package dev.lammertsma.parkingblues

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.lammertsma.parkingblues.shared.StoredAuth
import dev.lammertsma.parkingblues.shared.TokenStore
import dev.lammertsma.parkingblues.shared.model.AccountInfo
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the sign-in (refresh token included) in app-private storage, encrypted
 * with an AES-GCM key that lives in the Android Keystore and never leaves it.
 * The preferences file is excluded from backups and device transfer (see
 * res/xml/backup_rules.xml), so a restored phone starts signed out. Anything
 * that cannot be decrypted is treated as "signed out" rather than an error.
 */
class SecureTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredAuth? = runCatching {
        val encoded = prefs.getString(KEY_AUTH, null) ?: return null
        val json = JSONObject(decrypt(Base64.decode(encoded, Base64.NO_WRAP)))
        StoredAuth(
            refreshToken = json.getString("refresh"),
            accessToken = json.optString("access").ifEmpty { null },
            accessExpiresAtSeconds = json.getLong("expires"),
            account = AccountInfo(
                sub = json.getString("sub"),
                email = json.optString("email"),
                name = json.optString("name"),
                role = json.optString("role", "user"),
            ),
        )
    }.getOrNull()

    override fun save(auth: StoredAuth?) {
        if (auth == null) {
            prefs.edit().remove(KEY_AUTH).apply()
            return
        }
        val json = JSONObject()
            .put("refresh", auth.refreshToken)
            .put("access", auth.accessToken ?: "")
            .put("expires", auth.accessExpiresAtSeconds)
            .put("sub", auth.account.sub)
            .put("email", auth.account.email)
            .put("name", auth.account.name)
            .put("role", auth.account.role)
        val encrypted = encrypt(json.toString())
        prefs.edit().putString(KEY_AUTH, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** IV (12 bytes) followed by the ciphertext. */
    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(data: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
        }
        return String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val PREFS_NAME = "parking_blues_auth"
        const val KEY_AUTH = "auth"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "parking_blues_auth_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
