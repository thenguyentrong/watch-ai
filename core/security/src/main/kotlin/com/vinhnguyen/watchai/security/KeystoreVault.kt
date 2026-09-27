package com.vinhnguyen.watchai.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.vinhnguyen.watchai.brain.BrainLogger
import com.vinhnguyen.watchai.brain.LogEvent
import com.vinhnguyen.watchai.brain.SecretStore
import com.vinhnguyen.watchai.brain.code
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Secrets encrypted with a Tink AES-256-GCM keyset. The keyset itself is encrypted by an Android
 * Keystore key (StrongBox when the phone has it), so nothing readable ever touches the disk.
 *
 * - Files live in noBackupFilesDir and are excluded from backups and phone-to-phone transfer.
 * - Every ciphertext is bound to its purpose and name (associated data), so files can't be swapped.
 * - Anything that fails to decrypt wipes the vault; there is no plaintext fallback.
 * - The key is not bound to screen unlock, so watch requests keep working while the phone is locked.
 */
class KeystoreVault(
    context: Context,
    private val logger: BrainLogger = BrainLogger.None,
    private val alias: String = DEFAULT_ALIAS,
    dirName: String = "vault",
) : SecretStore {
    private val appContext = context.applicationContext
    private val pkg = appContext.packageName
    private val dir = File(appContext.noBackupFilesDir, dirName)
    private val keysetFile = AtomicFile(File(dir, "keyset.bin"))
    private val mutex = Mutex()
    private var aead: Aead? = null

    override suspend fun read(name: String): ByteArray? = locked {
        val file = secretFile(name)
        if (!file.baseFile.exists()) return@locked null
        try {
            // Opening the keyset first: if the master key is gone this clears the folder.
            val aead = dataAead()
            if (!file.baseFile.exists()) return@locked null
            aead.decrypt(file.readFully(), secretAd(name))
        } catch (e: KeyStoreException) {
            throw e // Keystore hiccup, not bad data: keep the secrets.
        } catch (e: VaultUnavailableException) {
            throw e
        } catch (e: GeneralSecurityException) {
            resetAfter(e)
            null
        }
    }

    override suspend fun write(
        name: String,
        value: ByteArray,
    ): Unit = locked {
        val aead =
            try {
                dataAead()
            } catch (e: KeyStoreException) {
                throw e
            } catch (e: VaultUnavailableException) {
                throw e
            } catch (e: GeneralSecurityException) {
                // The old keyset can't be opened any more (key invalidated): start a fresh vault.
                resetAfter(e)
                dataAead()
            }
        secretFile(name).writeAtomically(aead.encrypt(value, secretAd(name)))
    }

    override suspend fun delete(name: String): Unit = locked {
        secretFile(name).delete()
    }

    override suspend fun wipe(): Unit = locked { wipeLocked() }

    /** Where the master key lives, for diagnostics only (never secret). */
    suspend fun securityLevel(): SecurityLevel = locked {
        ensureMasterKey()
        keyInfo()?.let(::levelOf) ?: SecurityLevel.UNKNOWN
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun dataAead(): Aead = aead ?: run {
        ensureMasterKey()
        if (keyInfo()?.let(::levelOf) == SecurityLevel.SOFTWARE) throw VaultUnavailableException("software-only key")
        val master = withTransientRetry { AndroidKeystore.getAead(alias) }
        val handle =
            if (keysetFile.baseFile.exists()) {
                TinkProtoKeysetFormat.parseEncryptedKeyset(keysetFile.readFully(), master, keysetAd(), RegistryConfiguration.get())
            } else {
                KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).also {
                    keysetFile.writeAtomically(
                        TinkProtoKeysetFormat.serializeEncryptedKeyset(it, master, keysetAd(), RegistryConfiguration.get()),
                    )
                }
            }
        handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java).also { aead = it }
    }

    private fun ensureMasterKey() {
        if (withTransientRetry { AndroidKeystore.hasKey(alias) }) return
        // A new master key can't open an old keyset: start clean.
        dir.deleteRecursively()
        aead = null
        val strongBox = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        try {
            AndroidKeystore.generateNewKeyWithSpec(masterKeySpec(strongBox))
        } catch (_: StrongBoxUnavailableException) {
            AndroidKeystore.generateNewKeyWithSpec(masterKeySpec(strongBox = false))
        }
    }

    private fun masterKeySpec(strongBox: Boolean): KeyGenParameterSpec = KeyGenParameterSpec
        .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .setIsStrongBoxBacked(strongBox)
        .build()

    private fun keyInfo(): KeyInfo? = runCatching {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey ?: return null
        SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }.getOrNull()

    private fun resetAfter(e: Throwable) {
        logger.log(LogEvent.VaultReset(e.code))
        wipeLocked()
    }

    private fun wipeLocked() {
        aead = null
        dir.deleteRecursively()
        runCatching { AndroidKeystore.deleteKey(alias) }
    }

    private fun secretFile(name: String): AtomicFile {
        require(NAME.matches(name)) { "secret names are short lowercase identifiers" }
        return AtomicFile(File(dir, "$name.bin"))
    }

    private fun keysetAd() = "$pkg|vault-keyset|v1".toByteArray()

    private fun secretAd(name: String) = "$pkg|secret|$name|v1".toByteArray()

    private fun AtomicFile.writeAtomically(bytes: ByteArray) {
        dir.mkdirs()
        val out = startWrite()
        try {
            out.write(bytes)
            finishWrite(out)
        } catch (e: Exception) {
            failWrite(out)
            throw e
        }
    }

    private fun <T> withTransientRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: KeyStoreException) {
                val transient =
                    Build.VERSION.SDK_INT >= 33 &&
                        (e.cause as? android.security.KeyStoreException)?.isTransientFailure == true
                if (!transient || ++attempt >= 3) throw e
                Thread.sleep(50L * attempt)
            }
        }
    }

    enum class SecurityLevel { STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE, UNKNOWN }

    companion object {
        const val DEFAULT_ALIAS = "watchai_vault_master_v1"
        private val NAME = Regex("[a-z0-9_]{1,40}")

        init {
            AeadConfig.register()
        }

        private fun levelOf(info: KeyInfo): SecurityLevel = when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> SecurityLevel.SOFTWARE
            else -> SecurityLevel.UNKNOWN
        }
    }
}

class VaultUnavailableException(
    reason: String,
) : GeneralSecurityException(reason)
