package com.vinhnguyen.watchai.brain

/**
 * Encrypted storage for small secrets (sign-in tokens). The Android implementation lives in
 * core:security; tests use an in-memory fake. Implementations never fall back to plaintext:
 * if the key is gone or the data doesn't decrypt, they wipe and return null.
 */
public interface SecretStore {
    public suspend fun read(name: String): ByteArray?

    public suspend fun write(
        name: String,
        value: ByteArray,
    )

    public suspend fun delete(name: String)

    /** Deletes every secret and the key that protects them. */
    public suspend fun wipe()
}
