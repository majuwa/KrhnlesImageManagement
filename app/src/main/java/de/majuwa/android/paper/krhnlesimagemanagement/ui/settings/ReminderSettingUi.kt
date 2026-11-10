package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.model.NewPhotosReminderSettings
import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import de.majuwa.android.paper.krhnlesimagemanagement.reminder.canPostReminders

/**
 * The "Remind me about new photos" switch (one switch-role node) with its interval choice (a single-choice
 * group, shown only while on). Turning it on without the notification permission requests it; a denial keeps
 * the reminder on and shows a hint with a link to the notification settings, re-evaluated when Settings
 * resumes and after the permission result.
 */
@Composable
internal fun NewPhotosReminderSetting(
    settings: NewPhotosReminderSettings,
    onChange: (enabled: Boolean, interval: ReminderInterval) -> Unit,
) {
    val context = LocalContext.current
    var canPost by remember { mutableStateOf(context.canPostReminders()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPost = context.canPostReminders() }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            canPost = context.canPostReminders()
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("newPhotosReminderRow")
                .minimumInteractiveComponentSize()
                .toggleable(
                    value = settings.enabled,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        onChange(enabled, settings.interval)
                        if (enabled && !context.hasNotificationPermission()) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.label_new_photos_reminder),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.hint_new_photos_reminder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = settings.enabled, onCheckedChange = null)
    }

    if (settings.enabled) {
        ReminderIntervalChoice(selected = settings.interval) { onChange(true, it) }
        if (!canPost) NotificationsOffHint(context)
    }
}

@Composable
private fun ReminderIntervalChoice(
    selected: ReminderInterval,
    onSelect: (ReminderInterval) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ReminderInterval.entries.forEach { interval ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("reminderInterval_${interval.name}")
                        .minimumInteractiveComponentSize()
                        .selectable(
                            selected = interval == selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(interval) },
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = interval == selected, onClick = null)
                Text(
                    text = stringResource(interval.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun NotificationsOffHint(context: Context) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.reminder_notifications_off_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    TextButton(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    ) {
        Text(stringResource(R.string.action_open_notification_settings))
    }
}

private fun ReminderInterval.labelRes(): Int =
    when (this) {
        ReminderInterval.Weekly -> R.string.reminder_interval_weekly
        ReminderInterval.EveryTwoWeeks -> R.string.reminder_interval_every_two_weeks
        ReminderInterval.Monthly -> R.string.reminder_interval_monthly
    }

private fun Context.hasNotificationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
