package com.example.checkin

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected backup: PBKDF2-SHA256 -> AES-256-GCM. */
object Crypto {
    private const val ITER = 600_000

    /** A crafted file must not be able to hang the app on key derivation. */
    private const val MAX_IMPORT_ITER = 800_000   // own exports use 600k; higher = minutes-long hang on phone

    private fun key(pw: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun isEncrypted(text: String): Boolean = try {
        JSONObject(text).optString("format") == "checkin-encrypted"
    } catch (e: Exception) {
        false
    }

    fun encrypt(plain: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITER), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val o = JSONObject()
        o.put("format", "checkin-encrypted")
        o.put("v", 1)
        o.put("kdf", "PBKDF2WithHmacSHA256")
        o.put("iter", ITER)
        o.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        o.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
        o.put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        return o.toString(2)
    }

    fun decrypt(wrapper: String, password: String): String {
        try {
            val o = JSONObject(wrapper)
            val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(o.getString("data"), Base64.NO_WRAP)
            val iter = o.optInt("iter", ITER)
            // Not silently clamped: a clamped value derives the wrong key and reads as "wrong password".
            if (iter < 1 || iter > MAX_IMPORT_ITER)
                throw UnsupportedOperationException("Файл зашифрован с неподдерживаемыми параметрами")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iter), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Неверный пароль или файл повреждён")
        }
    }
}
