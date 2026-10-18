package io.github.mtkw0127.mimamori.ui.common

import android.app.Activity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * この画面を表示している間だけ、ナビゲーションバーを隠す。
 * 画面端からスワイプすると一時的に表示され、しばらくすると自動でまた隠れる。
 * 画面を離れたら元に戻す（ロール選択画面などではナビゲーションバーを出したままにしたいため）。
 */
@Composable
fun HideNavigationBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

/**
 * 映像をインカメ（ディスプレイカットアウト）の部分まで広げたとき、その上に重ねるボタン類が
 * カットアウトやシステムバーに隠れないよう、`enabled` のときだけ安全な領域まで内側に寄せる。
 */
fun Modifier.safeDrawingPaddingIf(enabled: Boolean): Modifier =
    if (enabled) safeDrawingPadding() else this
