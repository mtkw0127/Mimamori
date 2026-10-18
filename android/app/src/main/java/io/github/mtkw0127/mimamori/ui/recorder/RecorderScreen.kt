package io.github.mtkw0127.mimamori.ui.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mtkw0127.mimamori.ui.common.HideNavigationBar
import io.github.mtkw0127.mimamori.ui.common.safeDrawingPaddingIf
import io.github.mtkw0127.mimamori.webrtc.LocalCameraSession
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    HideNavigationBar()

    Scaffold(
        topBar = {
            // 横向きのときは TopAppBar 自体を消して、プレビューが画面いっぱいに見えるようにする
            if (!isLandscape) {
                TopAppBar(
                    title = { Text("Recorder") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        // CameraPreview() の呼び出しは、向きが変わっても絶対にここ1箇所だけにする。
        // 以前は landscape/portrait で別々の if/else 分岐の中にそれぞれ CameraPreview() を
        // 置いていたが、回転でその分岐が切り替わるたびに Compose が古い方を破棄・新しい方を
        // 新規マウントしてしまい、DisposableEffect の onDispose（= カメラと VideoTrack の破棄）
        // と LaunchedEffect の再実行（= 新しい VideoTrack の生成）が起きていた。
        // 新しい VideoTrack はすでにネゴシエーション済みの PeerConnection には繋がらないので、
        // 結果として Viewer 側の映像が回転のたびに永久に止まっていた。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 横向きではプレビューをインカメの部分まで広げる（ボタン類だけ安全な領域に寄せる）
                .padding(if (isLandscape) PaddingValues() else innerPadding),
        ) {
            if (hasCameraPermission) {
                CameraPreview(modifier = Modifier.fillMaxSize())

                // 横向きのときだけ、戻るボタンをプレビューの上に浮かせて表示する
                // （縦向きは Scaffold の topBar 側に出ている）
                if (isLandscape) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .safeDrawingPadding()
                            .padding(8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = Color.White,
                        )
                    }
                }
                SignalingStatusLabel(
                    modifier = Modifier
                        .align(if (isLandscape) Alignment.TopEnd else Alignment.TopStart)
                        .safeDrawingPaddingIf(isLandscape)
                        .padding(16.dp),
                    color = Color.White,
                )
                PairingControls(
                    modifier = Modifier
                        .align(if (isLandscape) Alignment.BottomEnd else Alignment.TopEnd)
                        .safeDrawingPaddingIf(isLandscape)
                        .padding(16.dp),
                    textColor = Color.White,
                )
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "映像を撮影するにはカメラの権限が必要です。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("カメラの権限を許可する")
                    }
                }
            }
        }
    }
}

/** mDNS 告知〜TCP シグナリング〜WebRTC 接続の、今の段階を一言で表示する */
@Composable
private fun SignalingStatusLabel(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    viewModel: RecorderViewModel = hiltViewModel(),
) {
    val status by viewModel.signalingStatus.collectAsStateWithLifecycle()
    val text = when (status) {
        SignalingStatus.WAITING_FOR_VIEWER -> "Viewer の接続を待っています…"
        SignalingStatus.NEGOTIATING -> "接続中…"
        SignalingStatus.CONNECTED -> "Viewer が接続しています"
        SignalingStatus.DISCONNECTED -> "Viewer が切断しました"
        SignalingStatus.FAILED -> "接続に失敗しました"
    }
    Text(text, modifier = modifier, color = color, style = MaterialTheme.typography.bodyMedium)
}

/**
 * 「Viewer を追加」ボタンと、ペアリング受付中は代わりに OTP を表示する（M4）。
 * OTP はこの画面を見ている人にしか分からないので、これを入力できる Viewer だけが
 * ペアリングできる（docs/security.md §3.2）。
 */
@Composable
private fun PairingControls(
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    viewModel: RecorderViewModel = hiltViewModel(),
) {
    val otp by viewModel.otpCode.collectAsStateWithLifecycle()
    val pairedViewers by viewModel.pairedViewers.collectAsStateWithLifecycle()
    var showManageDialog by remember { mutableStateOf(false) }
    val code = otp

    if (showManageDialog) {
        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("ペアリング済みの Viewer") },
            text = {
                if (pairedViewers.isEmpty()) {
                    Text("ペアリング済みの Viewer はありません")
                } else {
                    Column {
                        pairedViewers.forEach { viewer ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    viewer.displayName + " (" + viewer.viewerId.take(8) + ")",
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.revokeViewer(viewer.viewerId) }) {
                                    Text("削除")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageDialog = false }) { Text("閉じる") }
            },
        )
    }

    if (code == null) {
        Column(modifier = modifier, horizontalAlignment = Alignment.End) {
            OutlinedButton(onClick = viewModel::startPairing) { Text("Viewer を追加") }
            TextButton(onClick = {
                viewModel.refreshPairedViewers()
                showManageDialog = true
            }) { Text("Viewer を管理", color = textColor) }
        }
    } else {
        Column(modifier = modifier, horizontalAlignment = Alignment.End) {
            Text(
                "この番号を Viewer に入力してもらってください",
                color = textColor,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(code, color = textColor, style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = viewModel::cancelPairing) { Text("キャンセル") }
        }
    }
}

/**
 * [RecorderViewModel] 経由で [LocalCameraSession] を使い、カメラ映像をプレビュー表示する。
 * あわせて、mDNS での告知と TCP でのシグナリングもこの画面に入ったタイミングで開始する（M3）。
 */
@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    viewModel: RecorderViewModel = hiltViewModel(),
) {
    val videoTrack by viewModel.videoTrack.collectAsStateWithLifecycle()
    val isFrontFacingCamera by viewModel.isFrontFacingCamera.collectAsStateWithLifecycle()
    val currentCameraDeviceName by viewModel.currentCameraDeviceName.collectAsStateWithLifecycle()
    val availableCameras by viewModel.availableCameras.collectAsStateWithLifecycle()
    val zoomInfo by viewModel.zoomInfo.collectAsStateWithLifecycle()
    var cameraNotFound by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            viewModel.start()
            // カメラ映像の準備ができたら、Viewer からの接続を待ち受け始める
            viewModel.startSignaling()
        } catch (_: LocalCameraSession.LocalCameraSessionStartException.CameraNotFoundException) {
            cameraNotFound = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    // 端末を回転させるたびに、カメラの「正立させるための回転角」を更新する。
    // （MainActivity は configChanges で回転時の再生成を止めているので、ここで自分で検知する必要がある）
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        viewModel.updateRotation(ContextCompat.getDisplayOrDefault(context).rotation)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (videoTrack != null) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(viewModel.eglBase.eglBaseContext, null)
                        // 割り当てられた領域いっぱいに映像を表示するので、
                        // 画面の向きに合わせて Compose 側でアスペクト比を計算する必要はない
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    }
                },
                update = { view ->
                    // フロントカメラのときだけ左右反転する（背面カメラは反転しない）
                    view.setMirror(isFrontFacingCamera == true)
                    videoTrack?.addSink(view)
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (cameraNotFound) "カメラを見つけることができませんでした" else "カメラを起動しています…",
                    color = Color.White,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPaddingIf(
                    configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 連続ズーム: 今のレンズがズームに対応しているときだけスライダーを出す
            zoomInfo?.takeIf { it.maxRatio > it.minRatio }?.let { zoom ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Slider(
                        value = zoom.ratio,
                        onValueChange = { viewModel.setZoomRatio(it) },
                        valueRange = zoom.minRatio..zoom.maxRatio,
                        modifier = Modifier.width(160.dp),
                    )
                    Text("%.1fx".format(zoom.ratio), color = Color.White)
                }
            }

            // 段階ズーム: 端末が複数レンズを持っている場合だけ、切り替えの行を表示する
            if (availableCameras.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableCameras.forEach { option ->
                        FilterChip(
                            selected = option.deviceName == currentCameraDeviceName,
                            onClick = { viewModel.switchCamera(option.deviceName) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        }
    }
}
