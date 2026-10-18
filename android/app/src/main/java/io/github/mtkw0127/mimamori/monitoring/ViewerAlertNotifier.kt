package io.github.mtkw0127.mimamori.monitoring

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mtkw0127.mimamori.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Viewer 端末に見守りの通知を出す。
 *
 * サーバーを使わない設計（docs/architecture.md §1）なので、FCM などのプッシュ通知は使わず、
 * Recorder から TCP で届いたメッセージをもとに、この端末の中で通知を出す。
 * そのため Viewer が Recorder に接続していないあいだは、通知は届かない。
 * 「通知が来ない＝異常なし」と誤解させないよう、接続が切れたこと自体も通知する。
 */
@Singleton
class ViewerAlertNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        // 重要度 HIGH: 音を鳴らし、画面上部にも表示する。ユーザーが設定で変えたらそちらが優先される
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "見守りの通知", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "顔が見えなくなったときや、Recorder との接続が切れたときに通知します"
            },
        )
    }

    fun notifyFaceNotVisible(recorderName: String) {
        post(
            FACE_NOT_VISIBLE_ID,
            title = "顔が見えません",
            text = "$recorderName で、顔がしばらく映っていません。様子を確認してください",
        )
    }

    fun cancelFaceNotVisible() = manager.cancel(FACE_NOT_VISIBLE_ID)

    fun notifyMonitoringStopped(recorderName: String) {
        post(
            MONITORING_STOPPED_ID,
            title = "見守りが止まっています",
            text = "$recorderName との接続が切れました。接続し直すまで通知は届きません",
        )
    }

    fun cancelMonitoringStopped() = manager.cancel(MONITORING_STOPPED_ID)

    private fun post(id: Int, title: String, text: String) {
        // Android 13 以降は通知の権限が必要。許可されていなければ何もできないので、画面側のバナーだけになる
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    private companion object {
        const val CHANNEL_ID = "monitoring_alert"
        const val FACE_NOT_VISIBLE_ID = 1
        const val MONITORING_STOPPED_ID = 2
    }
}
