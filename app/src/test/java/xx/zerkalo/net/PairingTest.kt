package xx.zerkalo.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xx.zerkalo.NONCE_SIZE
import xx.zerkalo.PIN_LOCKOUT_MS
import xx.zerkalo.PIN_MAX_FAILURES
import xx.zerkalo.hex
import java.util.Random

class PairingTest {
    private val nonceS = ByteArray(16) { 0x11 }
    private val nonceR = ByteArray(16) { 0x22 }

    // Computed independently of this code, with Python's hmac module.
    private val goldenKey = hex("c486a73e944bc33c69758570aa5699e1e3465579accafd98ac003a0d43f32c25")
    private val goldenProof = hex("81e79e2acc9648bb393b626ac3b83e80b558913e1f125daff9346342f58e225c")

    @Test
    fun keyMatchesIndependentVector() {
        assertArrayEquals(goldenKey, pairKey("1234", nonceS, nonceR))
    }

    @Test
    fun proofMatchesIndependentVector() {
        assertArrayEquals(goldenProof, authProof(goldenKey, nonceS, nonceR))
    }

    @Test
    fun rightProofVerifies() {
        assertTrue(verifyProof(goldenKey, nonceS, nonceR, goldenProof))
    }

    @Test
    fun wrongPinDoesNotVerify() {
        val wrong = authProof(pairKey("1235", nonceS, nonceR), nonceS, nonceR)
        assertFalse(verifyProof(goldenKey, nonceS, nonceR, wrong))
    }

    @Test
    fun proofDoesNotReplayUnderNewNonce() {
        val otherNonce = ByteArray(16) { 0x33 }
        assertFalse(verifyProof(goldenKey, nonceS, otherNonce, goldenProof))
    }

    @Test
    fun malformedPinIsRefused() {
        listOf("", "123", "12345", "12a4", "١٢٣٤").forEach { pin ->
            assertFalse(pin, isValidPin(pin))
            assertThrows(pin, IllegalArgumentException::class.java) { pairKey(pin, nonceS, nonceR) }
        }
    }

    @Test
    fun generatedPinIsValid() {
        val random = Random(1)
        repeat(1000) { assertTrue(isValidPin(newPin(random))) }
    }

    @Test
    fun leadingZerosAreKept() {
        val zeros = object : Random() {
            override fun nextInt(bound: Int): Int = 0
        }
        assertEquals("0000", newPin(zeros))
    }

    @Test
    fun noncesDiffer() {
        val first = newNonce()
        assertEquals(NONCE_SIZE, first.size)
        assertFalse(first.contentEquals(newNonce()))
    }

    // ---------- PinGuard ----------

    private var time = 1_000L
    private var issued = 0

    private fun guard() = PinGuard(now = { time }, generate = { (issued++).toString().padStart(4, '0') })

    @Test
    fun fewFailuresKeepPinAndDoNotLock() {
        val guard = guard()
        val pin = guard.pin
        repeat(PIN_MAX_FAILURES - 1) { guard.onFailure() }
        assertEquals(pin, guard.pin)
        assertFalse(guard.isLocked())
    }

    @Test
    fun maxFailuresChangePinAndLock() {
        val guard = guard()
        val pin = guard.pin
        repeat(PIN_MAX_FAILURES) { guard.onFailure() }
        assertNotEquals(pin, guard.pin)
        assertTrue(guard.isLocked())
    }

    @Test
    fun lockEndsExactlyOnTime() {
        val guard = guard()
        repeat(PIN_MAX_FAILURES) { guard.onFailure() }
        time += PIN_LOCKOUT_MS - 1
        assertTrue(guard.isLocked())
        time += 1
        assertFalse(guard.isLocked())
    }

    @Test
    fun successResetsTheCount() {
        val guard = guard()
        val pin = guard.pin
        repeat(PIN_MAX_FAILURES - 1) { guard.onFailure() }
        guard.onSuccess()
        repeat(PIN_MAX_FAILURES - 1) { guard.onFailure() }
        assertEquals(pin, guard.pin)
        assertFalse(guard.isLocked())
    }

    @Test
    fun failureCountStartsOverAfterLock() {
        val guard = guard()
        repeat(PIN_MAX_FAILURES) { guard.onFailure() }
        val pin = guard.pin
        time += PIN_LOCKOUT_MS
        repeat(PIN_MAX_FAILURES - 1) { guard.onFailure() }
        assertEquals(pin, guard.pin)
        assertFalse(guard.isLocked())
    }
}
