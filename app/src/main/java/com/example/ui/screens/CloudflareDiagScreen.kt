package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.psiphon.AdaptiveRouteDecision
import com.example.psiphon.CloudflareDiagnosticState
import com.example.psiphon.CloudflareNodeStats
import com.example.ui.components.TelemetryStatCard
import com.example.ui.theme.AmberLatency
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.PsiphonCrimson
import com.example.ui.theme.VioletStudio
import kotlin.math.roundToInt

private val SeriesPalette = listOf(
    ElectricCyan,
    EmeraldShield,
    AmberLatency,
    VioletStudio,
    PsiphonCrimson
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudflareDiagScreen(
    state: CloudflareDiagnosticState,
    adaptiveDecision: AdaptiveRouteDecision,
    autoRoutingEnabled: Boolean,
    onToggleNode: (String) -> Unit,
    onAddCustomEdge: (String) -> Boolean,
    onSetInterval: (Long) -> Unit,
    onStartLive: () -> Unit,
    onStopLive: () -> Unit,
    onSingleSweep: () -> Unit,
    onClearHistory: () -> Unit,
    onEvaluateAdaptiveRoute: () -> Unit,
    modifier: Modifier = Modifier
) {
    var customEdgeInput by remember { mutableStateOf("") }
    var inspectedSampleOffset by remember { mutableStateOf<Int?>(null) }

    val selectedNodes = remember(state.nodes, state.selectedNodeIds) {
        state.nodes.filter { it.id in state.selectedNodeIds }
    }

    val activeSeries = remember(selectedNodes, state.nodeStats) {
        selectedNodes.mapIndexed { idx, node ->
            val stats = state.nodeStats[node.id] ?: CloudflareNodeStats(node = node)
            Triple(node, stats, SeriesPalette[idx % SeriesPalette.size])
        }
    }

    val aggregateRtts = remember(activeSeries) {
        activeSeries.mapNotNull { it.second.avgRttMs }
    }
    val overallAvgRtt = remember(aggregateRtts) {
        if (aggregateRtts.isEmpty()) null else aggregateRtts.sum() / aggregateRtts.size
    }
    val fastestNode = remember(activeSeries) {
        activeSeries.filter { it.second.latestRttMs != null }
            .minByOrNull { it.second.latestRttMs ?: Long.MAX_VALUE }
    }

    val pillChipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = ElectricCyan,
        selectedLabelColor = Color.White
    )
    val pillChipBorder = FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = false,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        selectedBorderColor = ElectricCyan
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Top Metrics
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ShowChart,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.cf_diag_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = stringResource(R.string.cf_diag_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryStatCard(
                            label = stringResource(R.string.cf_stat_sweeps),
                            value = "${state.totalSweeps}",
                            accent = VioletStudio,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatCard(
                            label = stringResource(R.string.cf_stat_avg_rtt),
                            value = overallAvgRtt?.let { "$it ms" } ?: "--",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatCard(
                            label = stringResource(R.string.cf_stat_fastest),
                            value = fastestNode?.second?.latestRttMs?.let { "$it ms" } ?: "--",
                            accent = EmeraldShield,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Live Control Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = if (state.isRunning) onStopLive else onStartLive,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isRunning) CoralError else ElectricCyan,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cf_diag_start_stop_button")
                        ) {
                            Icon(
                                imageVector = if (state.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    if (state.isRunning) R.string.cf_btn_stop_live else R.string.cf_btn_start_live
                                ),
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = onSingleSweep,
                            enabled = !state.isRunning,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("cf_diag_ping_once_button")
                        ) {
                            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.cf_btn_ping_once))
                        }

                        OutlinedButton(
                            onClick = {
                                inspectedSampleOffset = null
                                onClearHistory()
                            },
                            enabled = state.totalSweeps > 0 && !state.isRunning,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("cf_diag_clear_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = stringResource(R.string.cd_clear_cf_history),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Ping Interval Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cf_interval_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(500L to "0.5s", 1000L to "1s", 2000L to "2s").forEach { (ms, label) ->
                            FilterChip(
                                selected = state.intervalMs == ms,
                                onClick = { onSetInterval(ms) },
                                label = { Text(label) },
                                shape = CircleShape,
                                colors = pillChipColors,
                                border = pillChipBorder,
                                modifier = Modifier.testTag("cf_interval_${ms}")
                            )
                        }
                    }
                }
            }
        }

        // Live Adaptive Routing Service Banner
        item {
            val activeRoute = adaptiveDecision.selectedRoute
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, EmeraldShield.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AltRoute,
                        contentDescription = null,
                        tint = EmeraldShield,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.adaptive_routing_title) +
                                if (autoRoutingEnabled) " • AUTO" else " • MANUAL",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldShield
                        )
                        Text(
                            text = if (activeRoute != null) {
                                "\u2066${activeRoute.ip}:${activeRoute.targetPort}\u2069 • Anon ${activeRoute.anonymityConfidenceScore}% • Score ${adaptiveDecision.compositeScore}"
                            } else {
                                stringResource(R.string.adaptive_reason_none)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onEvaluateAdaptiveRoute,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.adaptive_btn_route_now))
                    }
                }
            }
        }

        // Visual Real-Time RTT Latency Graph Card
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.testTag("cf_latency_graph_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.cf_graph_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        if (overallAvgRtt != null) {
                            Text(
                                text = stringResource(R.string.cf_graph_avg_ref, overallAvgRtt),
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberLatency
                            )
                        }
                    }

                    // Legend pills
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        activeSeries.forEach { (node, stats, color) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                                Text(
                                    text = "\u2066${node.ip}\u2069" + (stats.latestRttMs?.let { " ($it ms)" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    RttLatencyChart(
                        series = activeSeries,
                        avgRefMs = overallAvgRtt,
                        inspectedIndex = inspectedSampleOffset,
                        onInspectIndex = { inspectedSampleOffset = it },
                        emptyPrompt = stringResource(R.string.cf_graph_empty)
                    )

                    // Tooltip details when user taps/scrubs a sample point on the graph
                    val maxLen = activeSeries.maxOfOrNull { it.second.samples.size } ?: 0
                    val activeIdx = inspectedSampleOffset?.coerceIn(0, (maxLen - 1).coerceAtLeast(0))
                    if (activeIdx != null && maxLen > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.cf_tooltip_sample_header, activeIdx + 1),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ElectricCyan
                                )
                                activeSeries.forEach { (node, stats, color) ->
                                    val sample = stats.samples.getOrNull(activeIdx)
                                    if (sample != null) {
                                        val rttText = sample.totalRttMs?.let { "${it} ms (TCP ${sample.tcpRttMs ?: it} ms)" }
                                            ?: (sample.error ?: stringResource(R.string.ping_failed))
                                        val coloSuffix = if (sample.coloCode != "--") " • ${sample.coloCode}" else ""
                                        Text(
                                            text = "\u2066${node.ip}\u2069: $rttText$coloSuffix",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Cloudflare Edge Nodes Selector + Custom Edge Input
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cf_nodes_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.nodes.forEach { node ->
                            val selected = node.id in state.selectedNodeIds
                            FilterChip(
                                selected = selected,
                                onClick = { onToggleNode(node.id) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                label = {
                                    Text(
                                        text = "\u2066${node.ip}\u2069",
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                shape = CircleShape,
                                colors = pillChipColors,
                                border = pillChipBorder,
                                modifier = Modifier.testTag("cf_node_chip_${node.id}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = customEdgeInput,
                                    onValueChange = { customEdgeInput = it },
                                    label = { Text(stringResource(R.string.cf_custom_edge_label)) },
                                    placeholder = { Text("104.18.12.34") },
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cf_custom_edge_input")
                                )
                            }
                        }
                        Button(
                            onClick = {
                                if (onAddCustomEdge(customEdgeInput)) {
                                    customEdgeInput = ""
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.White),
                            modifier = Modifier.testTag("cf_add_edge_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.btn_add_more_ips))
                        }
                    }
                }
            }
        }

        // Per-Node Telemetry Breakdown Cards
        items(activeSeries, key = { it.first.id }) { (node, stats, color) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                            Column {
                                Text(
                                    text = "\u2066${node.ip}:${node.port}\u2069",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "${node.regionHint} • PoP: ${stats.coloCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Text(
                            text = stats.latestRttMs?.let { "$it ms" } ?: "--",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = color
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.cf_node_metrics_line,
                            stats.minRttMs?.toString() ?: "--",
                            stats.avgRttMs?.toString() ?: "--",
                            stats.maxRttMs?.toString() ?: "--",
                            stats.jitterMs?.toString() ?: "--",
                            stats.packetLossPercent
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RttLatencyChart(
    series: List<Triple<com.example.psiphon.CloudflareEdgeNode, CloudflareNodeStats, Color>>,
    avgRefMs: Long?,
    inspectedIndex: Int?,
    onInspectIndex: (Int?) -> Unit,
    emptyPrompt: String
) {
    val textMeasurer = rememberTextMeasurer()
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val allPoints = series.flatMap { (_, stats, _) ->
        stats.samples.mapNotNull { it.totalRttMs ?: it.tcpRttMs }
    }
    val maxSampleCount = series.maxOfOrNull { it.second.samples.size } ?: 0

    if (maxSampleCount == 0 || allPoints.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyPrompt,
                style = MaterialTheme.typography.bodySmall,
                color = axisLabelColor
            )
        }
        return
    }

    val rawMax = (allPoints.maxOrNull() ?: 100L).coerceAtLeast(40L)
    val yMax = (((rawMax * 1.2) / 20.0).roundToInt() * 20).coerceAtLeast(60).toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(top = 10.dp, bottom = 10.dp, start = 8.dp, end = 12.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(maxSampleCount) {
                    detectTapGestures { offset ->
                        if (maxSampleCount > 0) {
                            val leftPad = 42.dp.toPx()
                            val plotW = (size.width - leftPad).coerceAtLeast(1f)
                            val relX = ((offset.x - leftPad) / plotW).coerceIn(0f, 1f)
                            val idx = if (maxSampleCount <= 1) 0 else (relX * (maxSampleCount - 1)).roundToInt()
                            onInspectIndex(idx)
                        }
                    }
                }
                .pointerInput(maxSampleCount) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        if (maxSampleCount > 0) {
                            val leftPad = 42.dp.toPx()
                            val plotW = (size.width - leftPad).coerceAtLeast(1f)
                            val relX = ((change.position.x - leftPad) / plotW).coerceIn(0f, 1f)
                            val idx = if (maxSampleCount <= 1) 0 else (relX * (maxSampleCount - 1)).roundToInt()
                            onInspectIndex(idx)
                        }
                    }
                }
        ) {
            val leftPad = 42.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val plotW = (size.width - leftPad).coerceAtLeast(10f)
            val plotH = (size.height - topPad - bottomPad).coerceAtLeast(10f)

            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            val labelStyle = TextStyle(color = axisLabelColor, fontSize = 10.sp)

            // 4 horizontal Y-axis gridlines
            for (step in 0..3) {
                val frac = step / 3f
                val y = topPad + plotH * (1f - frac)
                drawLine(
                    color = gridColor.copy(alpha = 0.55f),
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + plotW, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (step == 0) null else dashEffect
                )
                val valMs = (yMax * frac).roundToInt()
                val measured = textMeasurer.measure("${valMs}", style = labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(2.dp.toPx(), (y - measured.size.height / 2f).coerceAtLeast(0f))
                )
            }

            // Average RTT threshold dashed reference line
            if (avgRefMs != null && avgRefMs > 0L) {
                val refY = topPad + plotH * (1f - (avgRefMs.toFloat() / yMax).coerceIn(0f, 1f))
                drawLine(
                    color = AmberLatency.copy(alpha = 0.7f),
                    start = Offset(leftPad, refY),
                    end = Offset(leftPad + plotW, refY),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = dashEffect
                )
            }

            // Draw each edge node series
            series.forEachIndexed { sIdx, (_, stats, seriesColor) ->
                val samples = stats.samples
                if (samples.isEmpty()) return@forEachIndexed

                val points = samples.mapIndexedNotNull { idx, sample ->
                    val rtt = sample.totalRttMs ?: sample.tcpRttMs ?: return@mapIndexedNotNull null
                    val x = if (maxSampleCount <= 1) {
                        leftPad + plotW / 2f
                    } else {
                        leftPad + plotW * (idx.toFloat() / (maxSampleCount - 1).toFloat())
                    }
                    val y = topPad + plotH * (1f - (rtt.toFloat() / yMax).coerceIn(0f, 1f))
                    Offset(x, y)
                }

                if (points.size >= 2) {
                    val linePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            val prev = points[i - 1]
                            val curr = points[i]
                            val midX = (prev.x + curr.x) / 2f
                            cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                        }
                    }

                    // Soft area fill under the primary series (or first 2 series)
                    if (sIdx == 0) {
                        val areaPath = Path().apply {
                            addPath(linePath)
                            lineTo(points.last().x, topPad + plotH)
                            lineTo(points.first().x, topPad + plotH)
                            close()
                        }
                        drawPath(
                            path = areaPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    seriesColor.copy(alpha = 0.28f),
                                    seriesColor.copy(alpha = 0.02f)
                                ),
                                startY = topPad,
                                endY = topPad + plotH
                            )
                        )
                    }

                    drawPath(
                        path = linePath,
                        color = seriesColor,
                        style = Stroke(
                            width = 2.4.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Draw point markers
                points.forEach { pt ->
                    drawCircle(
                        color = seriesColor,
                        radius = 3.2.dp.toPx(),
                        center = pt
                    )
                }
            }

            // Inspected sample vertical crosshair
            if (inspectedIndex != null && maxSampleCount > 0) {
                val clamped = inspectedIndex.coerceIn(0, (maxSampleCount - 1).coerceAtLeast(0))
                val cursorX = if (maxSampleCount <= 1) {
                    leftPad + plotW / 2f
                } else {
                    leftPad + plotW * (clamped.toFloat() / (maxSampleCount - 1).toFloat())
                }
                drawLine(
                    color = ElectricCyan.copy(alpha = 0.85f),
                    start = Offset(cursorX, topPad),
                    end = Offset(cursorX, topPad + plotH),
                    strokeWidth = 1.4.dp.toPx(),
                    pathEffect = dashEffect
                )
            }
        }
    }
}
