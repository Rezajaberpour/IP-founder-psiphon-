package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.preferences.AppLanguage
import com.example.data.preferences.ScannerSettings
import com.example.ui.components.rememberEncryptedBackupController
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldShield

@Composable
fun SettingsScreen(
    settings: ScannerSettings,
    importedServerCount: Int,
    savedProxyCount: Int,
    onUpdateSettings: ((ScannerSettings) -> ScannerSettings) -> Unit,
    onImportServerEntries: (String) -> Unit,
    onExportEncryptedBackup: (Uri, String) -> Unit,
    onValidateBackupFile: (Uri, (Uri) -> Unit) -> Unit,
    onImportEncryptedBackup: (Uri, String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backupController = rememberEncryptedBackupController(
        savedProxyCount = savedProxyCount,
        onExportToUri = onExportEncryptedBackup,
        onValidateBackupUri = onValidateBackupFile,
        onImportFromUri = onImportEncryptedBackup
    )
    var socksPortText by remember { mutableStateOf(settings.localSocksPort.toString()) }
    var hasEditedSocksPort by remember { mutableStateOf(false) }
    LaunchedEffect(settings.localSocksPort) {
        if (!hasEditedSocksPort) {
            socksPortText = settings.localSocksPort.toString()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            }
            if (!content.isNullOrBlank()) {
                onImportServerEntries(content)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Language Selector
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        text = stringResource(R.string.settings_language_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val languages = listOf(
                            AppLanguage.SYSTEM to R.string.lang_system,
                            AppLanguage.ENGLISH to R.string.lang_english,
                            AppLanguage.PERSIAN to R.string.lang_persian
                        )
                        val chipColors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            selectedContainerColor = ElectricCyan,
                            selectedLabelColor = Color.White
                        )
                        languages.forEach { (lang, labelRes) ->
                            FilterChip(
                                selected = settings.appLanguage == lang,
                                onClick = { onUpdateSettings { it.copy(appLanguage = lang) } },
                                label = {
                                    Text(
                                        text = stringResource(labelRes),
                                        fontWeight = if (settings.appLanguage == lang) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                shape = CircleShape,
                                colors = chipColors,
                                modifier = Modifier.testTag("lang_chip_${lang.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        // Probe & TLS Settings
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.settings_verification_title), style = MaterialTheme.typography.titleLarge)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_verify_tls_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.settings_verify_tls_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.verifyTls,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(verifyTls = v) } },
                            modifier = Modifier.testTag("verify_tls_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_verify_https_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(R.string.settings_verify_https_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.verifyHttpsTunnel,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(verifyHttpsTunnel = v) } },
                            modifier = Modifier.testTag("verify_https_switch")
                        )
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = settings.sniOverride,
                            onValueChange = { v -> onUpdateSettings { it.copy(sniOverride = v) } },
                            label = { Text(stringResource(R.string.settings_sni_label)) },
                            placeholder = { Text(stringResource(R.string.settings_sni_placeholder)) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_sni_input")
                        )

                        OutlinedTextField(
                            value = socksPortText,
                            onValueChange = { v ->
                                hasEditedSocksPort = true
                                val digits = v.filter(Char::isDigit).take(5)
                                socksPortText = digits
                                val parsed = digits.toIntOrNull()
                                if (parsed != null && parsed in 1024..65535) {
                                    onUpdateSettings { it.copy(localSocksPort = parsed) }
                                }
                            },
                            label = { Text(stringResource(R.string.settings_socks_label)) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_local_socks_input")
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.settings_max_candidates, settings.maxCandidates),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Slider(
                            value = settings.maxCandidates.toFloat(),
                            onValueChange = { v ->
                                onUpdateSettings { it.copy(maxCandidates = v.toInt()) }
                            },
                            valueRange = 8f..64f,
                            steps = 6
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.settings_probe_timeout, settings.timeoutMs),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Slider(
                            value = settings.timeoutMs.toFloat(),
                            onValueChange = { v ->
                                onUpdateSettings { it.copy(timeoutMs = v.toInt()) }
                            },
                            valueRange = 1500f..6000f,
                            steps = 8
                        )
                    }
                }
            }
        }

        // Encrypted Proxy Backup & Restore (AES-256-GCM + SAF)
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, EmeraldShield.copy(alpha = 0.35f))
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.EnhancedEncryption,
                                contentDescription = null,
                                tint = EmeraldShield
                            )
                            Text(
                                text = stringResource(R.string.backup_card_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Surface(
                            color = EmeraldShield.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, EmeraldShield.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = stringResource(R.string.backup_cipher_badge),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldShield,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.backup_card_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.backup_saved_summary, savedProxyCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { backupController.launchExport() },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldShield,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_export_encrypted_button")
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.backup_btn_export), fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { backupController.launchImport() },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_import_encrypted_button")
                        ) {
                            Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.backup_btn_import), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Optional server_entries.txt Import via Zero-Permission SAF
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.settings_server_entries_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.settings_server_entries_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (importedServerCount > 0) {
                        Text(
                            text = stringResource(R.string.settings_server_entries_loaded, importedServerCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    FilledTonalButton(
                        onClick = { filePickerLauncher.launch(arrayOf("text/*", "*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_server_entries_button")
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_import_server_entries))
                    }
                }
            }
        }

        // Open-Source Attribution (GPL-3.0)
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Text(stringResource(R.string.settings_about_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = stringResource(R.string.settings_about_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.settings_about_pipeline),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
