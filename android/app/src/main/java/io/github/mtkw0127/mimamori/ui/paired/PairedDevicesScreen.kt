package io.github.mtkw0127.mimamori.ui.paired

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

/** 削除の確認ダイアログに出す内容。どの一覧の何を消すかをまとめて持つ */
private data class RemoveTarget(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairedDevicesScreen(
    onBack: () -> Unit,
    viewModel: PairedDevicesViewModel = hiltViewModel(),
) {
    val viewers by viewModel.viewers.collectAsStateWithLifecycle()
    val recorders by viewModel.recorders.collectAsStateWithLifecycle()
    var removeTarget by remember { mutableStateOf<RemoveTarget?>(null) }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(target.title) },
            text = { Text(target.message) },
            confirmButton = {
                TextButton(onClick = {
                    target.onConfirm()
                    removeTarget = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("キャンセル") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ペアリング済みの端末") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("Viewer（この端末の映像を見られる端末）") }
            if (viewers.isEmpty()) {
                item { EmptyText("ペアリング済みの Viewer はありません") }
            }
            items(viewers, key = { it.viewerId }) { viewer ->
                DeviceCard(
                    name = viewer.displayName,
                    pairedAtMillis = viewer.pairedAtMillis,
                    onRemove = {
                        removeTarget = RemoveTarget(
                            title = "Viewer を削除",
                            message = "この Viewer は映像を見られなくなります。もう一度見るには、再度ペアリングが必要です。",
                            onConfirm = { viewModel.removeViewer(viewer.viewerId) },
                        )
                    },
                )
            }

            item { SectionHeader("Recorder（この端末が見られる端末）") }
            if (recorders.isEmpty()) {
                item { EmptyText("ペアリング済みの Recorder はありません") }
            }
            items(recorders, key = { it.recorderId }) { recorder ->
                DeviceCard(
                    name = recorder.displayName,
                    pairedAtMillis = recorder.pairedAtMillis,
                    onRemove = {
                        removeTarget = RemoveTarget(
                            title = "Recorder を削除",
                            message = "この端末の登録を消します。もう一度見るには、再度ペアリングが必要です。",
                            onConfirm = { viewModel.removeRecorder(recorder.recorderId) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DeviceCard(
    name: String,
    pairedAtMillis: Long,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "ペアリング日: " + DateFormat.getDateTimeInstance().format(Date(pairedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRemove) { Text("削除") }
        }
    }
}
