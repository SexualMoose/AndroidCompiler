package com.androidcompiler.feature.monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidcompiler.core.common.model.GpuStatus
import com.androidcompiler.core.ui.theme.LocalSpacing

@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel = hiltViewModel()
) {
    val cpuUsage by viewModel.cpuUsage.collectAsStateWithLifecycle()
    val ramUsage by viewModel.ramUsage.collectAsStateWithLifecycle()
    val gpuStatus by viewModel.gpuStatus.collectAsStateWithLifecycle()
    val networkThroughput by viewModel.networkThroughput.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenPadding)
    ) {
        Spacer(Modifier.height(spacing.large))

        Text(
            text = "Hardware Monitor",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(spacing.medium))

        // CPU & RAM row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium)
        ) {
            // CPU Card
            MetricCard(
                title = "CPU",
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${cpuUsage.overallPercent.toInt()}%",
                    style = MaterialTheme.typography.displayLarge,
                    color = cpuColor(cpuUsage.overallPercent)
                )
                Spacer(Modifier.height(spacing.small))
                // Per-core mini bars
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    cpuUsage.perCorePercent.forEach { corePercent ->
                        CoreBar(
                            percent = corePercent,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        )
                    }
                }
            }

            // RAM Card
            MetricCard(
                title = "RAM",
                modifier = Modifier.weight(1f)
            ) {
                CircularProgress(
                    progress = ramUsage.systemUsagePercent,
                    modifier = Modifier.size(100.dp)
                )
                Spacer(Modifier.height(spacing.small))
                Text(
                    text = "${formatBytes(ramUsage.usedSystemBytes)} / ${formatBytes(ramUsage.totalSystemBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // GPU & NPU row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium)
        ) {
            // GPU Card
            MetricCard(
                title = "GPU",
                modifier = Modifier.weight(1f)
            ) {
                val statusColor = when (gpuStatus) {
                    GpuStatus.ACTIVE -> MaterialTheme.colorScheme.tertiary
                    GpuStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = gpuStatus.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = statusColor
                )
                Text(
                    text = "Vulkan Compute",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // NPU Card
            MetricCard(
                title = "NPU",
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "N/A",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "ML accelerator only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(Modifier.height(spacing.medium))

        // Network Card (full width)
        MetricCard(
            title = "Network",
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatSpeed(networkThroughput.downloadBytesPerSec),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Download",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatSpeed(networkThroughput.uploadBytesPerSec),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Upload",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (networkThroughput.activeInterfaces.isNotEmpty()) {
                Spacer(Modifier.height(spacing.small))
                networkThroughput.activeInterfaces.forEach { iface ->
                    Text(
                        text = "${iface.name} (${iface.type}): ${formatSpeed(iface.downloadBytesPerSec)} down",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.large))
    }
}

@Composable
private fun MetricCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(LocalSpacing.current.medium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(LocalSpacing.current.small))
            content()
        }
    }
}

@Composable
private fun CoreBar(percent: Float, modifier: Modifier = Modifier) {
    val color = cpuColor(percent)
    Canvas(modifier = modifier) {
        val barHeight = size.height * (percent / 100f)
        drawRect(
            color = color.copy(alpha = 0.2f),
            size = size
        )
        drawRect(
            color = color,
            topLeft = Offset(0f, size.height - barHeight),
            size = Size(size.width, barHeight)
        )
    }
}

@Composable
private fun CircularProgress(progress: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        val strokeWidth = 12.dp.toPx()
        val radius = (minOf(size.width, size.height) - strokeWidth) / 2
        val topLeft = Offset(
            (size.width - radius * 2) / 2,
            (size.height - radius * 2) / 2
        )
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun cpuColor(percent: Float): Color = when {
    percent < 50f -> MaterialTheme.colorScheme.tertiary
    percent < 80f -> Color(0xFFFFB74D)
    else -> MaterialTheme.colorScheme.error
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}
