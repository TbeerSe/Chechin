package com.example.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Crypto uses android.util.Base64 and org.json, so it is checked on a device/emulator. */
@RunWith(AndroidJUnit4::class)
class CryptoInstrumentedTest {

    @Test
    fun roundTripWithCorrectPassword() {
        val plain = """{"entries":[],"note":"привет"}"""
        val enc = Crypto.encrypt(plain, "correct horse")
        assertTrue(Crypto.isEncrypted(enc))
        assertEquals(plain, Crypto.decrypt(enc, "correct horse"))
    }

    @Test
    fun wrongPasswordIsRejected() {
        val enc = Crypto.encrypt("secret", "right")
        try {
            Crypto.decrypt(enc, "wrong")
            fail("wrong password must not decrypt")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun plainJsonIsNotMistakenForEncrypted() {
        assertFalse(Crypto.isEncrypted("""{"schemaVersion":5,"entries":[]}"""))
        assertFalse(Crypto.isEncrypted("not json"))
    }

    @Test
    fun twoEncryptionsDiffer() {
        // fresh salt and IV every time
        assertFalse(Crypto.encrypt("same", "pw") == Crypto.encrypt("same", "pw"))
    }
}
