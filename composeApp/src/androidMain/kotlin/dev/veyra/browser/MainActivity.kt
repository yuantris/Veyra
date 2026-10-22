package dev.veyra.browser

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAppContext.initialize(applicationContext)
        setContent { App() }
    }
}
