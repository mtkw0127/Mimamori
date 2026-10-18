package io.github.mtkw0127.mimamori.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mtkw0127.mimamori.signaling.DiscoveredRecorder
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
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Viewer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
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
                        onSelect = viewModel::connect,
                    )
                }

                ViewerConnectionStatus.CONNECTING,
                ViewerConnectionStatus.NEGOTIATING,
                -> {
                    Text("接続しています…", style = MaterialTheme.typography.bodyLarge)
                }

                ViewerConnectionStatus.CONNECTED -> {
                    RemoteVideo(
                        videoTrack = remoteVideoTrack,
                        eglBaseContext = viewModel.eglBase.eglBaseContext,
                    )
                }

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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(recorder) },
            ) {
                Text(
                    text = recorder.name,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun RemoteVideo(
    videoTrack: org.webrtc.VideoTrack?,
    eglBaseContext: org.webrtc.EglBase.Context,
) {
    if (videoTrack == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("相手からの映像を待っています…", color = Color.White)
        }
        return
    }
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        },
        update = { view -> videoTrack.addSink(view) },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f),
    )
}
