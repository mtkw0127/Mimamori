package io.github.mtkw0127.mimamori.pairing

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `HMAC-SHA256(key, parts を連結したもの)` を計算し、Base64 文字列で返す。
 *
 * `docs/security.md` §3.2 の `HMAC-SHA256(OTP, "viewer" || viewerId || viewerPublicKey)` を
 * そのままコードにしたもの。`key` には OTP(6桁の文字列)を渡す想定。
 * `parts` に `"viewer"`/`"recorder"` のラベルを含めることで、
 * 一方向の HMAC をもう一方向に使い回す攻撃（リフレクション攻撃）を防ぐ。
 */
fun hmacSha256(key: String, vararg parts: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val message = parts.joinToString(separator = "").toByteArray(Charsets.UTF_8)
    return Base64.getEncoder().encodeToString(mac.doFinal(message))
}

/** タイミング攻撃対策: 文字列の単純な `==` ではなく、長さに関わらず全バイトを比較する */
fun constantTimeEquals(a: String, b: String): Boolean {
    val aBytes = a.toByteArray(Charsets.UTF_8)
    val bBytes = b.toByteArray(Charsets.UTF_8)
    if (aBytes.size != bBytes.size) return false
    var result = 0
    for (i in aBytes.indices) {
        result = result or (aBytes[i].toInt() xor bBytes[i].toInt())
    }
    return result == 0
}
