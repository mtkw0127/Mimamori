package io.github.mtkw0127.mimamori.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * この端末自身の「身元」。ペアリング（M4）と、将来の再接続認証（M5）の両方で使う。
 *
 * - [id]: この端末を表す、使い捨てではない固定の文字列。乱数を1回作って
 *   [android.content.SharedPreferences] に保存しておくだけ（秘密情報ではない）。
 * - 署名用の鍵ペア（ECDSA P-256）は [KeyStore]（Android Keystore）の中で生成する。
 *   秘密鍵はハードウェアの外に**絶対に出ない**（`docs/security.md` §2）。
 *   取り出せるのは公開鍵([publicKeyBase64])と、秘密鍵を使った署名の結果だけ。
 */
@Singleton
class DeviceIdentity @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("mimamori_identity", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    val id: String = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_ID, it).apply()
    }

    /** SubjectPublicKeyInfo（X.509）形式の公開鍵を Base64 にしたもの。JSON でそのまま送れる */
    val publicKeyBase64: String by lazy {
        Base64.getEncoder().encodeToString(publicKeyEntry().encoded)
    }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateKeyPair()
        }
    }

    /** `data` に対する ECDSA(SHA-256) 署名を DER 形式で返す（M5 の再接続認証で使う予定） */
    fun sign(data: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey as java.security.PrivateKey)
            update(data)
        }.sign()
    }

    private fun publicKeyEntry() =
        keyStore.getCertificate(KEY_ALIAS).publicKey

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mimamori-identity"
        const val KEY_ID = "device_id"
    }
}
