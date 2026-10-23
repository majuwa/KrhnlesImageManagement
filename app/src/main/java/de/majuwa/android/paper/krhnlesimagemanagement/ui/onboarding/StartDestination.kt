package de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding

import de.majuwa.android.paper.krhnlesimagemanagement.model.CredentialState

enum class StartDestination(
    val route: String,
) {
    Onboarding(ROUTE_ONBOARDING),
    Photos(ROUTE_PHOTOS),
}

const val ROUTE_ONBOARDING = "onboarding"
const val ROUTE_PHOTOS = "photos"

/**
 * An existing user has a valid configuration, any stored server URL, or any credential state
 * other than [CredentialState.Missing] (so upgrade users with corrupted credentials are not
 * treated as first-time users).
 */
fun isExistingUser(
    configured: Boolean,
    storedServerUrl: String,
    credentialState: CredentialState,
): Boolean = configured || storedServerUrl.isNotBlank() || credentialState != CredentialState.Missing

fun resolveStartDestination(
    completed: Boolean,
    existingUser: Boolean,
): StartDestination = if (!completed && !existingUser) StartDestination.Onboarding else StartDestination.Photos
