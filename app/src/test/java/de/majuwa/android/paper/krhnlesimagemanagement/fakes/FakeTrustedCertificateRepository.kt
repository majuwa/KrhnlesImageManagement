package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.ServerCertificate
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateInfo
import de.majuwa.android.paper.krhnlesimagemanagement.data.tls.TrustedCertificateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class FakeTrustedCertificateRepository(
    initial: TrustedCertificateInfo? = null,
) : TrustedCertificateRepository {
    private val state = MutableStateFlow(initial)

    /** When set, reading never emits (to test the bounded read). */
    var hangRead: Boolean = false

    override val trustedCertificate: Flow<TrustedCertificateInfo?> =
        flow {
            if (hangRead) kotlinx.coroutines.awaitCancellation()
            emitAll(state)
        }

    /** When non-null, [trust] / [clear] throw it instead of storing anything. */
    var failure: Throwable? = null

    /** When set, [trust] never returns (to test the write timeout). */
    var hang: Boolean = false

    var clearCalls = 0
        private set

    val current: TrustedCertificateInfo? get() = state.value

    override suspend fun trust(certificate: ServerCertificate) {
        failure?.let { throw it }
        if (hang) kotlinx.coroutines.awaitCancellation()
        state.value = TrustedCertificateInfo(certificate, 1L)
    }

    override suspend fun clear() {
        clearCalls++
        failure?.let { throw it }
        state.value = null
    }
}
