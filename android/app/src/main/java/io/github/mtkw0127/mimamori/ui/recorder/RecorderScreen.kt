package io.github.mtkw0127.mimamori.ui.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import org.webrtc.Camera2Enumerator
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import timber.log.Timber

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recorder") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hasCameraPermission) {
                PreviewPlaceholder()
            } else {
                Text("映像を撮影するにはカメラの権限が必要です。", style = MaterialTheme.typography.bodyLarge)
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

@Composable
private fun PreviewPlaceholder() {
    val context = LocalContext.current
    val eglBase = remember { EglBase.create() }
    val factory = remember {
        // 1. WebRTC全体の司令塔（PeerConnectionFactory）を用意
        val initializationOptions = PeerConnectionFactory.InitializationOptions
            .builder(context) // ApplicationContext を渡す
            .setEnableInternalTracer(true) // 内部のトレース（ログ）機能を有効にするか
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }
    val videoCapture = remember {
        createCameraCapturer(context)
    }
    val videoSource = remember {
        factory.createVideoSource(videoCapture?.isScreencast ?: return@remember null)
    }
    val surfaceTextureHelper = remember {
        if(videoSource == null) return@remember null
        SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
    }
    val videoTrack = remember {
        if(videoCapture == null || videoSource == null) return@remember null
        // 4. ソースを元に、ようやく「VideoTrack」が完成！
        factory.createVideoTrack("VIDEO_TRACK_ID", videoSource)
    }
    val initialized = remember {
        if(videoSource != null && videoCapture != null && videoTrack != null) {
            videoCapture.initialize(
                surfaceTextureHelper,
                context,
                videoSource.capturerObserver // これにより Source と Capturer が結びつきます
            )
            videoCapture.startCapture(1280, 720, 15) // 解像度とFPSを指定
            true
        } else {
            false
        }
    }
    val configuration = LocalConfiguration.current
    val aspectRatio = remember(configuration.orientation) {
        if(configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            9f / 16f
        } else {
            16f / 9f
        }
    }

    when(initialized) {
        true -> {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(eglBase.eglBaseContext, null)
                        setMirror(true)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    }
                },
                update = { view ->
                    checkNotNull(videoTrack).addSink(view)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
            )
            DisposableEffect(Unit) {
                onDispose {
                    eglBase.release()
                    try {
                        videoCapture?.stopCapture()
                    } catch (e: InterruptedException) {
                        Timber.w(e)
                    }
                    videoCapture?.dispose()
                    surfaceTextureHelper?.dispose()
                    videoSource?.dispose()
                    factory.dispose()
                }
            }
        }
        false -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text("カメラを見つけることができませんでした", color = Color.White)
            }
        }
    }
}

// アウトカメラを優先して探す
private fun createCameraCapturer(context: Context): VideoCapturer? {
    val enumerator = Camera2Enumerator(context)
    val deviceNames = enumerator.deviceNames

    for (deviceName in deviceNames) {
        if (enumerator.isBackFacing(deviceName)) {
            val videoCapturer = enumerator.createCapturer(deviceName, null)
            if (videoCapturer != null) {
                return videoCapturer
            }
        }
    }

    for (deviceName in deviceNames) {
        if (enumerator.isFrontFacing(deviceName)) {
            // インカメラ用のキャプチャを作成して返す
            val videoCapturer = enumerator.createCapturer(deviceName, null)
            if (videoCapturer != null) {
                return videoCapturer
            }
        }
    }

    // カメラが見つからない場合
    return null
}

