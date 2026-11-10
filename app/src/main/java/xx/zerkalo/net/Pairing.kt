package xx.zerkalo.net

import xx.zerkalo.NONCE_SIZE
import xx.zerkalo.PIN_LENGTH
import xx.zerkalo.PIN_LOCKOUT_MS
import xx.zerkalo.PIN_MAX_FAILURES
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC = "HmacSHA256"
private val PAIR_LABEL = "zerkalo-pair".toByteArray(Charsets.US_ASCII)
private val AUTH_LABEL = "zerkalo-auth".toByteArray(Charsets.US_ASCII)

private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC)
    mac.init(SecretKeySpec(key, HMAC))
    parts.forEach(mac::update)
    return mac.doFinal()
}

/** Whether [pin] is exactly the digits a receiver shows. */
fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

/** The key both sides derive from the PIN, and keep as the token once pairing succeeds. */
fun pairKey(pin: String, nonceS: ByteArray, nonceR: ByteArray): ByteArray {
    require(isValidPin(pin)) { "Not a PIN" }
    return hmac(pin.toByteArray(Charsets.US_ASCII), PAIR_LABEL, nonceS, nonceR)
}

/** What the sender shows to prove it holds [key]. */
fun authProof(key: ByteArray, nonceS: ByteArray, nonceR: ByteArray): ByteArray =
    hmac(key, AUTH_LABEL, nonceS, nonceR)

/** Whether [proof] was made with [key]. Compared in constant time. */
fun verifyProof(key: ByteArray, nonceS: ByteArray, nonceR: ByteArray, proof: ByteArray): Boolean =
    MessageDigest.isEqual(authProof(key, nonceS, nonceR), proof)

fun newNonce(random: Random = SecureRandom()): ByteArray = ByteArray(NONCE_SIZE).also(random::nextBytes)

/** A PIN of [PIN_LENGTH] Latin digits, whatever the device's locale writes numbers in. */
fun newPin(random: Random = SecureRandom()): String =
    (1..PIN_LENGTH).joinToString("") { random.nextInt(10).toString() }

/**
 * The receiver's current PIN and its defence against guessing. Changing the PIN alone
 * would not slow a guesser down, so failures also lock PIN attempts out for a while.
 * Used from the one thread that handles incoming connections.
 */
class PinGuard(private val now: () -> Long, private val generate: () -> String = { newPin() }) {
    var pin: String = generate()
        private set

    private var failures = 0
    private var lockedUntil = 0L

    /** While true a PIN attempt is refused without being checked. */
    fun isLocked(): Boolean = now() < lockedUntil

    fun onFailure() {
        failures++
        if (failures >= PIN_MAX_FAILURES) {
            failures = 0
            pin = generate()
            lockedUntil = now() + PIN_LOCKOUT_MS
        }
    }

    fun onSuccess() {
        failures = 0
    }
}
