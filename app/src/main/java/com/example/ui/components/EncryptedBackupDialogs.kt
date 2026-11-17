package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldShield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Stable
class EncryptedBackupController internal constructor(
    private val onStartExport: () -> Unit,
    private val onStartImport: () -> Unit
) {
    fun launchExport() = onStartExport()
    fun launchImport() = onStartImport()
}

@Composable
fun rememberEncryptedBackupController(
    savedProxyCount: Int,
    onExportToUri: (Uri, String) -> Unit,
    onValidateBackupUri: (Uri, (Uri) -> Unit) -> Unit,
    onImportFromUri: (Uri, String, Boolean) -> Unit
): EncryptedBackupController {
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportPassphrase by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val passphrase = pendingExportPassphrase
        pendingExportPassphrase = ""
        if (uri != null && passphrase.length >= 4) {
            onExportToUri(uri, passphrase)
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onValidateBackupUri(uri) { validUri ->
                pendingImportUri = validUri
            }
        }
    }

    if (showExportDialog) {
        ExportBackupPassphraseDialog(
            savedProxyCount = savedProxyCount,
            onDismiss = { showExportDialog = false },
            onConfirmPassphrase = { passphrase ->
                showExportDialog = false
                pendingExportPassphrase = passphrase
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                createBackupLauncher.launch("ipfounder-proxy-backup-$stamp.ipfbak")
            }
        )
    }

    val importUri = pendingImportUri
    if (importUri != null) {
        ImportBackupPassphraseDialog(
            onDismiss = { pendingImportUri = null },
            onConfirmRestore = { passphrase, replaceExisting ->
                pendingImportUri = null
                onImportFromUri(importUri, passphrase, replaceExisting)
            }
        )
    }

    return remember {
        EncryptedBackupController(
            onStartExport = { showExportDialog = true },
            onStartImport = { openBackupLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
        )
    }
}

@Composable
private fun ExportBackupPassphraseDialog(
    savedProxyCount: Int,
    onDismiss: () -> Unit,
    onConfirmPassphrase: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var attemptedSubmit by remember { mutableStateOf(false) }

    val tooShort = passphrase.length < 4
    val mismatch = passphrase != confirmPassphrase
    val errorRes: Int? = when {
        !attemptedSubmit -> null
        tooShort -> R.string.backup_err_short_passphrase
        mismatch -> R.string.backup_err_mismatch_passphrase
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.EnhancedEncryption,
                contentDescription = null,
                tint = EmeraldShield
            )
        },
        title = {
            Text(
                text = stringResource(R.string.backup_export_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = EmeraldShield.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, EmeraldShield.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = stringResource(R.string.backup_cipher_badge),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldShield,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.backup_export_dialog_body, savedProxyCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { showPassword = !showPassword },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_passphrase_visibility)
                            )
                        }
                    },
                    isError = attemptedSubmit && tooShort,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_export_passphrase_input")
                )
                OutlinedTextField(
                    value = confirmPassphrase,
                    onValueChange = { confirmPassphrase = it },
                    label = { Text(stringResource(R.string.backup_confirm_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    isError = attemptedSubmit && mismatch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_export_confirm_input")
                )
                if (errorRes != null) {
                    Text(
                        text = stringResource(errorRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = CoralError
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    attemptedSubmit = true
                    if (!tooShort && !mismatch) {
                        onConfirmPassphrase(passphrase)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldShield, contentColor = Color.White),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("backup_export_confirm_button")
            ) {
                Text(stringResource(R.string.backup_btn_choose_file), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.backup_btn_cancel))
            }
        }
    )
}

@Composable
private fun ImportBackupPassphraseDialog(
    onDismiss: () -> Unit,
    onConfirmRestore: (String, Boolean) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var replaceExisting by remember { mutableStateOf(false) }
    var attemptedSubmit by remember { mutableStateOf(false) }

    val tooShort = passphrase.length < 4

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.LockOpen,
                contentDescription = null,
                tint = ElectricCyan
            )
        },
        title = {
            Text(
                text = stringResource(R.string.backup_import_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.backup_import_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { showPassword = !showPassword },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_passphrase_visibility)
                            )
                        }
                    },
                    isError = attemptedSubmit && tooShort,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_import_passphrase_input")
                )
                if (attemptedSubmit && tooShort) {
                    Text(
                        text = stringResource(R.string.backup_err_short_passphrase),
                        style = MaterialTheme.typography.labelSmall,
                        color = CoralError
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.backup_replace_existing_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.backup_replace_existing_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = replaceExisting,
                        onCheckedChange = { replaceExisting = it },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("backup_import_replace_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    attemptedSubmit = true
                    if (!tooShort) {
                        onConfirmRestore(passphrase, replaceExisting)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.White),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("backup_import_confirm_button")
            ) {
                Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.backup_btn_decrypt_restore), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.backup_btn_cancel))
            }
        }
    )
}
