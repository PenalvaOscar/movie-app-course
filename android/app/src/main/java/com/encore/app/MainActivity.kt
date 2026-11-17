package com.encore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.encore.app.ui.navigation.EncoreApp
import com.encore.app.ui.theme.EncoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Default edge-to-edge picks light/dark system-bar icons from the system night mode,
        // which is also what EncoreTheme follows.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EncoreTheme {
                EncoreApp()
            }
        }
    }
}
