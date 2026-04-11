package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.majuwa.android.paper.krhnlesimagemanagement.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val browserUrl by viewModel.openBrowserEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(browserUrl) {
        browserUrl?.let { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            viewModel.consumeBrowserEvent()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
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
    Text(stringResource(R.string.status_connected), style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    Text(stringResource(R.string.label_user, uiState.username), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.label_webdav_url, uiState.webDavUrl),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = uiState.baseFolder,
        onValueChange = { viewModel.onBaseFolderChange(it) },
        label = { Text(stringResource(R.string.label_base_folder)) },
        placeholder = { Text(stringResource(R.string.placeholder_base_folder)) },
        supportingText = { Text(stringResource(R.string.hint_base_folder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = { viewModel.saveBaseFolder() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_save_folder))
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
            Text(stringResource(R.string.action_test_connection))
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
        Text(stringResource(R.string.action_disconnect))
    }
}

@Composable
private fun LoginContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Text(
        text = stringResource(R.string.title_connect_nextcloud),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.subtitle_connect_nextcloud),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = uiState.serverUrl,
        onValueChange = { viewModel.onServerUrlChange(it) },
        label = { Text(stringResource(R.string.label_nextcloud_url)) },
        placeholder = { Text(stringResource(R.string.placeholder_nextcloud_url)) },
        singleLine = true,
        enabled = !uiState.isLoading && !uiState.isWaitingForBrowser,
        modifier = Modifier.fillMaxWidth(),
    )

    if (uiState.httpWarning) {
        Spacer(modifier = Modifier.height(4.dp))
        HttpWarningBanner()
    }

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
                        text = stringResource(R.string.status_waiting_browser),
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
                Text(stringResource(R.string.action_connect_browser))
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
        val resId =
            if (uiState.useManualConfig) R.string.action_hide_manual_config else R.string.action_show_manual_config
        Text(stringResource(resId))
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
        label = { Text(stringResource(R.string.label_webdav_url_input)) },
        placeholder = { Text(stringResource(R.string.placeholder_webdav_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (uiState.httpWarning) {
        Spacer(modifier = Modifier.height(4.dp))
        HttpWarningBanner()
    }

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = uiState.manualUsername,
        onValueChange = { viewModel.onManualUsernameChange(it) },
        label = { Text(stringResource(R.string.label_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = uiState.manualPassword,
        onValueChange = { viewModel.onManualPasswordChange(it) },
        label = { Text(stringResource(R.string.label_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = { viewModel.saveManualConfig() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_save_connect))
    }
}

@Composable
private fun HttpWarningBanner() {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.padding(4.dp))
            Text(
                text = stringResource(R.string.warning_http_insecure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
