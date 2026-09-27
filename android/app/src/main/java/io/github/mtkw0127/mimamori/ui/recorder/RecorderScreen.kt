package io.github.mtkw0127.mimamori.ui.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import io.github.mtkw0127.mimamori.webrtc.LocalCameraSession
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    onBack: () -> Unit,
    onOpenManualP2P: () -> Unit,
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
        if (hasCameraPermission && isLandscape) {
            // 開発用の手動 P2P 接続ボタンと、TopAppBar の代わりの戻るボタンを
            // プレビューの上に浮かせて表示する
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                CameraPreview(modifier = Modifier.fillMaxSize())
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
                OutlinedButton(
                    onClick = onOpenManualP2P,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Text("[開発用] 手動 P2P 接続（M2）")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (hasCameraPermission) {
                    CameraPreview(modifier = Modifier.weight(1f))
                } else {
                    Text(
                        "映像を撮影するにはカメラの権限が必要です。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("カメラの権限を許可する")
                    }
                }

                OutlinedButton(onClick = onOpenManualP2P, modifier = Modifier.fillMaxWidth()) {
                    Text("[開発用] 手動 P2P 接続（M2）")
                }
            }
        }
    }
}

/**
 * [RecorderViewModel] 経由で [LocalCameraSession] を使い、カメラ映像をプレビュー表示する。
 * カメラの生成・解放そのものはこの画面が入る/出るタイミングで [RecorderViewModel] に依頼するだけで、
 * 実際の WebRTC のオブジェクト操作は [LocalCameraSession] 側に閉じている。
 */
@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    viewModel: RecorderViewModel = hiltViewModel(),
) {
    val videoTrack by viewModel.videoTrack.collectAsStateWithLifecycle()
    val isFrontFacingCamera by viewModel.isFrontFacingCamera.collectAsStateWithLifecycle()
    var cameraNotFound by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            viewModel.start()
        } catch (_: LocalCameraSession.LocalCameraSessionStartException.CameraNotFoundException) {
            cameraNotFound = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    if (videoTrack != null) {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(viewModel.eglBase.eglBaseContext, null)
                    // Column の weight で割り当てられた領域いっぱいに映像を表示するので、
                    // 画面の向きに合わせて Compose 側でアスペクト比を計算する必要はない
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                }
            },
            update = { view ->
                // フロントカメラのときだけ左右反転する（背面カメラは反転しない）
                view.setMirror(isFrontFacingCamera == true)
                videoTrack?.addSink(view)
            },
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (cameraNotFound) "カメラを見つけることができませんでした" else "カメラを起動しています…",
                color = Color.White,
            )
        }
    }
}
