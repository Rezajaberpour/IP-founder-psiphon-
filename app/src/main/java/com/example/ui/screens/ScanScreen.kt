package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.CleanIpEntity
import com.example.data.preferences.ScannerSettings
import com.example.psiphon.CdnProvider
import com.example.psiphon.FreeProxyWorldSource
import com.example.psiphon.ProbeTelemetry
import com.example.psiphon.PsiphonMethod
import com.example.psiphon.PsiphonRegions
import com.example.psiphon.ScanProgressState
import com.example.ui.components.IpVersionBadge
import com.example.ui.components.PingBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.TelemetryStatCard
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PsiphonCrimson
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(
    settings: ScannerSettings,
    progress: ScanProgressState,
    liveResults: List<ProbeTelemetry>,
    selectedEntity: CleanIpEntity?,
    onToggleProvider: (CdnProvider) -> Unit,
    onToggleAllCdns: () -> Unit,
    onSelectMethod: (PsiphonMethod) -> Unit,
    onSelectFreeProxyCountry: (String) -> Unit,
    onSelectFreeProxyPort: (String) -> Unit,
    onToggleIpv4: () -> Unit,
    onToggleIpv6: () -> Unit,
    onToggleHttp: () -> Unit,
    onToggleSocks5: () -> Unit,
    onUpdateSettings: ((ScannerSettings) -> ScannerSettings) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onResetToAuto: () -> Unit,
    onSelectIpKey: (String) -> Unit,
    onCopyFeedback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val activeLocale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
    var livePortFilter by remember { mutableStateOf<Int?>(null) }
    val livePorts = remember(liveResults) {
        liveResults.map { it.targetPort }.distinct().sorted()
    }
    val effectiveLivePortFilter = livePortFilter?.takeIf { it in livePorts }
    val displayedLiveResults = remember(liveResults, effectiveLivePortFilter) {
        if (effectiveLivePortFilter == null) liveResults else liveResults.filter { it.targetPort == effectiveLivePortFilter }
    }

    val pillChipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = ElectricCyan,
        selectedLabelColor = Color.White,
        selectedLeadingIconColor = Color.White
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Psiphon Clean IP Hero + Grouped Actions Card (matches screenshot 1)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(
                    1.dp,
                    if (selectedEntity != null) EmeraldShield.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outline
                ),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_shield_mesh_1794563362634),
                        contentDescription = stringResource(R.string.cd_header_backdrop),
                        contentScale = ContentScale.Crop,
                        alpha = 0.18f,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        ObsidianBg.copy(alpha = 0.45f),
                                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f)
                                    )
                                )
                            )
                    )

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        color = if (selectedEntity != null) EmeraldShield else PsiphonCrimson,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (selectedEntity != null) Icons.Filled.CleaningServices else Icons.Filled.Radar,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(
                                            if (selectedEntity != null) R.string.hero_title_active else R.string.hero_title_idle
                                        ),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (selectedEntity != null) EmeraldShield else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val heroBadgeText = if (selectedEntity != null) {
                                    "\u2066${selectedEntity.proxyProtocol} :${selectedEntity.targetPort}\u2069"
                                } else {
                                    buildList {
                                        if (settings.includeHttp) add("HTTP")
                                        if (settings.includeSocks5) add("SOCKS5")
                                    }.joinToString(" • ")
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Text(
                                        text = heroBadgeText,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            if (selectedEntity != null) {
                                val selectedCountryName = if (selectedEntity.countryCode == "--") {
                                    stringResource(R.string.country_unknown)
                                } else {
                                    PsiphonRegions.resolveDisplayName(selectedEntity.countryCode, activeLocale)
                                }
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = selectedEntity.countryFlag,
                                            style = MaterialTheme.typography.headlineMedium
                                        )
                                        Text(
                                            text = "\u2066${selectedEntity.ip}:${selectedEntity.targetPort}\u2069",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                val value = "${selectedEntity.ip}:${selectedEntity.targetPort}"
                                                clipboard.setText(AnnotatedString(value))
                                                onCopyFeedback(context.getString(R.string.msg_copied_value, value))
                                            }
                                        ) {
                                            Icon(
                                                Icons.Filled.ContentCopy,
                                                contentDescription = stringResource(R.string.cd_copy_active_ip)
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$selectedCountryName (${selectedEntity.countryCode}) • ${selectedEntity.provider} • ${selectedEntity.tlsVersion}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        )
                                        PingBadge(pingMs = selectedEntity.httpsPingMs, isClean = selectedEntity.isClean)
                                    }
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.hero_subtitle_idle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (selectedEntity != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldShield,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.hero_method_clean_ip),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = EmeraldShield
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onResetToAuto)
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .testTag("reset_auto_button"),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MyLocation,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.btn_reset_to_auto),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = ElectricCyan
                                )
                            }
                        }
                    }
                }
            }
        }

        // Scanner Configuration Controls (matches pill style in screenshot 1 & 2)
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
                    val activeProviders = settings.selectedProviders.ifEmpty { setOf(settings.provider) }
                    val allRealCdnsSelected = CdnProvider.realScanCdns.all { it in activeProviders }
                    val hasProxySourceSelected = activeProviders.any { it.isProxyListSource }
                    val hasCdnOrCustomSelected = activeProviders.any { !it.isProxyListSource }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.section_cdn_sources),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = allRealCdnsSelected,
                            onClick = onToggleAllCdns,
                            leadingIcon = if (allRealCdnsSelected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = {
                                Text(
                                    text = stringResource(R.string.btn_select_all_cdns),
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            shape = CircleShape,
                            colors = pillChipColors,
                            border = pillChipBorder,
                            modifier = Modifier.testTag("cdn_chip_all_cdns")
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val cdnAndCustom = CdnProvider.entries.filter { !it.isProxyListSource }
                        cdnAndCustom.forEach { provider ->
                            val isSelected = provider in activeProviders
                            FilterChip(
                                selected = isSelected,
                                onClick = { onToggleProvider(provider) },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                label = {
                                    Text(
                                        text = stringResource(provider.labelRes),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                shape = CircleShape,
                                colors = pillChipColors,
                                border = pillChipBorder,
                                modifier = Modifier.testTag("cdn_chip_${provider.name.lowercase()}")
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.section_proxy_sources),
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CdnProvider.proxyListSources.forEach { provider ->
                            val isSelected = provider in activeProviders
                            FilterChip(
                                selected = isSelected,
                                onClick = { onToggleProvider(provider) },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                label = {
                                    Text(
                                        text = stringResource(provider.labelRes),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                shape = CircleShape,
                                colors = pillChipColors,
                                border = pillChipBorder,
                                modifier = Modifier.testTag("cdn_chip_${provider.name.lowercase()}")
                            )
                        }
                    }

                    if (hasProxySourceSelected) {
                        Text(
                            text = stringResource(R.string.freeproxy_country_label),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FreeProxyWorldSource.supportedCountries.forEach { code ->
                                val selected = settings.freeProxyCountry.equals(code, ignoreCase = true)
                                val label = if (code.isBlank()) {
                                    "🌐 " + stringResource(R.string.freeproxy_country_all)
                                } else {
                                    "${PsiphonRegions.countryCodeToFlag(code)} $code"
                                }
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectFreeProxyCountry(code) },
                                    label = { Text(label) },
                                    shape = CircleShape,
                                    colors = pillChipColors,
                                    border = pillChipBorder,
                                    modifier = Modifier.testTag("freeproxy_country_${code.ifBlank { "all" }.lowercase()}")
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.freeproxy_port_label),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FreeProxyWorldSource.supportedPorts.forEach { portPreset ->
                                val selected = settings.freeProxyPortFilter.trim() == portPreset
                                val label = if (portPreset.isBlank()) {
                                    stringResource(R.string.filter_port_all)
                                } else {
                                    ":$portPreset"
                                }
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectFreeProxyPort(portPreset) },
                                    label = { Text(label, fontWeight = FontWeight.SemiBold) },
                                    shape = CircleShape,
                                    colors = pillChipColors,
                                    border = pillChipBorder,
                                    modifier = Modifier.testTag("freeproxy_port_${portPreset.ifBlank { "all" }}")
                                )
                            }
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = settings.freeProxyPortFilter,
                                onValueChange = { v -> onSelectFreeProxyPort(normalizePortInput(v)) },
                                label = { Text(stringResource(R.string.freeproxy_port_custom_label)) },
                                placeholder = { Text(stringResource(R.string.freeproxy_port_custom_placeholder)) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("freeproxy_port_input")
                            )
                        }
                        Text(
                            text = stringResource(R.string.freeproxy_source_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(stringResource(R.string.section_ip_version_port), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = settings.includeIpv4,
                            onClick = onToggleIpv4,
                            leadingIcon = if (settings.includeIpv4) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = { Text(stringResource(R.string.label_ipv4), fontWeight = FontWeight.SemiBold) },
                            shape = CircleShape,
                            colors = pillChipColors,
                            border = pillChipBorder,
                            modifier = Modifier.testTag("toggle_ipv4_chip")
                        )
                        FilterChip(
                            selected = settings.includeIpv6,
                            onClick = onToggleIpv6,
                            leadingIcon = if (settings.includeIpv6) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = { Text(stringResource(R.string.label_ipv6), fontWeight = FontWeight.SemiBold) },
                            shape = CircleShape,
                            colors = pillChipColors,
                            border = pillChipBorder,
                            modifier = Modifier.testTag("toggle_ipv6_chip")
                        )
                        if (hasCdnOrCustomSelected) {
                            Box(modifier = Modifier.weight(1f)) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    OutlinedTextField(
                                        value = settings.targetPortsInput,
                                        onValueChange = { v ->
                                            val normalized = normalizePortInput(v)
                                            onUpdateSettings { it.copy(targetPortsInput = normalized) }
                                        },
                                        label = { Text(stringResource(R.string.label_target_ports)) },
                                        placeholder = { Text(stringResource(R.string.placeholder_target_ports)) },
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("target_ports_input")
                                    )
                                }
                            }
                        }
                    }

                    Text(stringResource(R.string.section_proxy_protocol), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = settings.includeHttp,
                            onClick = onToggleHttp,
                            leadingIcon = if (settings.includeHttp) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = { Text(stringResource(R.string.label_http), fontWeight = FontWeight.SemiBold) },
                            shape = CircleShape,
                            colors = pillChipColors,
                            border = pillChipBorder,
                            modifier = Modifier.testTag("toggle_http_chip")
                        )
                        FilterChip(
                            selected = settings.includeSocks5,
                            onClick = onToggleSocks5,
                            leadingIcon = if (settings.includeSocks5) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = { Text(stringResource(R.string.label_socks5), fontWeight = FontWeight.SemiBold) },
                            shape = CircleShape,
                            colors = pillChipColors,
                            border = pillChipBorder,
                            modifier = Modifier.testTag("toggle_socks5_chip")
                        )
                    }

                    Text(stringResource(R.string.section_probe_method), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PsiphonMethod.entries.forEach { method ->
                            FilterChip(
                                selected = settings.method == method,
                                onClick = { onSelectMethod(method) },
                                label = { Text(method.wireName) },
                                shape = CircleShape,
                                colors = pillChipColors,
                                border = pillChipBorder,
                                modifier = Modifier.testTag("method_chip_${method.badge.lowercase()}")
                            )
                        }
                    }

                    if (CdnProvider.CUSTOM in activeProviders) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = settings.customTargetsInput,
                                onValueChange = { v -> onUpdateSettings { it.copy(customTargetsInput = v) } },
                                label = { Text(stringResource(R.string.label_custom_ranges)) },
                                placeholder = { Text(stringResource(R.string.placeholder_custom_ranges)) },
                                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("custom_targets_input")
                            )
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onUpdateSettings { current ->
                                        val appended = listOf(current.customTargetsInput.trim(), "23.32.0.0/24, 2.16.10.0/24")
                                            .filter { it.isNotEmpty() }
                                            .joinToString("\n")
                                        current.copy(customTargetsInput = appended)
                                    }
                                },
                                label = { Text("+ " + stringResource(R.string.provider_akamai)) },
                                shape = CircleShape,
                                colors = pillChipColors,
                                border = pillChipBorder
                            )
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onUpdateSettings { current ->
                                        val appended = listOf(current.customTargetsInput.trim(), "151.101.0.0/24, 2a04:4e42::1")
                                            .filter { it.isNotEmpty() }
                                            .joinToString("\n")
                                        current.copy(customTargetsInput = appended)
                                    }
                                },
                                label = { Text("+ " + stringResource(R.string.provider_fastly)) },
                                shape = CircleShape,
                                colors = pillChipColors,
                                border = pillChipBorder
                            )
                        }
                    }

                    Button(
                        onClick = if (progress.isScanning) onStopScan else onStartScan,
                        shape = RoundedCornerShape(14.dp),
                        colors = if (progress.isScanning) {
                            ButtonDefaults.buttonColors(containerColor = PsiphonCrimson, contentColor = Color.White)
                        } else {
                            ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.White)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scan_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (progress.isScanning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (progress.isScanning) R.string.btn_stop_scan else R.string.btn_start_scan
                            ),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Live Progress & Counters
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(progress.stage.labelRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = ElectricCyan
                        )
                        if (progress.totalCount > 0) {
                            Text(
                                text = "\u2066${progress.testedCount} / ${progress.totalCount}\u2069",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (progress.isScanning) {
                        if (progress.totalCount > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (progress.testedCount.toFloat() / progress.totalCount.toFloat()).coerceIn(0f, 1f)
                                },
                                color = ElectricCyan,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(
                                color = PsiphonCrimson,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TelemetryStatCard(
                            label = stringResource(R.string.stat_clean_passed),
                            value = "${progress.passedCount}",
                            accent = EmeraldShield,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatCard(
                            label = stringResource(R.string.stat_failed_blocked),
                            value = "${progress.failedCount}",
                            accent = CoralError,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Live Scan Stream
        if (liveResults.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.live_probe_results_count, displayedLiveResults.size),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (livePorts.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = effectiveLivePortFilter == null,
                                onClick = { livePortFilter = null },
                                label = { Text(stringResource(R.string.filter_port_all)) },
                                shape = CircleShape,
                                colors = pillChipColors,
                                border = pillChipBorder,
                                modifier = Modifier.testTag("live_port_chip_all")
                            )
                            livePorts.forEach { port ->
                                FilterChip(
                                    selected = effectiveLivePortFilter == port,
                                    onClick = { livePortFilter = if (effectiveLivePortFilter == port) null else port },
                                    label = { Text(":$port") },
                                    shape = CircleShape,
                                    colors = pillChipColors,
                                    border = pillChipBorder,
                                    modifier = Modifier.testTag("live_port_chip_$port")
                                )
                            }
                        }
                    }
                }
            }

            items(displayedLiveResults, key = { "${it.ip}:${it.targetPort}:${it.proxyProtocol}" }) { result ->
                val idKey = "${result.ip}:${result.targetPort}:${result.proxyProtocol}"
                val isSelected = settings.selectedIpKey == idKey
                val countryDisplayName = if (result.countryCode == "--") {
                    stringResource(R.string.country_unknown)
                } else {
                    PsiphonRegions.resolveDisplayName(result.countryCode, activeLocale)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { if (result.isClean) onSelectIpKey(idKey) },
                    color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = when {
                            isSelected -> ElectricCyan
                            result.isClean -> EmeraldShield.copy(alpha = 0.35f)
                            else -> CoralError.copy(alpha = 0.25f)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = result.countryFlag,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "\u2066${result.ip}:${result.targetPort}\u2069",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            PingBadge(
                                pingMs = result.httpsPingMs ?: result.tcpPingMs,
                                isClean = result.isClean
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IpVersionBadge(result.ipVersion)
                            ProtocolBadge(result.proxyProtocol)
                            Text(
                                text = if (result.isClean) {
                                    "$countryDisplayName • ${stringResource(result.provider.labelRes)} • ${result.tlsVersion}"
                                } else {
                                    result.failureReason ?: stringResource(R.string.probe_failed_fallback)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (result.isClean) EmeraldShield else CoralError,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val value = "${result.ip}:${result.targetPort}"
                                    clipboard.setText(AnnotatedString(value))
                                    onCopyFeedback(context.getString(R.string.msg_copied_value, value))
                                }
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.cd_copy_ip_port),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun normalizePortInput(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        when (ch) {
            in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
            in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
            '،' -> append(',')
            else -> append(ch)
        }
    }
}

