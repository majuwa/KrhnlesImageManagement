package de.majuwa.android.paper.krhnlesimagemanagement.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.majuwa.android.paper.krhnlesimagemanagement.R
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateInfo
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.groupedHex
import java.text.DateFormat
import java.util.Date

internal const val TAG_CERTIFICATE_REVIEW = "certificate_review"
internal const val TAG_TRUST_CERTIFICATE = "trust_certificate"
internal const val TAG_CONFIRM_TRUST = "confirm_trust"
internal const val TAG_STOP_TRUSTING = "stop_trusting"

private fun warningRes(review: CertificateReview): Int =
    if (review.changed) R.string.certificate_review_changed_warning else R.string.certificate_review_warning

private fun originText(certificate: ServerCertificate): String = certificate.origin.serialize()

/** Review of a captured certificate with the "Trust this certificate" action and its confirmation dialog. */
@Composable
internal fun CertificateReviewCard(
    review: CertificateReview,
    trustInProgress: Boolean,
    saveFailed: Boolean,
    onTrust: () -> Unit,
) {
    val certificate = review.certificate
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val valid = certificate.isValidAt(System.currentTimeMillis())
    val trustable = valid && certificate.hasStrongKey
    // Keyed on the certificate: a dialog opened for one certificate never confirms another one.
    var showDialog by rememberSaveable(certificate.sha256.hex()) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().testTag(TAG_CERTIFICATE_REVIEW),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.certificate_review_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LabeledValue(stringResource(R.string.certificate_review_fingerprint), certificate.sha256.groupedHex())
            review.previousSha256?.let {
                LabeledValue(stringResource(R.string.certificate_review_previous_fingerprint), it.groupedHex())
            }
            LabeledValue(stringResource(R.string.certificate_review_subject), certificate.subject)
            LabeledValue(stringResource(R.string.certificate_review_issuer), certificate.issuer)
            Text(
                if (certificate.keyBits > 0) {
                    stringResource(R.string.certificate_review_key, certificate.keyAlgorithm, certificate.keyBits)
                } else {
                    stringResource(R.string.certificate_review_key_unknown, certificate.keyAlgorithm)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.certificate_review_validity,
                    dateFormat.format(Date(certificate.notBeforeMillis)),
                    dateFormat.format(Date(certificate.notAfterMillis)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.certificate_review_origin, originText(certificate)),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    warningRes(review),
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!valid) {
                Text(
                    stringResource(R.string.certificate_review_not_valid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (valid && !certificate.hasStrongKey) {
                Text(
                    stringResource(R.string.certificate_review_weak_key),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (saveFailed) {
                Text(
                    stringResource(R.string.certificate_trust_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (trustable) {
                Button(
                    onClick = { showDialog = true },
                    enabled = !trustInProgress,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_TRUST_CERTIFICATE),
                ) { Text(stringResource(R.string.action_trust_certificate)) }
            }
        }
    }

    if (showDialog && trustable) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.certificate_trust_dialog_title)) },
            text = {
                Column {
                    val previous = review.previousSha256
                    Text(
                        if (previous != null) {
                            stringResource(
                                R.string.certificate_trust_dialog_changed_body,
                                previous.groupedHex(),
                                certificate.sha256.groupedHex(),
                                originText(certificate),
                            )
                        } else {
                            stringResource(
                                R.string.certificate_trust_dialog_body,
                                certificate.sha256.groupedHex(),
                                originText(certificate),
                            )
                        },
                    )
                    Text(
                        stringResource(
                            warningRes(review),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        onTrust()
                    },
                    modifier = Modifier.testTag(TAG_CONFIRM_TRUST),
                ) { Text(stringResource(R.string.action_trust_certificate)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun LabeledValue(
    label: String,
    value: String,
) {
    Text(label, style = MaterialTheme.typography.labelSmall)
    Text(value, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(4.dp))
}

/** The trusted certificate of the configured origin with the "Stop trusting" action. */
@Composable
internal fun TrustedCertificateRow(
    info: TrustedCertificateInfo,
    enabled: Boolean,
    onStopTrusting: () -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.certificate_trusted_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.certificate_trusted_until,
                    info.certificate.sha256.groupedHex(),
                    dateFormat.format(Date(info.certificate.notAfterMillis)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onStopTrusting,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(TAG_STOP_TRUSTING),
            ) { Text(stringResource(R.string.action_stop_trusting)) }
        }
    }
}
