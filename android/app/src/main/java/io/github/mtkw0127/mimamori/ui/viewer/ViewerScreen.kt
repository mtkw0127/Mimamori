package io.github.mtkw0127.mimamori.ui.viewer

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mtkw0127.mimamori.signaling.DiscoveredRecorder
import io.github.mtkw0127.mimamori.signaling.SignalingMessage
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val recorders by viewModel.discoveredRecorders.collectAsStateWithLifecycle()
    val pairedRecorderIds by viewModel.pairedRecorderIds.collectAsStateWithLifecycle()
    val pairingTarget by viewModel.pairingTarget.collectAsStateWithLifecycle()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val zoomInfo by viewModel.zoomInfo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    if (pairingTarget != null) {
        OtpEntryDialog(
            onConfirm = viewModel::submitOtp,
            onDismiss = viewModel::cancelPairingDialog,
        )
    }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 横向きで映像表示中は、映像をできるだけ大きく見せるため TopAppBar を隠して戻るボタンを重ねる
    val fullscreenVideo = isLandscape && status == ViewerConnectionStatus.CONNECTED

    Scaffold(
        topBar = {
            if (!fullscreenVideo) {
                TopAppBar(
                    title = { Text("Viewer") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (status == ViewerConnectionStatus.CONNECTED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                RemoteVideo(
                    videoTrack = remoteVideoTrack,
                    eglBaseContext = viewModel.eglBase.eglBaseContext,
                )
                ZoomControl(
                        zoomInfo = zoomInfo,
                        onZoomChange = viewModel::setZoomRatio,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                    )
                if (fullscreenVideo) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = Color.White,
                        )
                    }
                }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (status) {
                ViewerConnectionStatus.IDLE -> {
                    RecorderList(
                        recorders = recorders,
                        pairedRecorderIds = pairedRecorderIds,
                        onSelect = viewModel::selectRecorder,
                    )
                }

                ViewerConnectionStatus.CONNECTING,
                ViewerConnectionStatus.NEGOTIATING,
                -> {
                    Text("接続しています…", style = MaterialTheme.typography.bodyLarge)
                }

                ViewerConnectionStatus.CONNECTED -> Unit

                ViewerConnectionStatus.DISCONNECTED -> {
                    Text("Recorder との接続が切れました。", style = MaterialTheme.typography.bodyLarge)
                }

                ViewerConnectionStatus.FAILED -> {
                    Text("接続に失敗しました。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun RecorderList(
    recorders: List<DiscoveredRecorder>,
    pairedRecorderIds: Set<String>,
    onSelect: (DiscoveredRecorder) -> Unit,
) {
    if (recorders.isEmpty()) {
        Text(
            "同じ Wi-Fi 内の Recorder を探しています…",
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recorders, key = { it.name }) { recorder ->
            val isPaired = recorder.recorderId != null && recorder.recorderId in pairedRecorderIds
            val subtitle = when {
                isPaired -> "ペアリング済み・タップして接続"
                recorder.isPairingOpen -> "ペアリング待ち受け中・タップして OTP を入力"
                else -> "ペアリングされていません"
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(recorder) },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(recorder.name, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OtpEntryDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ペアリング") },
        text = {
            Column {
                Text("Recorder の画面に表示されている6桁の番号を入力してください。")
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it.filter(Char::isDigit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code) }, enabled = code.length == 6) {
                Text("ペアリング")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun RemoteVideo(
    videoTrack: org.webrtc.VideoTrack?,
    eglBaseContext: org.webrtc.EglBase.Context,
) {
    if (videoTrack == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("相手からの映像を待っています…", color = Color.White)
        }
        return
    }
    // 映像の縦横比。Recorder が回転すると変わるので、届いたフレームのサイズから更新する。
    var videoAspect by remember { mutableFloatStateOf(0f) }
    val isViewerLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isVideoLandscape = videoAspect > 1f
    // 向きが揃っているときは Recorder のプレビューと同じく領域いっぱいに表示する。
    // 向きが食い違うとき（Recorder が横・Viewer が縦など）は、クロップすると端が切れるので、
    // 映像全体が見えるよう縦横比を保って収める。
    val fitWhole = videoAspect > 0f && isVideoLandscape != isViewerLandscape
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(
                    eglBaseContext,
                    object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() = Unit
                        override fun onFrameResolutionChanged(
                            videoWidth: Int,
                            videoHeight: Int,
                            rotation: Int,
                        ) {
                            val rotated = rotation == 90 || rotation == 270
                            val width = if (rotated) videoHeight else videoWidth
                            val height = if (rotated) videoWidth else videoHeight
                            if (width > 0 && height > 0) {
                                videoAspect = width.toFloat() / height.toFloat()
                            }
                        }
                    },
                )
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        },
        update = { view -> videoTrack.addSink(view) },
        modifier = if (fitWhole) Modifier.aspectRatio(videoAspect) else Modifier.fillMaxSize(),
    )
}

/**
 * Recorder のカメラのズーム倍率を遠隔で変える UI。
 *
 * 映像の邪魔にならないよう、普段は倍率だけの小さなボタンにしておき、
 * タップしたときだけ短いスライダーを出す。操作が止まってしばらくしたら自動で閉じる。
 *
 * ドラッグ中は手元の値を表示する: Recorder から返ってくる倍率をそのまま表示すると、
 * 送った値と返ってきた値が行き違って、つまみが指の位置からガタつくため。
 * 指を離したら Recorder から届いた値の表示に戻す。
 */
@Composable
private fun ZoomControl(
    zoomInfo: SignalingMessage.ZoomInfo?,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoom = zoomInfo?.takeIf { it.maxRatio > it.minRatio } ?: return
    var expanded by remember { mutableStateOf(false) }
    var draggingRatio by remember { mutableStateOf<Float?>(null) }
    // 最後に操作した時刻。これが変わるたびに自動で閉じるまでのタイマーをやり直す
    var lastInteraction by remember { mutableLongStateOf(0L) }
    val ratio = draggingRatio ?: zoom.ratio

    LaunchedEffect(expanded, lastInteraction, draggingRatio) {
        if (expanded && draggingRatio == null) {
            delay(3_000)
            expanded = false
        }
    }

    Row(
        modifier = modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded) {
            Slider(
                value = ratio.coerceIn(zoom.minRatio, zoom.maxRatio),
                onValueChange = {
                    draggingRatio = it
                    lastInteraction = System.currentTimeMillis()
                    onZoomChange(it)
                },
                onValueChangeFinished = { draggingRatio = null },
                valueRange = zoom.minRatio..zoom.maxRatio,
                modifier = Modifier
                    .width(160.dp)
                    .padding(start = 12.dp),
            )
        }
        Text(
            "%.1fx".format(ratio),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(CircleShape)
                .clickable {
                    expanded = !expanded
                    lastInteraction = System.currentTimeMillis()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
