package com.androidcompiler.feature.compiler.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val detectedVersions by viewModel.detectedVersions.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val clipboardManager = LocalClipboardManager.current
    var advancedExpanded by remember { mutableStateOf(false) }

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

        // Advanced: version overrides — only show once a project is loaded
        if (state !is CompilationState.Idle) {
            Spacer(Modifier.height(spacing.small))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(spacing.medium)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { advancedExpanded = !advancedExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                            Spacer(Modifier.width(spacing.small))
                            Text("Build versions", style = MaterialTheme.typography.titleSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Concise summary when collapsed
                            if (!advancedExpanded) {
                                val g = detectedVersions.effectiveGradleVersion ?: "default"
                                val s = detectedVersions.effectiveCompileSdk?.toString() ?: "default"
                                Text(
                                    "Gradle $g · API $s",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(spacing.small))
                            }
                            Icon(
                                if (advancedExpanded) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = if (advancedExpanded) "Collapse" else "Expand"
                            )
                        }
                    }

                    if (advancedExpanded) {
                        Spacer(Modifier.height(spacing.medium))
                        VersionOverrideRow(
                            label = "Gradle",
                            detected = detectedVersions.detectedGradleVersion,
                            override = detectedVersions.gradleVersionOverride,
                            options = viewModel.supportedGradleVersions,
                            formatter = { it },
                            onOverrideChange = viewModel::setGradleVersionOverride
                        )
                        Spacer(Modifier.height(spacing.small))
                        VersionOverrideRow(
                            label = "Compile SDK",
                            detected = detectedVersions.detectedCompileSdk?.toString(),
                            override = detectedVersions.compileSdkOverride?.toString(),
                            options = viewModel.supportedApiLevels.map { it.toString() },
                            formatter = { "API $it" },
                            onOverrideChange = { v -> viewModel.setCompileSdkOverride(v?.toIntOrNull()) }
                        )
                        detectedVersions.detectedAgpVersion?.let { agp ->
                            Spacer(Modifier.height(spacing.small))
                            Text(
                                "AGP (read-only): $agp",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(spacing.small))
                        Text(
                            "JDK: 17 (bundled)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

/**
 * One row in the Build versions card: label + chip showing effective value +
 * dropdown with all options. When an override is selected, the chip is
 * filled/primary-colored so it's clear the user has overridden auto-detection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionOverrideRow(
    label: String,
    detected: String?,
    override: String?,
    options: List<String>,
    formatter: (String) -> String,
    onOverrideChange: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val effective = override ?: detected
    val displayValue = effective?.let { formatter(it) } ?: "Use default"
    val isOverridden = override != null

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    isOverridden -> "Overridden"
                    detected != null -> "Detected from project"
                    else -> "Not specified — using default"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isOverridden) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.width(200.dp)
        ) {
            OutlinedTextField(
                value = displayValue,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (detected != null) "Detected (${formatter(detected)})"
                            else "Registry default"
                        )
                    },
                    onClick = {
                        onOverrideChange(null)
                        expanded = false
                    }
                )
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(formatter(opt)) },
                        onClick = {
                            onOverrideChange(opt)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
