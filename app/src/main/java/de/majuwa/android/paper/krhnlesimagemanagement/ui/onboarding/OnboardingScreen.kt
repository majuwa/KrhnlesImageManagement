package de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.majuwa.android.paper.krhnlesimagemanagement.R

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onExit: (OnboardingExit) -> Unit,
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val isFinishing by viewModel.isFinishing.collectAsStateWithLifecycle()
    val exit by viewModel.exit.collectAsStateWithLifecycle()

    LaunchedEffect(exit) {
        exit?.let {
            viewModel.consumeExit()
            onExit(it)
        }
    }
    // Disabled on the first page so the system Back exits the app without persisting anything.
    BackHandler(enabled = page > 0 && !isFinishing) { viewModel.back() }

    val isOverview = page == 0
    val title =
        stringResource(if (isOverview) R.string.onboarding_overview_title else R.string.onboarding_connect_title)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_indicator, page + 1, ONBOARDING_PAGE_COUNT),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = { viewModel.finish(OnboardingExit.Photos) }, enabled = !isFinishing) {
                Text(stringResource(R.string.onboarding_action_skip))
            }
        }
        Spacer(Modifier.height(32.dp))
        Icon(
            imageVector = if (isOverview) Icons.Default.PhotoLibrary else Icons.Default.CloudUpload,
            contentDescription = null,
            modifier = Modifier.padding(8.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text =
                stringResource(
                    if (isOverview) R.string.onboarding_overview_body else R.string.onboarding_connect_body,
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { if (isOverview) viewModel.next() else viewModel.finish(OnboardingExit.Settings) },
            enabled = !isFinishing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (isOverview) R.string.onboarding_action_next else R.string.onboarding_action_connect_now,
                ),
            )
        }
    }
}
