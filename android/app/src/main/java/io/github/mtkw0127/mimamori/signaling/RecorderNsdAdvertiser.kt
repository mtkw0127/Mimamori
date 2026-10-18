package io.github.mtkw0127.mimamori.signaling

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Recorder が、自分の存在を同一 LAN 内に mDNS（[NsdManager]）で告知する。
 *
 * [android.content.pm.PackageManager] のような普通のサービスと違い、[NsdManager] は
 * 「見つけてもらう」ためにコールバックを渡す古いスタイルの API なので、
 * [suspendCancellableCoroutine] で包んで `suspend fun` にしている（M2 で SdpObserver に
 * やったのと同じパターン）。
 */
@Singleton
class RecorderNsdAdvertiser @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * `_mimamori._tcp.` として告知を始める。既に重複した名前が使われていれば、
     * Android 側が自動で名前を調整してくれる（実際に登録された名前を返す）。
     */
    suspend fun advertise(serviceName: String, port: Int): String =
        suspendCancellableCoroutine { continuation ->
            // mDNS はマルチキャスト通信を使うので、確実に受信/送信できるようロックを取っておく
            // （取らなくても動く端末もあるが、取らないと発見できない端末が実際にある）
            val lock = wifiManager.createMulticastLock("mimamori-advertise").apply {
                setReferenceCounted(true)
                acquire()
            }
            multicastLock = lock

            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                serviceType = MIMAMORI_SERVICE_TYPE
                this.port = port
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Timber.d("mDNS advertise: registered as %s", info.serviceName)
                    continuation.resume(info.serviceName)
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    continuation.resumeWithException(
                        IllegalStateException("mDNS registration failed: errorCode=$errorCode"),
                    )
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            registrationListener = listener
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

            continuation.invokeOnCancellation { stop() }
        }

    fun stop() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        multicastLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        multicastLock = null
    }
}
