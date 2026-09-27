package io.github.mtkw0127.mimamori.ui.debug

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualP2PScreen(
    role: ManualP2PRole,
    onBack: () -> Unit,
    viewModel: ManualP2PViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (role == ManualP2PRole.Offerer) "手動 P2P（Offer 側）" else "手動 P2P（Answer 側）") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("接続状態: ${uiState.connectionState}", style = MaterialTheme.typography.titleMedium)
            StepsCard(role)

            if (role == ManualP2PRole.Offerer) {
                Button(
                    onClick = viewModel::onCreateOffer,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Offer を作成") }
            }

            SdpSection(
                title = if (role == ManualP2PRole.Offerer) "自分の Offer（相手に渡す）" else "自分の Answer（相手に渡す）",
                sdp = uiState.localSdp,
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("sdp", uiState.localSdp)))
                    }
                },
            )

            OutlinedTextField(
                value = uiState.remoteSdpInput,
                onValueChange = viewModel::onRemoteSdpInputChange,
                label = { Text(if (role == ManualP2PRole.Offerer) "相手の Answer" else "相手の Offer") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()
                        if (text != null) viewModel.onRemoteSdpInputChange(text)
                    }
                }) { Text("貼り付け") }
                Button(
                    onClick = viewModel::onApplyRemoteSdp,
                    enabled = !uiState.isBusy && uiState.remoteSdpInput.isNotBlank(),
                ) {
                    Text(if (role == ManualP2PRole.Offerer) "Answer を適用" else "Offer を適用して Answer を作成")
                }
            }

            // TODO(M1 / M2: あなたが実装): 映像の表示
            //  Offerer はローカルプレビュー、Answerer は onTrack で受け取った相手の映像を SurfaceViewRenderer で表示する。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text("映像（M1 / M2 で実装）", color = Color.White)
            }
        }
    }
}

@Composable
private fun StepsCard(role: ManualP2PRole) {
    val steps = when (role) {
        ManualP2PRole.Offerer -> listOf(
            "「Offer を作成」を押す",
            "自分の Offer をコピーして相手の端末に渡す",
            "相手が作った Answer を貼り付けて「Answer を適用」",
        )
        ManualP2PRole.Answerer -> listOf(
            "相手の Offer を貼り付けて「Offer を適用して Answer を作成」",
            "自分の Answer をコピーして相手の端末に渡す",
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("手順", style = MaterialTheme.typography.titleSmall)
            steps.forEachIndexed { index, step -> Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
            Text(
                "端末間の受け渡しは、PC 経由のチャットやメモアプリなどを使う。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SdpSection(title: String, sdp: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCopy, enabled = sdp.isNotEmpty()) { Text("コピー") }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    text = sdp.ifEmpty { "（まだありません）" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
