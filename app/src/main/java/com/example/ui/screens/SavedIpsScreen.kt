package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
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
import com.example.psiphon.PsiphonRegions
import com.example.ui.components.AiScoreBadge
import com.example.ui.components.AnonymityConfidenceBadge
import com.example.ui.components.IpVersionBadge
import com.example.ui.components.PingBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.rememberEncryptedBackupController
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldShield
import java.util.Locale

@Composable
fun SavedIpsScreen(
    savedIps: List<CleanIpEntity>,
    selectedIpKey: String,
    isScanning: Boolean,
    onSelectIp: (String) -> Unit,
    onRescanIp: (CleanIpEntity) -> Unit,
    onRescanAll: () -> Unit,
    onRemoveFailed: () -> Unit,
    onDeleteIp: (String) -> Unit,
    onClearAll: () -> Unit,
    onCopyFeedback: (String) -> Unit,
    onExportEncryptedBackup: (Uri, String) -> Unit,
    onValidateBackupFile: (Uri, (Uri) -> Unit) -> Unit,
    onImportEncryptedBackup: (Uri, String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val activeLocale = LocalConfiguration.current.locales[0] ?: Locale.ENGLISH
    val backupController = rememberEncryptedBackupController(
        savedProxyCount = savedIps.size,
        onExportToUri = onExportEncryptedBackup,
        onValidateBackupUri = onValidateBackupFile,
        onImportFromUri = onImportEncryptedBackup
    )
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("CLEAN") } // ALL, CLEAN, HTTP, SOCKS5, IPV4, IPV6
    var portFilterInput by remember { mutableStateOf("") }

    val availablePorts = remember(savedIps) {
        val discovered = savedIps.map { it.targetPort }.distinct().sorted()
        discovered.ifEmpty { listOf(443, 80, 1080, 8080) }
    }

    val pillChipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = ElectricCyan,
        selectedLabelColor = Color.White
    )

    val filtered = remember(savedIps, searchQuery, filterMode, portFilterInput, activeLocale) {
        val requiredPort = portFilterInput.trim().toIntOrNull()
        savedIps.filter { entry ->
            val matchesFilter = when (filterMode) {
                "CLEAN" -> entry.isClean
                "HTTP" -> entry.proxyProtocol.equals("HTTP", ignoreCase = true)
                "SOCKS5" -> entry.proxyProtocol.equals("SOCKS5", ignoreCase = true)
                "IPV4" -> entry.ipVersion == "IPv4"
                "IPV6" -> entry.ipVersion == "IPv6"
                else -> true
            }
            val matchesPort = requiredPort == null || entry.targetPort == requiredPort
            val localizedCountry = if (entry.countryCode == "--") {
                ""
            } else {
                PsiphonRegions.resolveDisplayName(entry.countryCode, activeLocale)
            }
            val matchesSearch = searchQuery.isBlank() ||
                entry.ip.contains(searchQuery, ignoreCase = true) ||
                entry.targetPort.toString().contains(searchQuery.trim(), ignoreCase = true) ||
                entry.countryName.contains(searchQuery, ignoreCase = true) ||
                localizedCountry.contains(searchQuery, ignoreCase = true) ||
                entry.countryCode.contains(searchQuery, ignoreCase = true) ||
                entry.city.contains(searchQuery, ignoreCase = true) ||
                entry.isp.contains(searchQuery, ignoreCase = true) ||
                entry.provider.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesPort && matchesSearch
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Grouped Verify All / Remove Failed Card (matches screenshot 2)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = savedIps.isNotEmpty() && !isScanning, onClick = onRescanAll)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag("rescan_all_saved_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = null,
                                tint = EmeraldShield,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.btn_rescan),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldShield
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = savedIps.any { !it.isClean } && !isScanning, onClick = onRemoveFailed)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag("remove_failed_ips_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = null,
                                tint = CoralError,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.btn_remove_failed),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = CoralError
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val cleanCount = filtered.count { it.isClean }
                            val exportText = filtered.filter { it.isClean }
                                .joinToString("\n") { "${it.ip}:${it.targetPort}" }
                            if (exportText.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(exportText))
                                onCopyFeedback(context.getString(R.string.msg_copied_clean_count, cleanCount))
                            } else {
                                onCopyFeedback(context.getString(R.string.msg_no_clean_export))
                            }
                        },
                        enabled = filtered.any { it.isClean },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_clean_ips_button")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_copy_clean), fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onClearAll,
                        enabled = savedIps.isNotEmpty() && !isScanning,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("clear_saved_ips_button")
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = stringResource(R.string.cd_clear_all_saved)
                        )
                    }
                }

                // Encrypted Backup / Restore Row (AES-256-GCM)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { backupController.launchExport() },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, EmeraldShield.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("saved_export_encrypted_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.EnhancedEncryption,
                            contentDescription = null,
                            tint = EmeraldShield,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.backup_btn_export),
                            color = EmeraldShield,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { backupController.launchImport() },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("saved_import_encrypted_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LockOpen,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.backup_btn_import),
                            color = ElectricCyan,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.placeholder_search_saved)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("saved_ips_search_input")
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "CLEAN" to stringResource(R.string.filter_clean_only),
                        "ALL" to stringResource(R.string.filter_all_count, savedIps.size),
                        "HTTP" to stringResource(R.string.filter_http),
                        "SOCKS5" to stringResource(R.string.filter_socks5),
                        "IPV4" to stringResource(R.string.label_ipv4),
                        "IPV6" to stringResource(R.string.label_ipv6)
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = portFilterInput.isBlank(),
                            onClick = { portFilterInput = "" },
                            label = { Text(stringResource(R.string.filter_port_all)) },
                            shape = CircleShape,
                            colors = pillChipColors,
                            modifier = Modifier.testTag("saved_port_chip_all")
                        )
                        availablePorts.forEach { port ->
                            val portStr = port.toString()
                            FilterChip(
                                selected = portFilterInput.trim() == portStr,
                                onClick = {
                                    portFilterInput = if (portFilterInput.trim() == portStr) "" else portStr
                                },
                                label = { Text(":$portStr") },
                                shape = CircleShape,
                                colors = pillChipColors,
                                modifier = Modifier.testTag("saved_port_chip_$portStr")
                            )
                        }
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = portFilterInput,
                            onValueChange = { v -> portFilterInput = normalizeDigits(v).take(5) },
                            label = { Text(stringResource(R.string.filter_port_input_label)) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .width(104.dp)
                                .testTag("saved_port_filter_input")
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
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
                        Text(stringResource(R.string.empty_saved_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.empty_saved_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filtered, key = { it.idKey }) { entry ->
                val isSelected = entry.idKey == selectedIpKey
                val countryDisplay = if (entry.countryCode == "--") {
                    stringResource(R.string.country_unknown)
                } else {
                    PsiphonRegions.resolveDisplayName(entry.countryCode, activeLocale)
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelectIp(entry.idKey) }
                        .testTag("saved_ip_card_${entry.idKey}"),
                    color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) ElectricCyan
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = entry.countryFlag, style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "\u2066${entry.ip}:${entry.targetPort}\u2069",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            PingBadge(
                                pingMs = entry.httpsPingMs ?: entry.tcpPingMs,
                                isClean = entry.isClean
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IpVersionBadge(entry.ipVersion)
                            ProtocolBadge(entry.proxyProtocol)
                            AiScoreBadge(aiScore = entry.aiScore, aiVerdict = entry.aiVerdict)
                            AnonymityConfidenceBadge(confidenceScore = entry.anonymityConfidenceScore)
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = stringResource(R.string.cd_selected_ip),
                                    tint = EmeraldShield,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = "$countryDisplay (${entry.countryCode}) • ${entry.provider}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.saved_card_port_details,
                                    entry.targetPort,
                                    entry.proxyProtocol,
                                    entry.tlsVersion
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Row {
                                IconButton(
                                    onClick = {
                                        val value = "${entry.ip}:${entry.targetPort}"
                                        clipboard.setText(AnnotatedString(value))
                                        onCopyFeedback(context.getString(R.string.msg_copied_value, value))
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = stringResource(R.string.cd_copy_ip_port)
                                    )
                                }
                                IconButton(onClick = { onRescanIp(entry) }) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.cd_rescan_ip)
                                    )
                                }
                                IconButton(onClick = { onDeleteIp(entry.idKey) }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(R.string.cd_delete_ip)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeDigits(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        when (ch) {
            in '0'..'9' -> append(ch)
            in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
            in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
        }
    }
}
