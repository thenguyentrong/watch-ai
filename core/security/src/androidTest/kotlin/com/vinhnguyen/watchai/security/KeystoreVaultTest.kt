package com.vinhnguyen.watchai.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.vinhnguyen.watchai.brain.LogEvent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val events = mutableListOf<LogEvent>()
    private val alias = "watchai_vault_test"
    private val dirName = "vault_test"
    private lateinit var vault: KeystoreVault
    private val canary = "SYNTHETIC_TOKEN_CANARY_0001"

    @Before
    fun setUp() = runTest {
        vault = KeystoreVault(context, { events += it }, alias, dirName)
        vault.wipe()
    }

    @After
    fun tearDown() = runTest { vault.wipe() }

    @Test
    fun roundTrip() = runTest {
        vault.write("chatgpt_tokens", canary.toByteArray())
        assertThat(vault.read("chatgpt_tokens")?.decodeToString()).isEqualTo(canary)
    }

    @Test
    fun nothingReadableOnDisk() = runTest {
        vault.write("chatgpt_tokens", canary.toByteArray())
        val dir = File(context.noBackupFilesDir, dirName)
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            assertThat(file.readBytes().decodeToString(throwOnInvalidSequence = false)).doesNotContain(canary)
        }
    }

    @Test
    fun swappedFileDoesNotDecryptAndWipes() = runTest {
        vault.write("aaa", "SYNTHETIC_A".toByteArray())
        vault.write("bbb", "SYNTHETIC_B".toByteArray())
        val dir = File(context.noBackupFilesDir, dirName)
        File(dir, "bbb.bin").copyTo(File(dir, "aaa.bin"), overwrite = true)
        assertThat(vault.read("aaa")).isNull()
        assertThat(events.filterIsInstance<LogEvent.VaultReset>()).isNotEmpty()
        assertThat(vault.read("bbb")).isNull()
    }

    @Test
    fun tamperedByteWipes() = runTest {
        vault.write("chatgpt_tokens", canary.toByteArray())
        val file = File(File(context.noBackupFilesDir, dirName), "chatgpt_tokens.bin")
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
        assertThat(vault.read("chatgpt_tokens")).isNull()
    }

    @Test
    fun deletedKeyMeansEmptyVaultNotCrash() = runTest {
        vault.write("chatgpt_tokens", canary.toByteArray())
        java.security.KeyStore
            .getInstance("AndroidKeyStore")
            .apply { load(null) }
            .deleteEntry(alias)
        val fresh = KeystoreVault(context, { events += it }, alias, dirName)
        assertThat(fresh.read("chatgpt_tokens")).isNull()
        fresh.write("chatgpt_tokens", "SYNTHETIC_NEW".toByteArray())
        assertThat(fresh.read("chatgpt_tokens")?.decodeToString()).isEqualTo("SYNTHETIC_NEW")
    }

    @Test
    fun keyIsHardwareBacked() = runTest {
        vault.write("x", byteArrayOf(1))
        val hasStrongBox = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val expected = if (hasStrongBox) KeystoreVault.SecurityLevel.STRONGBOX else KeystoreVault.SecurityLevel.TRUSTED_ENVIRONMENT
        assertThat(vault.securityLevel()).isEqualTo(expected)
    }
}
