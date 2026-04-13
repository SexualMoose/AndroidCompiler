package com.androidcompiler.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompiler.core.common.model.CoreType
import com.androidcompiler.core.common.model.NetworkType
import com.androidcompiler.core.common.model.ThemeMode
import com.androidcompiler.core.ui.theme.LocalSpacing

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    val inputFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.setDefaultInputFolder(it.toString()) } }

    val outputFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.setDefaultOutputFolder(it.toString()) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenPadding)
    ) {
        Spacer(Modifier.height(spacing.large))

        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(spacing.medium))

        // Appearance Section
        SettingsSection(title = "Appearance") {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(spacing.small))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size
                        )
                    ) {
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // Performance Section
        SettingsSection(title = "Performance") {
            Text("Core Types", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(spacing.small))
            Row {
                CoreType.entries.forEach { type ->
                    FilterChip(
                        selected = type in settings.coreTypes,
                        onClick = {
                            val newSet = settings.coreTypes.toMutableSet()
                            if (type in newSet) newSet.remove(type) else newSet.add(type)
                            if (newSet.isNotEmpty()) viewModel.setCoreTypes(newSet)
                        },
                        label = { Text(type.displayName) },
                        modifier = Modifier.padding(end = spacing.small)
                    )
                }
            }

            Spacer(Modifier.height(spacing.medium))

            Text("Network Types", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(spacing.small))
            Row {
                NetworkType.entries.forEach { type ->
                    FilterChip(
                        selected = type in settings.networkTypes,
                        onClick = {
                            val newSet = settings.networkTypes.toMutableSet()
                            if (type in newSet) newSet.remove(type) else newSet.add(type)
                            if (newSet.isNotEmpty()) viewModel.setNetworkTypes(newSet)
                        },
                        label = { Text(type.displayName) },
                        modifier = Modifier.padding(end = spacing.small)
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // Storage Section
        SettingsSection(title = "Storage") {
            OutlinedButton(
                onClick = { inputFolderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(spacing.small))
                Text(settings.defaultInputFolder?.let { "Input: $it" } ?: "Set Default Input Folder")
            }

            Spacer(Modifier.height(spacing.small))

            OutlinedButton(
                onClick = { outputFolderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(spacing.small))
                Text(settings.defaultOutputFolder?.let { "Output: $it" } ?: "Set Default Output Folder")
            }

            Spacer(Modifier.height(spacing.medium))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Incremental File Names", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Add version numbers to output files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.incrementalFileNames,
                    onCheckedChange = { viewModel.setIncrementalFileNames(it) }
                )
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // Updates Section
        SettingsSection(title = "Updates") {
            OutlinedButton(
                onClick = { viewModel.checkForUpdates() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Update, contentDescription = null)
                Spacer(Modifier.width(spacing.small))
                Text("Check for Component Updates")
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // About Section
        SettingsSection(title = "About") {
            Text(
                text = "AndroidCompiler v1.0.0",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "On-device Android project compilation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(spacing.extraLarge))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(LocalSpacing.current.large)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(LocalSpacing.current.medium))
            content()
        }
    }
}
