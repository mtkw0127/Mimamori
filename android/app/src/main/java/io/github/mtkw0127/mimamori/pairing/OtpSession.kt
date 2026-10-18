package io.github.mtkw0127.mimamori.pairing

import java.security.SecureRandom

/**
 * Recorder が発行する、今アクティブな OTP 1回分。`docs/security.md` §3.1。
 *
 * - 数字6桁、有効期限5分、使用回数1回、失敗許容5回。
 * - 乱数は [SecureRandom]（暗号学的に安全な乱数。`java.util.Random` ではない）で生成する。
 */
class OtpSession(
    val code: String = generateCode(),
    private val expiresAtMillis: Long = System.currentTimeMillis() + EXPIRY_MILLIS,
) {
    private var failureCount = 0

    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMillis
    val remainingMillis: Long get() = (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
    val isLocked: Boolean get() = failureCount >= MAX_FAILURES

    /** 試せる状態か（期限内かつロックされていない） */
    val isUsable: Boolean get() = !isExpired && !isLocked

    fun recordFailure() {
        failureCount++
    }

    companion object {
        private const val EXPIRY_MILLIS = 5 * 60 * 1000L
        private const val MAX_FAILURES = 5

        private fun generateCode(): String {
            val random = SecureRandom()
            val value = random.nextInt(1_000_000) // 0 〜 999999
            return value.toString().padStart(6, '0')
        }
    }
}
