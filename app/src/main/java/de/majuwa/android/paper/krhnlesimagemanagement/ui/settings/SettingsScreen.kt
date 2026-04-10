package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun settingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val browserUrl by viewModel.openBrowserEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(browserUrl) {
        browserUrl?.let { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            viewModel.consumeBrowserEvent()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            if (uiState.isLoggedIn) {
                LoggedInContent(uiState, viewModel)
            } else {
                LoginContent(uiState, viewModel)
            }
        }
    }
}

@Composable
private fun LoggedInContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Text("Connected", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    Text("User: ${uiState.username}", style = MaterialTheme.typography.bodyMedium)
    Text(
        "WebDAV: ${uiState.webDavUrl}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = uiState.baseFolder,
        onValueChange = { viewModel.onBaseFolderChange(it) },
        label = { Text("Base upload folder") },
        placeholder = { Text("Photos/KrohnSync") },
        supportingText = { Text("All uploads go inside this folder. Leave empty for root.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = { viewModel.saveBaseFolder() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save folder")
    }

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedButton(
        onClick = { viewModel.testConnection() },
        enabled = !uiState.isTesting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (uiState.isTesting) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        } else {
            Text("Test Connection")
        }
    }

    uiState.testResult?.let { result ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = result,
            color =
                if (result.startsWith("Connection successful")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    OutlinedButton(
        onClick = { viewModel.logout() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Disconnect")
    }
}

@Composable
private fun LoginContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Text(
        text = "Connect to Nextcloud",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Sign in via your browser for a secure connection.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = uiState.serverUrl,
        onValueChange = { viewModel.onServerUrlChange(it) },
        label = { Text("Nextcloud URL") },
        placeholder = { Text("https://nextcloud.example.com") },
        singleLine = true,
        enabled = !uiState.isLoading && !uiState.isWaitingForBrowser,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(16.dp))

    when {
        uiState.isLoading || uiState.isWaitingForBrowser -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                if (uiState.isWaitingForBrowser) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Waiting for browser login...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        else -> {
            Button(
                onClick = { viewModel.startLoginFlow() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect via Browser")
            }
        }
    }

    uiState.error?.let { error ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Spacer(modifier = Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    TextButton(onClick = { viewModel.toggleManualConfig() }) {
        Text(if (uiState.useManualConfig) "Hide manual config" else "Manual WebDAV config")
    }

    if (uiState.useManualConfig) {
        ManualConfigContent(uiState, viewModel)
    }
}

@Composable
private fun ManualConfigContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = uiState.manualUrl,
        onValueChange = { viewModel.onManualUrlChange(it) },
        label = { Text("WebDAV URL") },
        placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = uiState.manualUsername,
        onValueChange = { viewModel.onManualUsernameChange(it) },
        label = { Text("Username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = uiState.manualPassword,
        onValueChange = { viewModel.onManualPasswordChange(it) },
        label = { Text("Password / App Token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = { viewModel.saveManualConfig() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save & Connect")
    }
}
