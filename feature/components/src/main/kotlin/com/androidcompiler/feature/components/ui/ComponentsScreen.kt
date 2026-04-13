package com.androidcompiler.feature.components.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompiler.core.common.model.ComponentStatus
import com.androidcompiler.core.ui.theme.LocalSpacing

@Composable
fun ComponentsScreen(
    viewModel: ComponentsViewModel = hiltViewModel()
) {
    val components by viewModel.components.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenPadding)
    ) {
        Spacer(Modifier.height(spacing.large))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Toolchain Components",
                style = MaterialTheme.typography.headlineMedium
            )
            FilledTonalButton(onClick = { viewModel.refreshAll() }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(spacing.small))
                Text("Refresh")
            }
        }

        Spacer(Modifier.height(spacing.medium))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            items(components) { (component, status) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = component.displayName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "v${component.version} - ${formatSize(component.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            when (status) {
                                is ComponentStatus.Installed -> {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Installed",
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                is ComponentStatus.NotInstalled -> {
                                    Button(onClick = { viewModel.download(component.id) }) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Download")
                                    }
                                }
                                is ComponentStatus.UpdateAvailable -> {
                                    Button(onClick = { viewModel.download(component.id) }) {
                                        Text("Update")
                                    }
                                }
                                is ComponentStatus.Downloading -> {}
                                is ComponentStatus.Error -> {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = "Error",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        if (status is ComponentStatus.Downloading) {
                            Spacer(Modifier.height(spacing.small))
                            LinearProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (status is ComponentStatus.Error) {
                            Spacer(Modifier.height(spacing.small))
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
