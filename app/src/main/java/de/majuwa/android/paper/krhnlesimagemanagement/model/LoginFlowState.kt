package de.majuwa.android.paper.krhnlesimagemanagement.model

sealed class LoginFlowState {
    data class WaitingForBrowser(
        val loginUrl: String,
    ) : LoginFlowState()

    data class Authenticated(
        val server: String,
        val loginName: String,
        val appPassword: String,
    ) : LoginFlowState()

    data class Failed(
        val message: String,
    ) : LoginFlowState()
}
