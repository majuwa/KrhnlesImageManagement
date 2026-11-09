package de.majuwa.android.paper.krhnlesimagemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards design D2 of `cancel-running-upload`: the worker treats a stop as the user's cancel only
 * when WorkManager reports `STOP_REASON_CANCELLED_BY_APP`, which is only sound while the single
 * `cancelRunningUpload()` in `worker/UploadCancellation.kt` is the only code that cancels *upload*
 * work. `ExistingWorkPolicy.REPLACE` also stops running work with that reason and is forbidden
 * everywhere, as are the other WorkManager cancel APIs outside that file.
 *
 * Two narrow allowances exist for the separate, serialized download chain (`download-album-photos`,
 * decision confirmed by the user): `ExistingWorkPolicy.APPEND_OR_REPLACE` only in
 * `download/DownloadEnqueuer.kt` (it never stops running work; the unique name `photo_download` is
 * never used by upload work) and WorkManager's `createCancelPendingIntent` only in
 * `worker/DownloadNotifications.kt` (it cancels the download work by its own id; download work never
 * carries the `photo_upload` tag).
 *
 * A third narrow allowance serves the new-photos reminder (`new-photos-reminder`, decision confirmed by the
 * user): exactly one `cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME)`, only in
 * `reminder/NewPhotosReminderScheduler.kt` (a periodic work never ends on its own, so opting out needs a
 * cancel; the unique name is never used by upload work). That file must never reference upload work, and
 * `ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE` / `REPLACE` (they stop a running instance) are
 * forbidden everywhere.
 */
class NoUploadWorkCancelOutsideCancelPathRegressionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val moduleDir: File = File(System.getProperty("krhnles.moduleDir") ?: ".").absoluteFile

    @Test
    fun `production sources cancel work only in the cancel path`() {
        val mainDir = File(moduleDir, "src/main")
        assertTrue("Expected app module sources at ${mainDir.path}", mainDir.isDirectory)

        assertEquals(emptyList<String>(), violations(mainDir))
    }

    @Test
    fun `the scan finds a cancel call outside the cancel path in a copy of the tree`() {
        val copy = tmp.newFolder("main")
        File(moduleDir, "src/main").copyRecursively(copy)
        val planted = File(copy, "java/de/majuwa/android/paper/krhnlesimagemanagement/Planted.kt")
        planted.writeText("fun f(m: androidx.work.WorkManager) { m.cancelAllWorkByTag(\"photo_upload\") }\n")

        assertEquals(listOf("java/de/majuwa/android/paper/krhnlesimagemanagement/Planted.kt:1"), violations(copy))
    }

    @Test
    fun `the scan finds a replace policy in a copy of the tree`() {
        val copy = tmp.newFolder("main")
        File(moduleDir, "src/main").copyRecursively(copy)
        File(copy, "java/Planted.kt").writeText("val p = ExistingWorkPolicy.REPLACE\n")
        File(copy, "java/Planted2.kt").writeText("val p = ExistingWorkPolicy.APPEND_OR_REPLACE\n")

        assertEquals(listOf("java/Planted.kt:1", "java/Planted2.kt:1"), violations(copy))
    }

    @Test
    fun `replace is forbidden even in the files that have an allowance`() {
        val copy = tmp.newFolder("main")
        val enqueuer = File(copy, "java/de/x/download/DownloadEnqueuer.kt")
        checkNotNull(enqueuer.parentFile).mkdirs()
        enqueuer.writeText("val p = ExistingWorkPolicy.REPLACE\n")

        assertEquals(listOf("java/de/x/download/DownloadEnqueuer.kt:1"), violations(copy))
    }

    @Test
    fun `append or replace is allowed only in the download enqueuer`() {
        val copy = tmp.newFolder("main")
        val enqueuer = File(copy, "java/de/x/download/DownloadEnqueuer.kt")
        checkNotNull(enqueuer.parentFile).mkdirs()
        enqueuer.writeText("val p = ExistingWorkPolicy.APPEND_OR_REPLACE\n")
        assertEquals(emptyList<String>(), violations(copy))

        File(copy, "java/de/x/upload/UploadEnqueuer.kt")
            .also { checkNotNull(it.parentFile).mkdirs() }
            .writeText("val p = ExistingWorkPolicy.APPEND_OR_REPLACE\n")
        File(copy, "java/de/x/worker/DownloadNotifications.kt")
            .also { checkNotNull(it.parentFile).mkdirs() }
            .writeText("val p = ExistingWorkPolicy.APPEND_OR_REPLACE\n")

        assertEquals(
            listOf("java/de/x/upload/UploadEnqueuer.kt:1", "java/de/x/worker/DownloadNotifications.kt:1"),
            violations(copy),
        )
    }

    @Test
    fun `the cancel pending intent is allowed only in the download notifications`() {
        val copy = tmp.newFolder("main")
        val notifications = File(copy, "java/de/x/worker/DownloadNotifications.kt")
        checkNotNull(notifications.parentFile).mkdirs()
        notifications.writeText("val i = WorkManager.getInstance(c).createCancelPendingIntent(id)\n")
        assertEquals(emptyList<String>(), violations(copy))

        File(copy, "java/de/x/worker/UploadNotifications.kt")
            .also { checkNotNull(it.parentFile).mkdirs() }
            .writeText("val i = WorkManager.getInstance(c).createCancelPendingIntent(id)\n")
        File(copy, "java/de/x/download/DownloadEnqueuer.kt")
            .also { checkNotNull(it.parentFile).mkdirs() }
            .writeText("val i = WorkManager.getInstance(c).createCancelPendingIntent(id)\n")
        File(copy, "java/de/x/worker/UploadCancellation.kt")
            .also { checkNotNull(it.parentFile).mkdirs() }
            .writeText("val i = WorkManager.getInstance(c).createCancelPendingIntent(id)\n")

        assertEquals(
            listOf(
                "java/de/x/download/DownloadEnqueuer.kt:1",
                "java/de/x/worker/UploadCancellation.kt:1",
                "java/de/x/worker/UploadNotifications.kt:1",
            ),
            violations(copy),
        )
    }

    @Test
    fun `the scan allows the cancel path file and ignores comments`() {
        val copy = tmp.newFolder("main")
        val cancelPath = File(copy, "java/de/majuwa/android/paper/krhnlesimagemanagement/worker/UploadCancellation.kt")
        checkNotNull(cancelPath.parentFile).mkdirs()
        cancelPath.writeText("fun f(m: WorkManager) { m.cancelWorkById(id) }\n")
        File(copy, "java/Doc.kt").writeText("// cancelWorkById is only in the cancel path\n * cancelUniqueWork\n")

        assertEquals(emptyList<String>(), violations(copy))
    }

    @Test
    fun `the reminder scheduler may cancel its own unique work exactly once`() {
        val copy = tmp.newFolder("main")
        val scheduler = File(copy, "java/de/x/$REMINDER_SCHEDULER_FILE")
        checkNotNull(scheduler.parentFile).mkdirs()
        scheduler.writeText("fun c(m: WorkManager) { m.cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME) }\n")
        assertEquals(emptyList<String>(), violations(copy))

        scheduler.writeText(
            "fun c(m: WorkManager) { m.cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME) }\n" +
                "fun d(m: WorkManager) { m.cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME) }\n",
        )
        assertEquals(listOf("java/de/x/$REMINDER_SCHEDULER_FILE:2"), violations(copy))
    }

    @Test
    fun `other cancel apis or arguments in the reminder scheduler are violations`() {
        val copy = tmp.newFolder("main")
        val scheduler = File(copy, "java/de/x/$REMINDER_SCHEDULER_FILE")
        checkNotNull(scheduler.parentFile).mkdirs()
        scheduler.writeText(
            "fun a(m: WorkManager) { m.cancelAllWorkByTag(\"photo_upload\") }\n" +
                "fun b(m: WorkManager) { m.cancelUniqueWork(\"photo_download\") }\n" +
                "fun c(m: WorkManager) { m.cancelAllWork() }\n" +
                "fun d(m: WorkManager) { m.cancelWorkById(id) }\n",
        )
        assertEquals(
            (1..4).map { "java/de/x/$REMINDER_SCHEDULER_FILE:$it" },
            violations(copy),
        )
    }

    @Test
    fun `the reminder unique work cancel is a violation in any other file`() {
        val copy = tmp.newFolder("main")
        File(copy, "java/de/x/reminder/NewPhotosReminderWorker.kt")
            .also { checkNotNull(it.parentFile).mkdirs() }
            .writeText("fun c(m: WorkManager) { m.cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME) }\n")
        File(copy, "java/de/x/worker/UploadWorker.kt")
            .also { checkNotNull(it.parentFile).mkdirs() }
            .writeText("fun c(m: WorkManager) { m.cancelUniqueWork(NEW_PHOTOS_REMINDER_WORK_NAME) }\n")

        assertEquals(
            listOf("java/de/x/reminder/NewPhotosReminderWorker.kt:1", "java/de/x/worker/UploadWorker.kt:1"),
            violations(copy),
        )
    }

    @Test
    fun `periodic cancel and re-enqueue and replace policies are forbidden everywhere`() {
        val copy = tmp.newFolder("main")
        File(copy, "java").mkdirs()
        File(copy, "java/A.kt").writeText("val p = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE\n")
        File(copy, "java/B.kt").writeText("val p = ExistingPeriodicWorkPolicy.REPLACE\n")
        val scheduler = File(copy, "java/de/x/$REMINDER_SCHEDULER_FILE")
        checkNotNull(scheduler.parentFile).mkdirs()
        scheduler.writeText(
            "val p = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE\nval q = ExistingPeriodicWorkPolicy.UPDATE\n",
        )

        assertEquals(
            listOf("java/A.kt:1", "java/B.kt:1", "java/de/x/$REMINDER_SCHEDULER_FILE:1"),
            violations(copy),
        )
    }

    @Test
    fun `a reference to upload work in the reminder scheduler is found in a copy of the tree`() {
        val copy = tmp.newFolder("main")
        File(moduleDir, "src/main").copyRecursively(copy)
        val scheduler = copy.walkTopDown().single { it.invariantSeparatorsPath.endsWith("/$REMINDER_SCHEDULER_FILE") }
        scheduler.appendText("\nval tag = \"photo_upload\"\n")

        assertEquals(listOf("NewPhotosReminderScheduler.kt"), uploadWorkReferences(copy))
    }

    @Test
    fun `the files with a cancel allowance never touch upload work`() {
        assertEquals(emptyList<String>(), uploadWorkReferences(File(moduleDir, "src/main")))
    }

    @Test
    fun `a reference to upload work in an allowed file is found in a copy of the tree`() {
        val copy = tmp.newFolder("main")
        File(moduleDir, "src/main").copyRecursively(copy)
        val enqueuer = copy.walkTopDown().single { it.invariantSeparatorsPath.endsWith("/$DOWNLOAD_ENQUEUER_FILE") }
        enqueuer.appendText("\nval planted = UploadWorker::class\n")
        val notifications =
            copy.walkTopDown().single { it.invariantSeparatorsPath.endsWith("/$DOWNLOAD_NOTIFICATIONS_FILE") }
        notifications.appendText("\nval tag = \"photo_upload\"\n")

        assertEquals(listOf("DownloadEnqueuer.kt", "DownloadNotifications.kt"), uploadWorkReferences(copy))
    }

    private companion object {
        const val CANCEL_PATH_FILE = "worker/UploadCancellation.kt"
        const val DOWNLOAD_ENQUEUER_FILE = "download/DownloadEnqueuer.kt"
        const val DOWNLOAD_NOTIFICATIONS_FILE = "worker/DownloadNotifications.kt"
        const val REMINDER_SCHEDULER_FILE = "reminder/NewPhotosReminderScheduler.kt"

        val cancelApis = Regex("""\b(cancelWorkById|cancelAllWorkByTag|cancelUniqueWork|cancelAllWork)\b""")
        val cancelIntentApi = Regex("""\bcreateCancelPendingIntent\b""")
        val replacePolicy = Regex("""ExistingWorkPolicy\.REPLACE\b""")
        val periodicForbiddenPolicy = Regex("""ExistingPeriodicWorkPolicy\.(CANCEL_AND_REENQUEUE|REPLACE)\b""")
        val reminderCancel = Regex("""\bcancelUniqueWork\(NEW_PHOTOS_REMINDER_WORK_NAME\)""")
        val appendOrReplacePolicy = Regex("""ExistingWorkPolicy\.APPEND_OR_REPLACE\b""")

        fun violations(mainDir: File): List<String> =
            mainDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val path = file.invariantSeparatorsPath
                    val inCancelPath = path.endsWith("/$CANCEL_PATH_FILE")
                    val inEnqueuer = path.endsWith("/$DOWNLOAD_ENQUEUER_FILE")
                    val inDownloadNotifications = path.endsWith("/$DOWNLOAD_NOTIFICATIONS_FILE")
                    val inReminderScheduler = path.endsWith("/$REMINDER_SCHEDULER_FILE")
                    var reminderCancels = 0
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> isCode(line) }
                        .filter { (_, line) ->
                            val code = line.substringBefore("//")
                            // The one allowed reminder cancel is removed first; anything else on the line,
                            // or a second one in the file, is still a violation.
                            val remaining =
                                if (inReminderScheduler && reminderCancel.containsMatchIn(code) &&
                                    ++reminderCancels == 1
                                ) {
                                    reminderCancel.replace(code, "")
                                } else {
                                    code
                                }
                            replacePolicy.containsMatchIn(code) ||
                                periodicForbiddenPolicy.containsMatchIn(code) ||
                                (!inEnqueuer && appendOrReplacePolicy.containsMatchIn(code)) ||
                                (!inCancelPath && cancelApis.containsMatchIn(remaining)) ||
                                (!inDownloadNotifications && cancelIntentApi.containsMatchIn(code))
                        }.map { (index, _) -> "${file.relativeTo(mainDir).invariantSeparatorsPath}:${index + 1}" }
                }.sorted()
                .toList()

        /** Names of the allowed download files that mention upload work (tag, worker or request builder). */
        fun uploadWorkReferences(mainDir: File): List<String> {
            val allowed = listOf(DOWNLOAD_ENQUEUER_FILE, DOWNLOAD_NOTIFICATIONS_FILE, REMINDER_SCHEDULER_FILE)
            val files =
                mainDir
                    .walkTopDown()
                    .filter { file -> file.isFile && allowed.any { file.invariantSeparatorsPath.endsWith("/$it") } }
                    .toList()
            assertEquals("all allowed files exist", allowed.size, files.size)
            return files
                .filter { file ->
                    file.readLines().filter(::isCode).any {
                        val code = it.substringBefore("//")
                        "photo_upload" in code || "UploadWorker" in code || "uploadWorkRequest" in code
                    }
                }.map { it.name }
                .sorted()
        }

        fun isCode(line: String): Boolean {
            val trimmed = line.trimStart()
            return !(trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
        }
    }
}
