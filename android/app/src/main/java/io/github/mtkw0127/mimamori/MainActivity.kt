package io.github.mtkw0127.mimamori

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.mtkw0127.mimamori.ui.MimamoriApp
import io.github.mtkw0127.mimamori.ui.theme.MimamoriTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MimamoriTheme {
                MimamoriApp()
            }
        }
    }
}
