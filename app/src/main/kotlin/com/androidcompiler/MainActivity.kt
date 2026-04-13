package com.androidcompiler

import android.os.Bundle
import android.os.PerformanceHintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompiler.core.ui.theme.AndroidCompilerTheme
import com.androidcompiler.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

            // Initialize PerformanceHintManager for big.LITTLE scheduling
            viewModel.initPerformanceHints(
                getSystemService(PerformanceHintManager::class.java)
            )

            AndroidCompilerTheme(themeMode = themeMode) {
                AppNavHost(needsSetup = viewModel.needsSetup)
            }
        }
    }
}
