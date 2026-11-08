package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import de.majuwa.android.paper.krhnlesimagemanagement.download.DownloadRequester
import kotlinx.coroutines.Job

/** Records every download request as `(albumHref, hrefs)`. */
class FakeDownloadRequester : DownloadRequester {
    val requests = mutableListOf<Pair<String, List<String>>>()

    override fun requestDownload(
        albumHref: String,
        hrefs: List<String>,
    ): Job? {
        requests += albumHref to hrefs
        return null
    }
}
