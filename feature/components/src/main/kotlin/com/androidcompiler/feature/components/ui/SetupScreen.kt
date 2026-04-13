package com.androidcompiler.feature.components.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompiler.core.common.model.ComponentStatus
import com.androidcompiler.core.ui.theme.LocalSpacing

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: ComponentsViewModel = hiltViewModel()
) {
    val components by viewModel.components.collectAsStateWithLifecycle()
    val isDownloadingAll by viewModel.isDownloadingAll.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    val allInstalled = components.all { it.second is ComponentStatus.Installed }
    val totalComponents = components.size
    val installedCount = components.count { it.second is ComponentStatus.Installed }
    val totalSize = components.sumOf { it.first.sizeBytes }

    if (allInstalled) {
        onSetupComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(spacing.large))

        Text(
            text = "Welcome to AndroidCompiler",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(spacing.small))

        Text(
            text = "Compile Android projects into APKs directly on your device",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(spacing.extraLarge))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(spacing.large)
                    .animateContentSize()
            ) {
                Text(
                    text = "Toolchain Setup",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(spacing.small))
                Text(
                    text = "The compiler requires ~${totalSize / 1_048_576} MB of components. " +
                            "These will be downloaded once and cached on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(spacing.medium))

                // Component status list
                components.forEach { (component, status) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (status) {
                            is ComponentStatus.Installed -> Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            is ComponentStatus.Downloading -> CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            else -> Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(spacing.small))
                        Text(
                            text = component.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${component.sizeBytes / 1_048_576} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (status is ComponentStatus.Downloading) {
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 28.dp)
                        )
                    }
                    if (status is ComponentStatus.Error) {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 28.dp)
                        )
                    }
                }

                Spacer(Modifier.height(spacing.medium))

                if (isDownloadingAll) {
                    LinearProgressIndicator(
                        progress = { installedCount.toFloat() / totalComponents },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(spacing.small))
                    Text(
                        text = "$installedCount / $totalComponents components ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!allInstalled) {
                    Button(
                        onClick = { viewModel.downloadAll() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(spacing.small))
                        Text("Download All Components")
                    }
                }
            }
        }
    }
}
