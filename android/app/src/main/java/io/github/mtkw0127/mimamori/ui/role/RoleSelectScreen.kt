package io.github.mtkw0127.mimamori.ui.role

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.mtkw0127.mimamori.ui.theme.MimamoriTheme

@Composable
fun RoleSelectScreen(
    onRecorderSelected: () -> Unit,
    onViewerSelected: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Mimamori", style = MaterialTheme.typography.headlineLarge)
            Text("この端末の役割を選んでください", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onRecorderSelected, modifier = Modifier.fillMaxWidth()) {
                Text("Recorder（撮影する）")
            }
            OutlinedButton(onClick = onViewerSelected, modifier = Modifier.fillMaxWidth()) {
                Text("Viewer（見る）")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RoleSelectScreenPreview() {
    MimamoriTheme {
        RoleSelectScreen(onRecorderSelected = {}, onViewerSelected = {})
    }
}
