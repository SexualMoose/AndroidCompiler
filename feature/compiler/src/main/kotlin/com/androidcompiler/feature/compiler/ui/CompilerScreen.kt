package com.androidcompiler.feature.compiler.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompiler.core.common.model.CompilationState
import com.androidcompiler.core.common.model.ErrorSeverity
import com.androidcompiler.core.ui.theme.LocalSpacing

@Composable
fun CompilerScreen(
    viewModel: CompilerViewModel = hiltViewModel()
) {
    val state by viewModel.compilationState.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val clipboardManager = LocalClipboardManager.current

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.loadProject(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenPadding)
    ) {
        Spacer(Modifier.height(spacing.large))

        // Project Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(spacing.large)) {
                Text(
                    text = when (val s = state) {
                        is CompilationState.Idle -> "No project loaded"
                        is CompilationState.ProjectLoaded -> s.projectName
                        is CompilationState.Compiling -> "Compiling: ${s.step.displayName}"
                        is CompilationState.Complete -> "Build successful"
                        is CompilationState.Failed -> "Build failed"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (state is CompilationState.Compiling) {
                    Spacer(Modifier.height(spacing.medium))
                    LinearProgressIndicator(
                        progress = { (state as CompilationState.Compiling).progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(spacing.small))
                    Text(
                        text = (state as CompilationState.Compiling).step.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            OutlinedButton(
                onClick = { zipPicker.launch(arrayOf("application/zip")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(spacing.small))
                Text("Select ZIP")
            }

            Button(
                onClick = { viewModel.compile() },
                enabled = state is CompilationState.ProjectLoaded || state is CompilationState.Failed,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(spacing.small))
                Text("Compile")
            }
        }

        if (state is CompilationState.Complete) {
            Spacer(Modifier.height(spacing.small))
            val apkPath = (state as CompilationState.Complete).apkPath
            val context = androidx.compose.ui.platform.LocalContext.current
            Button(
                onClick = {
                    val apkFile = File(apkPath)
                    val apkUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        apkFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.InstallMobile, contentDescription = null)
                Spacer(Modifier.width(spacing.small))
                Text("Install APK")
            }
        }

        if (state is CompilationState.Failed) {
            Spacer(Modifier.height(spacing.small))
            FilledTonalButton(
                onClick = {
                    val prompt = viewModel.generateClaudePrompt()
                    clipboardManager.setText(AnnotatedString(prompt))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null)
                Spacer(Modifier.width(spacing.small))
                Text("Generate Claude Fix Prompt")
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // Compilation Log
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(spacing.medium)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Compilation Log",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row {
                        IconButton(onClick = {
                            val logText = logEntries.joinToString("\n") { it.message }
                            clipboardManager.setText(AnnotatedString(logText))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy log")
                        }
                        IconButton(onClick = { viewModel.clearLog() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear log")
                        }
                    }
                }

                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logEntries) { entry ->
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = when (entry.severity) {
                                ErrorSeverity.ERROR -> MaterialTheme.colorScheme.error
                                ErrorSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                ErrorSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(spacing.small))
    }
}
