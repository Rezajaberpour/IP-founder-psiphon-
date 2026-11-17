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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.minimumInteractiveComponentSize
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

enum class GeoIpSortColumn {
    ANONYMITY,
    COUNTRY,
    CITY,
    ISP,
    PING
}

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
    val isClean: Boolean
)

fun buildGeoIpTableRows(
    liveResults: List<ProbeTelemetry>,
    savedIps: List<CleanIpEntity>
): List<GeoIpTableRow> {
    val map = linkedMapOf<String, GeoIpTableRow>()
    liveResults.forEach { t ->
        val idKey = "${t.ip}:${t.targetPort}:${t.proxyProtocol}"
        map[idKey] = GeoIpTableRow(
            idKey = idKey,
            ip = t.ip,
            targetPort = t.targetPort,
            proxyProtocol = t.proxyProtocol,
            countryCode = t.countryCode,
            countryName = t.countryName,
            countryFlag = t.countryFlag,
            city = t.city.ifBlank { "Unknown" },
            isp = t.isp.ifBlank { t.provider.displayName },
            anonymityConfidenceScore = t.anonymityConfidenceScore,
            pingMs = t.httpsPingMs ?: t.tcpPingMs,
            isClean = t.isClean
        )
    }
    savedIps.forEach { e ->
        if (!map.containsKey(e.idKey)) {
            map[e.idKey] = GeoIpTableRow(
                idKey = e.idKey,
                ip = e.ip,
                targetPort = e.targetPort,
                proxyProtocol = e.proxyProtocol,
                countryCode = e.countryCode,
                countryName = e.countryName,
                countryFlag = e.countryFlag,
                city = e.city.ifBlank { "Unknown" },
                isp = e.isp.ifBlank { e.provider },
                anonymityConfidenceScore = e.anonymityConfidenceScore,
                pingMs = e.httpsPingMs ?: e.tcpPingMs,
                isClean = e.isClean
            )
        }
    }
    return map.values.toList()
}

fun filterAndSortGeoIpRows(
    rows: List<GeoIpTableRow>,
    query: String,
    filterMode: String,
    sortColumn: GeoIpSortColumn,
    sortDescending: Boolean,
    locale: Locale = Locale.ENGLISH
): List<GeoIpTableRow> {
    val trimmed = query.trim()
    val filtered = rows.filter { row ->
        val matchesMode = when (filterMode) {
            "CLEAN" -> row.isClean
            "HTTP" -> row.proxyProtocol.equals("HTTP", ignoreCase = true)
            "SOCKS5" -> row.proxyProtocol.equals("SOCKS5", ignoreCase = true)
            else -> true
        }
        val localizedCountry = if (row.countryCode == "--") "" else PsiphonRegions.resolveDisplayName(row.countryCode, locale)
        val matchesQuery = trimmed.isBlank() ||
            row.ip.contains(trimmed, ignoreCase = true) ||
            row.targetPort.toString().contains(trimmed, ignoreCase = true) ||
            row.countryCode.contains(trimmed, ignoreCase = true) ||
            row.countryName.contains(trimmed, ignoreCase = true) ||
            localizedCountry.contains(trimmed, ignoreCase = true) ||
            row.city.contains(trimmed, ignoreCase = true) ||
            row.isp.contains(trimmed, ignoreCase = true)
        matchesMode && matchesQuery
    }

    val comparator = when (sortColumn) {
        GeoIpSortColumn.ANONYMITY -> compareBy<GeoIpTableRow> { it.anonymityConfidenceScore }
        GeoIpSortColumn.COUNTRY -> compareBy<GeoIpTableRow> { it.countryName.lowercase(Locale.US) }
        GeoIpSortColumn.CITY -> compareBy<GeoIpTableRow> { it.city.lowercase(Locale.US) }
        GeoIpSortColumn.ISP -> compareBy<GeoIpTableRow> { it.isp.lowercase(Locale.US) }
        GeoIpSortColumn.PING -> compareBy<GeoIpTableRow> { it.pingMs ?: Long.MAX_VALUE }
    }
    return if (sortDescending) filtered.sortedWith(comparator.reversed()) else filtered.sortedWith(comparator)
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
    var filterMode by remember { mutableStateOf("ALL") }
    var sortColumn by remember { mutableStateOf(GeoIpSortColumn.ANONYMITY) }
    var sortDescending by remember { mutableStateOf(true) }

    val allRows = remember(liveResults, savedIps) {
        buildGeoIpTableRows(liveResults = liveResults, savedIps = savedIps)
    }
    val displayedRows = remember(allRows, searchQuery, filterMode, sortColumn, sortDescending, activeLocale) {
        filterAndSortGeoIpRows(
            rows = allRows,
            query = searchQuery,
            filterMode = filterMode,
            sortColumn = sortColumn,
            sortDescending = sortDescending,
            locale = activeLocale
        )
    }
    val distinctCountries = remember(allRows) {
        allRows.map { it.countryCode }.filter { it != "--" && it.isNotBlank() }.distinct().size
    }

    val pillChipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = ElectricCyan,
        selectedLabelColor = Color.White
    )

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
                            value = "$distinctCountries",
                            accent = EmeraldShield,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.geoip_search_placeholder)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.cd_clear_search)
                                    )
                                }
                            }
                        } else null,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("geoip_search_input")
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "ALL" to stringResource(R.string.filter_all_count, allRows.size),
                            "CLEAN" to stringResource(R.string.filter_clean_only),
                            "HTTP" to stringResource(R.string.filter_http),
                            "SOCKS5" to stringResource(R.string.filter_socks5)
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = filterMode == key,
                                onClick = { filterMode = key },
                                label = { Text(label) },
                                shape = CircleShape,
                                colors = pillChipColors
                            )
                        }
                    }
                }
            }
        }

        // Scrollable Sortable Data Table Header
        item {
            val hScroll = rememberScrollState()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScroll)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.geoip_col_ip),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = ElectricCyan,
                            modifier = Modifier.width(156.dp)
                        )
                        SortableHeaderCell(
                            label = stringResource(R.string.geoip_col_country),
                            active = sortColumn == GeoIpSortColumn.COUNTRY,
                            descending = sortDescending,
                            onClick = {
                                if (sortColumn == GeoIpSortColumn.COUNTRY) sortDescending = !sortDescending
                                else {
                                    sortColumn = GeoIpSortColumn.COUNTRY
                                    sortDescending = false
                                }
                            },
                            modifier = Modifier.width(136.dp)
                        )
                        SortableHeaderCell(
                            label = stringResource(R.string.geoip_col_city),
                            active = sortColumn == GeoIpSortColumn.CITY,
                            descending = sortDescending,
                            onClick = {
                                if (sortColumn == GeoIpSortColumn.CITY) sortDescending = !sortDescending
                                else {
                                    sortColumn = GeoIpSortColumn.CITY
                                    sortDescending = false
                                }
                            },
                            modifier = Modifier.width(116.dp)
                        )
                        SortableHeaderCell(
                            label = stringResource(R.string.geoip_col_isp),
                            active = sortColumn == GeoIpSortColumn.ISP,
                            descending = sortDescending,
                            onClick = {
                                if (sortColumn == GeoIpSortColumn.ISP) sortDescending = !sortDescending
                                else {
                                    sortColumn = GeoIpSortColumn.ISP
                                    sortDescending = false
                                }
                            },
                            modifier = Modifier.width(156.dp)
                        )
                        SortableHeaderCell(
                            label = stringResource(R.string.geoip_col_anonymity),
                            active = sortColumn == GeoIpSortColumn.ANONYMITY,
                            descending = sortDescending,
                            onClick = {
                                if (sortColumn == GeoIpSortColumn.ANONYMITY) sortDescending = !sortDescending
                                else {
                                    sortColumn = GeoIpSortColumn.ANONYMITY
                                    sortDescending = true
                                }
                            },
                            modifier = Modifier.width(118.dp)
                        )
                        SortableHeaderCell(
                            label = stringResource(R.string.geoip_col_ping),
                            active = sortColumn == GeoIpSortColumn.PING,
                            descending = sortDescending,
                            onClick = {
                                if (sortColumn == GeoIpSortColumn.PING) sortDescending = !sortDescending
                                else {
                                    sortColumn = GeoIpSortColumn.PING
                                    sortDescending = false
                                }
                            },
                            modifier = Modifier.width(104.dp)
                        )
                    }

                    if (displayedRows.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
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
                    } else {
                        displayedRows.forEachIndexed { idx, row ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            val isSelected = row.idKey == selectedIpKey
                            val countryLabel = if (row.countryCode == "--") {
                                stringResource(R.string.country_unknown)
                            } else {
                                PsiphonRegions.resolveDisplayName(row.countryCode, activeLocale)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(hScroll)
                                    .clickable { if (row.isClean) onSelectIp(row.idKey) }
                                    .background(
                                        if (isSelected) EmeraldShield.copy(alpha = 0.10f)
                                        else if (idx % 2 == 0) MaterialTheme.colorScheme.surfaceContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("geoip_row_${row.idKey}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.width(156.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Filled.CheckCircle,
                                                    contentDescription = stringResource(R.string.cd_selected_ip),
                                                    tint = EmeraldShield,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = "\u2066${row.ip}:${row.targetPort}\u2069",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        ProtocolBadge(protocol = row.proxyProtocol)
                                    }
                                    IconButton(
                                        onClick = {
                                            val value = "${row.ip}:${row.targetPort}"
                                            clipboard.setText(AnnotatedString(value))
                                            onCopyFeedback(context.getString(R.string.msg_copied_value, value))
                                        },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = stringResource(R.string.cd_copy_ip_port),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "${row.countryFlag} $countryLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(136.dp).padding(end = 6.dp)
                                )

                                Text(
                                    text = row.city,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(116.dp).padding(end = 6.dp)
                                )

                                Text(
                                    text = row.isp,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VioletStudio,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(156.dp).padding(end = 6.dp)
                                )

                                Box(modifier = Modifier.width(118.dp)) {
                                    AnonymityConfidenceBadge(confidenceScore = row.anonymityConfidenceScore)
                                }

                                Box(modifier = Modifier.width(104.dp)) {
                                    PingBadge(pingMs = row.pingMs, isClean = row.isClean)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortableHeaderCell(
    label: String,
    active: Boolean,
    descending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (active) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (active) {
            Icon(
                imageVector = if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
