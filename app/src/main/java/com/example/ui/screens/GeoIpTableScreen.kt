package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.CleanIpEntity
import com.example.psiphon.ProbeTelemetry
import com.example.psiphon.PsiphonRegions
import com.example.ui.components.AnonymityConfidenceBadge
import com.example.ui.components.PingBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.TelemetryStatCard
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.VioletStudio
import java.util.Locale

data class GeoIpTableRow(
    val idKey: String,
    val ip: String,
    val targetPort: Int,
    val proxyProtocol: String,
    val countryCode: String,
    val countryName: String,
    val countryFlag: String,
    val city: String,
    val isp: String,
    val anonymityConfidenceScore: Int,
    val pingMs: Long?,
    val isClean: Boolean,
    val provider: String
)

enum class GeoIpSortColumn {
    ANONYMITY,
    COUNTRY,
    CITY,
    ISP,
    PING
}

fun buildGeoIpTableRows(
    liveResults: List<ProbeTelemetry>,
    savedIps: List<CleanIpEntity>,
    locale: Locale = Locale.ENGLISH
): List<GeoIpTableRow> {
    val merged = linkedMapOf<String, GeoIpTableRow>()

    liveResults.forEach { r ->
        val idKey = "${r.ip}:${r.targetPort}:${r.proxyProtocol}"
        val localizedCountry = if (r.countryCode == "--") r.countryName
        else PsiphonRegions.resolveDisplayName(r.countryCode, locale)
        merged[idKey] = GeoIpTableRow(
            idKey = idKey,
            ip = r.ip,
            targetPort = r.targetPort,
            proxyProtocol = r.proxyProtocol,
            countryCode = r.countryCode,
            countryName = localizedCountry,
            countryFlag = r.countryFlag,
            city = r.city.ifBlank { "Unknown" },
            isp = r.isp.ifBlank { r.provider.displayName },
            anonymityConfidenceScore = r.anonymityConfidenceScore,
            pingMs = r.httpsPingMs ?: r.tcpPingMs,
            isClean = r.isClean,
            provider = r.provider.displayName
        )
    }

    savedIps.forEach { e ->
        if (e.idKey !in merged) {
            val localizedCountry = if (e.countryCode == "--") e.countryName
            else PsiphonRegions.resolveDisplayName(e.countryCode, locale)
            merged[e.idKey] = GeoIpTableRow(
                idKey = e.idKey,
                ip = e.ip,
                targetPort = e.targetPort,
                proxyProtocol = e.proxyProtocol,
                countryCode = e.countryCode,
                countryName = localizedCountry,
                countryFlag = e.countryFlag,
                city = e.city.ifBlank { "Unknown" },
                isp = e.isp.ifBlank { e.provider },
                anonymityConfidenceScore = e.anonymityConfidenceScore,
                pingMs = e.httpsPingMs ?: e.tcpPingMs,
                isClean = e.isClean,
                provider = e.provider
            )
        }
    }

    return merged.values.toList()
}

fun filterAndSortGeoIpRows(
    rows: List<GeoIpTableRow>,
    query: String,
    filterMode: String,
    sortColumn: GeoIpSortColumn,
    sortDescending: Boolean
): List<GeoIpTableRow> {
    val q = query.trim()
    val filtered = rows.filter { row ->
        val matchesMode = when (filterMode) {
            "CLEAN" -> row.isClean
            "HTTP" -> row.proxyProtocol.equals("HTTP", ignoreCase = true)
            "SOCKS5" -> row.proxyProtocol.equals("SOCKS5", ignoreCase = true)
            else -> true
        }
        val matchesQuery = q.isBlank() ||
            row.ip.contains(q, ignoreCase = true) ||
            row.targetPort.toString().contains(q, ignoreCase = true) ||
            row.countryName.contains(q, ignoreCase = true) ||
            row.countryCode.contains(q, ignoreCase = true) ||
            row.city.contains(q, ignoreCase = true) ||
            row.isp.contains(q, ignoreCase = true) ||
            row.provider.contains(q, ignoreCase = true) ||
            row.proxyProtocol.contains(q, ignoreCase = true)
        matchesMode && matchesQuery
    }

    val comparator: Comparator<GeoIpTableRow> = when (sortColumn) {
        GeoIpSortColumn.ANONYMITY -> compareBy<GeoIpTableRow> { it.anonymityConfidenceScore }
            .thenByDescending { it.pingMs ?: Long.MAX_VALUE }
        GeoIpSortColumn.COUNTRY -> compareBy<GeoIpTableRow> { it.countryName.lowercase(Locale.US) }
            .thenBy { it.city.lowercase(Locale.US) }
        GeoIpSortColumn.CITY -> compareBy<GeoIpTableRow> { it.city.lowercase(Locale.US) }
            .thenBy { it.countryName.lowercase(Locale.US) }
        GeoIpSortColumn.ISP -> compareBy<GeoIpTableRow> { it.isp.lowercase(Locale.US) }
            .thenByDescending { it.anonymityConfidenceScore }
        GeoIpSortColumn.PING -> compareBy<GeoIpTableRow> { -(it.pingMs ?: Long.MAX_VALUE) }
    }

    return if (sortDescending) {
        filtered.sortedWith(comparator.reversed())
    } else {
        filtered.sortedWith(comparator)
    }
}

@Composable
fun GeoIpTableScreen(
    liveResults: List<ProbeTelemetry>,
    savedIps: List<CleanIpEntity>,
    selectedIpKey: String,
    onSelectIp: (String) -> Unit,
    onCopyFeedback: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val activeLocale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH

    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("ALL") } // ALL, CLEAN, HTTP, SOCKS5
    var sortColumn by remember { mutableStateOf(GeoIpSortColumn.ANONYMITY) }
    var sortDescending by remember { mutableStateOf(true) }

    val allRows = remember(liveResults, savedIps, activeLocale) {
        buildGeoIpTableRows(liveResults, savedIps, activeLocale)
    }

    val displayedRows = remember(allRows, searchQuery, filterMode, sortColumn, sortDescending) {
        filterAndSortGeoIpRows(
            rows = allRows,
            query = searchQuery,
            filterMode = filterMode,
            sortColumn = sortColumn,
            sortDescending = sortDescending
        )
    }

    val uniqueCountries = remember(displayedRows) {
        displayedRows.map { it.countryCode }.filter { it != "--" }.distinct().size
    }
    val avgAnon = remember(displayedRows) {
        if (displayedRows.isEmpty()) 0 else displayedRows.sumOf { it.anonymityConfidenceScore } / displayedRows.size
    }

    val pillChipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = ElectricCyan,
        selectedLabelColor = Color.White
    )

    val horizontalTableScroll = rememberScrollState()

    fun onHeaderClick(col: GeoIpSortColumn) {
        if (sortColumn == col) {
            sortDescending = !sortDescending
        } else {
            sortColumn = col
            sortDescending = col == GeoIpSortColumn.ANONYMITY
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.geoip_table_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = stringResource(R.string.geoip_table_subtitle),
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
                            label = stringResource(R.string.geoip_stat_endpoints),
                            value = "${displayedRows.size}",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatCard(
                            label = stringResource(R.string.geoip_stat_countries),
                            value = "$uniqueCountries",
                            accent = VioletStudio,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatCard(
                            label = stringResource(R.string.ai_stat_anon_confidence),
                            value = if (displayedRows.isEmpty()) "--" else "$avgAnon%",
                            accent = EmeraldShield,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.geoip_search_placeholder)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.cd_clear_search)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("geoip_table_search_input")
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "ALL" to stringResource(R.string.filter_all_count, allRows.size),
                            "CLEAN" to stringResource(R.string.filter_clean_only),
                            "SOCKS5" to stringResource(R.string.filter_socks5),
                            "HTTP" to stringResource(R.string.filter_http)
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = filterMode == key,
                                onClick = { filterMode = key },
                                label = { Text(label) },
                                shape = CircleShape,
                                colors = pillChipColors,
                                modifier = Modifier.testTag("geoip_filter_${key.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        if (displayedRows.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.geoip_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.geoip_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Table Header Card
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalTableScroll)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TableStaticHeaderCell(
                            label = stringResource(R.string.geoip_col_ip),
                            width = 168.dp
                        )
                        TableSortHeaderCell(
                            label = stringResource(R.string.geoip_col_country),
                            width = 148.dp,
                            active = sortColumn == GeoIpSortColumn.COUNTRY,
                            descending = sortDescending,
                            onClick = { onHeaderClick(GeoIpSortColumn.COUNTRY) }
                        )
                        TableSortHeaderCell(
                            label = stringResource(R.string.geoip_col_city),
                            width = 124.dp,
                            active = sortColumn == GeoIpSortColumn.CITY,
                            descending = sortDescending,
                            onClick = { onHeaderClick(GeoIpSortColumn.CITY) }
                        )
                        TableSortHeaderCell(
                            label = stringResource(R.string.geoip_col_isp),
                            width = 156.dp,
                            active = sortColumn == GeoIpSortColumn.ISP,
                            descending = sortDescending,
                            onClick = { onHeaderClick(GeoIpSortColumn.ISP) }
                        )
                        TableSortHeaderCell(
                            label = stringResource(R.string.geoip_col_anonymity),
                            width = 112.dp,
                            active = sortColumn == GeoIpSortColumn.ANONYMITY,
                            descending = sortDescending,
                            onClick = { onHeaderClick(GeoIpSortColumn.ANONYMITY) }
                        )
                        TableSortHeaderCell(
                            label = stringResource(R.string.geoip_col_ping),
                            width = 104.dp,
                            active = sortColumn == GeoIpSortColumn.PING,
                            descending = sortDescending,
                            onClick = { onHeaderClick(GeoIpSortColumn.PING) }
                        )
                        Spacer(modifier = Modifier.width(48.dp))
                    }
                }
            }

            itemsIndexed(displayedRows, key = { _, row -> row.idKey }) { idx, row ->
                val isSelected = row.idKey == selectedIpKey
                val rowBg = when {
                    isSelected -> ElectricCyan.copy(alpha = 0.12f)
                    idx % 2 == 0 -> MaterialTheme.colorScheme.surfaceContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .clickable { if (row.isClean) onSelectIp(row.idKey) }
                        .testTag("geoip_row_${row.idKey}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalTableScroll)
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.width(168.dp)) {
                            Text(
                                text = "\u2066${row.ip}:${row.targetPort}\u2069",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) ElectricCyan else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.size(2.dp))
                            ProtocolBadge(row.proxyProtocol)
                        }

                        Row(
                            modifier = Modifier
                                .width(148.dp)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = row.countryFlag, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${row.countryName} (${row.countryCode})",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = row.city,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(124.dp)
                                .padding(end = 8.dp)
                        )

                        Text(
                            text = row.isp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(156.dp)
                                .padding(end = 8.dp)
                        )

                        Box(modifier = Modifier.width(112.dp)) {
                            AnonymityConfidenceBadge(confidenceScore = row.anonymityConfidenceScore)
                        }

                        Box(modifier = Modifier.width(104.dp)) {
                            PingBadge(pingMs = row.pingMs, isClean = row.isClean)
                        }

                        IconButton(
                            onClick = {
                                val endpoint = "${row.ip}:${row.targetPort}"
                                clipboard.setText(AnnotatedString(endpoint))
                                onCopyFeedback(context.getString(R.string.msg_copied_value, endpoint))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_ip_port),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun TableStaticHeaderCell(
    label: String,
    width: Dp
) {
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TableSortHeaderCell(
    label: String,
    width: Dp,
    active: Boolean,
    descending: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(width)
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (active) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (active) {
            Icon(
                imageVector = if (descending) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowDropUp,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
