package app.penny.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the bridge's access token for storage. */
interface TokenCipher {
    fun encrypt(plain: String): String

    /** Null when [stored] can't be decrypted (e.g. the key is gone); the phone then has to pair again. */
    fun decrypt(stored: String): String?
}

/** AES-GCM with a key that never leaves the Android Keystore. Stored as "v1:<iv>:<ciphertext>", Base64. */
class KeystoreTokenCipher(private val alias: String = "penny_bridge_token") : TokenCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoder = Base64.getEncoder()
        return "$VERSION:${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(cipher.doFinal(plain.toByteArray()))}"
    }

    override fun decrypt(stored: String): String? = runCatching {
        val (version, iv, data) = stored.split(':').takeIf { it.size == 3 } ?: return null
        if (version != VERSION) return null
        val key = existingKey() ?: return null
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decoder.decode(iv)))
        String(cipher.doFinal(decoder.decode(data)))
    }.getOrNull()

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION = "v1"
    }
}
