package io.github.mtkw0127.mimamori.signaling

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** mDNS で見つかった Recorder 1台分。[host]/[port] は Viewer が TCP で接続しに行く先 */
data class DiscoveredRecorder(
    val name: String,
    val host: InetAddress,
    val port: Int,
)

/**
 * Viewer が、同一 LAN 内の Recorder（`_mimamori._tcp.`）を mDNS で探す。
 *
 * [NsdManager.discoverServices] は「見つかった」「消えた」をコールバックで教えてくれるだけの API。
 * [NsdManager.resolveService] で、さらに実際の IP アドレス・ポートを取得する必要がある
 * （`onServiceFound` の時点ではまだ IP アドレスが分からない）。
 *
 * [callbackFlow] を使うと、こういう「複数回呼ばれるコールバック」をそのまま [Flow] にできる。
 */
@Singleton
class ViewerNsdDiscoverer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** 見つかっている Recorder の一覧。発見・消失のたびに新しいリストが流れる */
    fun discover(): Flow<List<DiscoveredRecorder>> = callbackFlow {
        val lock = wifiManager.createMulticastLock("mimamori-discover").apply {
            setReferenceCounted(true)
            acquire()
        }

        // サービス名 → 解決済みの情報。同じ名前が複数回見つかることもあるので Map で管理する
        val found = LinkedHashMap<String, DiscoveredRecorder>()

        fun sendCurrentList() {
            trySend(found.values.toList())
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.w("mDNS resolve failed for %s: errorCode=%d", info.serviceName, errorCode)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host ?: return
                found[info.serviceName] = DiscoveredRecorder(info.serviceName, host, info.port)
                sendCurrentList()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.d("mDNS discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                // onServiceFound の時点ではまだ IP アドレスが無いので、resolve して初めて使える
                nsdManager.resolveService(info, resolveListener)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                sendCurrentList()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("mDNS discovery failed to start: errorCode=$errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsdManager.discoverServices(MIMAMORI_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        sendCurrentList() // 最初は空リストを出しておく

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            if (lock.isHeld) runCatching { lock.release() }
        }
    }
}
