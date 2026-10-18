package io.github.mtkw0127.mimamori.pairing

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 再接続時の認証（docs/security.md §4）で署名する対象のバイト列を作る。
 *
 * `label` を先頭に入れるのは、ある用途の署名を別の用途に使い回されないようにするため
 * （challenge への署名を answer の署名として流用される、など）。
 * 各要素は改行で区切る。SDP は途中に改行を含むが、必ず最後の要素にするので曖昧にならない。
 */
fun signedPayload(label: String, vararg parts: String): ByteArray =
    (listOf(label) + parts).joinToString(separator = "\n").toByteArray(Charsets.UTF_8)

/** チャレンジ用のランダムな nonce（32 バイト）を Base64 で返す。リプレイ攻撃対策なので毎回新しく作る */
fun newNonceBase64(): String {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return Base64.getEncoder().encodeToString(bytes)
}

/** [DeviceIdentity.sign] の結果（DER 形式）を JSON で送れるよう Base64 にする */
fun DeviceIdentity.signBase64(data: ByteArray): String =
    Base64.getEncoder().encodeToString(sign(data))

/**
 * SubjectPublicKeyInfo(X.509) 形式の公開鍵（Base64）で、ECDSA(SHA-256) の DER 署名（Base64）を検証する。
 * 形式が壊れているなど、検証以前に失敗した場合も例外ではなく false を返す。
 */
fun verifySignature(publicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean =
    try {
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(data)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    } catch (e: Exception) {
        false
    }
