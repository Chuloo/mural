package chat.mural.ondevice

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.annotation.StringRes
import chat.mural.R
import chat.mural.core.DownloadProgress
import chat.mural.core.OnDeviceReadiness
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the active On-Device Model: metadata in SharedPreferences, the model file in
 * noBackupFilesDir, and one [RuntimeSession] behind the Model Runtime seam. Which runtime runs it
 * follows the chosen model: a `.litertlm` file runs on LiteRT-LM, the zero-download system model on
 * AICore (both injectable for tests). One model is active for every language and every Helper.
 * Models arrive from the curated [OnDeviceModelCatalogue], a pasted direct link, or the document
 * picker, and leave through [delete]. Main-confined like HostedReadinessController: every change
 * publishes an [OnDeviceReadiness], so the Conversation Provider never guesses whether it can
 * start and never substitutes another provider when the model is missing or still downloading.
 */
class OnDeviceModelController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: ModelRuntime = LiteRtModelRuntime(),
    private val aicore: AICoreModelRuntime = AICoreModelRuntime(),
    private val changed: (OnDeviceReadiness) -> Unit = {},
) {
    private val preferences = context.getSharedPreferences("mural_on_device_models", Context.MODE_PRIVATE)
    private val store = OnDeviceModelStore(preferences.getString(KEY_MODELS, null)) { json ->
        preferences.edit().putString(KEY_MODELS, json).apply()
    }
    private val directory get() = File(context.noBackupFilesDir, MODELS_DIRECTORY)
    private val downloader by lazy { OnDeviceModelDownloader(directory) }
    private var session: RuntimeSession? = null
    private var loadedName: String? = null
    private var job: Job? = null
    private var downloadJob: Job? = null
    /** Written from the transfer thread, read on main; conflation is the progress throttle. */
    private val progress = MutableStateFlow<DownloadProgress?>(null)
    private var progressWatcher: Job? = null

    /** The models on this phone, curated downloads and imported files alike. */
    fun stored(): List<OnDeviceModel> = store.models

    /** Whether this phone offers the zero-download system model: the AICore device probe. */
    suspend fun systemModelAvailable(): Boolean = aicore.available()

    /** Publishes readiness from the imported metadata without loading a model. */
    fun refresh() { publish(current()) }

    /** Loads the active model, if one is chosen and not held yet. */
    fun initialize() {
        refresh()
        val id = store.activeID() ?: return
        if (session != null && loadedName == id) return
        job?.cancel()
        job = scope.launch {
            val system = OnDeviceModelCatalogue.byID(id)?.takeIf { it.runtime == ModelRuntimeKind.AICORE }
            if (system != null) loadSystem(system) else store.active()?.let { load(it) }
        }
    }

    /** Switches the active model — an imported file, or the zero-download system model — and loads it. */
    fun select(name: String) {
        val system = OnDeviceModelCatalogue.byID(name)?.takeIf { it.runtime == ModelRuntimeKind.AICORE }
        if (system == null) { if (store.select(name)) initialize(); return }
        job?.cancel()
        // One tap: the system model has no download to confirm and no storage to plan.
        job = scope.launch {
            store.selectSystem(system.id)
            loadSystem(system)
        }
    }

    /** Copies a learner-chosen `.litertlm` file into app storage and makes it the active model. */
    fun importModel(uri: Uri) {
        job?.cancel()
        job = scope.launch {
            publish(current(preparing = true))
            try {
                load(copy(uri))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(current(failure = context.getString(R.string.error_on_device_import_failed))) }
        }
    }

    /** The warnings for a catalogue model, decided before its multi-gigabyte transfer starts. */
    fun plan(model: CataloguedModel): DownloadPlan = plan(model.fileName, model.sizeBytes, model.minDeviceMemoryGb)

    /** Downloads a curated catalogue model, then makes it the active model. */
    fun download(model: CataloguedModel) {
        val url = model.downloadURL
        val fileName = model.fileName
        // A zero-download Model Runtime (AICore) has neither, and downloads nothing here.
        if (url == null || fileName == null) return
        begin(url, fileName, model.sizeBytes, model.minDeviceMemoryGb)
    }

    /** Downloads a pasted direct HTTPS `.litertlm` link through the same flow. */
    fun download(pastedURL: String) {
        val url = OnDeviceModelURLs.normalize(pastedURL)
        // shortcut: a pasted link announces neither its size nor a memory floor, so only the
        // free-storage guard applies to it; upgrade if a curated floor is ever worth a probe.
        begin(url, OnDeviceModelURLs.fileName(url), 0L, 0)
    }

    /** Stops an in-flight download at once; the partial file stays so the next attempt resumes. */
    fun cancelDownload() { downloadJob?.cancel() }

    /** Deletes a model to reclaim its storage, closing its session first when it is the active one. */
    fun delete(name: String) {
        if (progress.value?.fileName == name) cancelDownload()
        job?.cancel()
        job = scope.launch {
            if (loadedName == name) closeSession()
            withContext(Dispatchers.IO) {
                File(directory, name).delete()
                File(directory, name + OnDeviceModelDownloader.PARTIAL_SUFFIX).delete()
            }
            store.delete(name)
            publish(current())
        }
    }

    /** The loaded session for a Helper. Readiness gates every Helper, so a missing session is
     * an honest refusal rather than a silent multi-gigabyte reload mid-reply. */
    fun requireSession(): RuntimeSession =
        session ?: throw ModelRuntimeException(context.getString(R.string.error_on_device_model_needed))

    fun close() {
        job?.cancel()
        downloadJob?.cancel()
        progressWatcher?.cancel()
        closeSession()
    }

    private fun begin(url: String, fileName: String, sizeBytes: Long, minDeviceMemoryGb: Int) {
        if (downloadJob?.isActive == true) return
        // shortcut: the transfer lives in the ViewModel scope, so leaving the app can stop it; the
        // partial file resumes on the next tap. Upgrade to a foreground service if learners lose
        // multi-gigabyte downloads to process death often.
        val plan = plan(fileName, sizeBytes, minDeviceMemoryGb)
        if (plan.refused) { publish(current(failure = storageMessage(plan.shortfallBytes))); return }
        watchProgress()
        downloadJob = scope.launch {
            progress.value = DownloadProgress(fileName, partialBytes(fileName), sizeBytes)
            try {
                val downloaded = downloader.download(url, fileName, sizeBytes) { received, total ->
                    progress.value = DownloadProgress(fileName, received, total)
                }
                progress.value = null
                load(store.import(fileName, downloaded.length()))
            } catch (cancelled: CancellationException) { progress.value = null; throw cancelled }
            catch (error: Exception) { progress.value = null; publish(current(failure = downloadMessage(error))) }
        }
    }

    /** Progress arrives from the transfer thread; publishing stays on main through one conflated flow. */
    private fun watchProgress() {
        if (progressWatcher?.isActive == true) return
        progressWatcher = scope.launch { progress.collect { publish(current()) } }
    }

    private fun plan(fileName: String?, sizeBytes: Long, minDeviceMemoryGb: Int) = OnDeviceDownloadPlanner.plan(
        // A resumed download plans only the bytes that are still missing.
        (sizeBytes - partialBytes(fileName)).coerceAtLeast(0L),
        context.noBackupFilesDir.usableSpace, deviceMemoryBytes(), minDeviceMemoryGb)

    private fun partialBytes(fileName: String?) =
        fileName?.let { File(directory, it + OnDeviceModelDownloader.PARTIAL_SUFFIX).length() } ?: 0L

    /** The phone's memory against a model's floor. Fails open: no warning beats a wrong refusal. */
    private fun deviceMemoryBytes(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        // advertisedMem is the marketed size; totalMem under-reports a 12 GB phone below E4B's floor.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.advertisedMem else info.totalMem
    }

    private fun downloadMessage(error: Exception): String = when ((error as? ModelDownloadException)?.kind) {
        ModelDownloadException.Kind.OUT_OF_STORAGE -> storageMessage(error.shortfallBytes)
        ModelDownloadException.Kind.REFUSED -> context.getString(R.string.error_on_device_download_refused)
        else -> context.getString(R.string.error_on_device_download_interrupted)
    }

    private fun storageMessage(shortfallBytes: Long) = context.getString(R.string.error_on_device_storage_short,
        Formatter.formatFileSize(context, shortfallBytes))

    private suspend fun load(model: OnDeviceModel) = start(model.name, R.string.error_on_device_model_failed) {
        runtime.load(File(directory, model.name).absolutePath)
    }

    /** The zero-download system model: AICore serves it, so there is no file and no download. */
    private suspend fun loadSystem(model: CataloguedModel) = start(model.id, R.string.error_on_device_aicore_unavailable) {
        aicore.load(model.id)
    }

    /** Holds one loaded session, or reports why the learner's phone could not start it. */
    private suspend fun start(id: String, @StringRes failure: Int, open: suspend () -> RuntimeSession) {
        publish(current(preparing = true))
        try {
            val loaded = open()
            closeSession()
            session = loaded; loadedName = id
            publish(current())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            closeSession()
            publish(current(failure = context.getString(failure)))
        }
    }

    /** A picked Uri is not a file path: the model lives in app storage the OS never backs up. */
    private suspend fun copy(uri: Uri): OnDeviceModel {
        val copied = withContext(Dispatchers.IO) {
            val name = OnDeviceModelStore.fileName(displayName(uri))
            val models = directory.apply { mkdirs() }
            models.listFiles { file -> file.name.endsWith(PARTIAL_SUFFIX) }?.forEach { it.delete() }
            // Copy beside the target and rename, so a failed import cannot corrupt the active model.
            val partial = File(models, name + PARTIAL_SUFFIX)
            context.contentResolver.openInputStream(uri)?.use { source -> partial.outputStream().use { source.copyTo(it) } }
                ?: throw ModelRuntimeException(context.getString(R.string.error_on_device_import_failed))
            val target = File(models, name)
            if (!partial.renameTo(target)) {
                partial.delete()
                throw ModelRuntimeException(context.getString(R.string.error_on_device_import_failed))
            }
            name to target.length()
        }
        return store.import(copied.first, copied.second)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    private fun closeSession() {
        runCatching { session?.close() }
        session = null; loadedName = null
    }

    private fun current(preparing: Boolean = false, failure: String? = null) =
        OnDeviceReadiness(store.activeID(), session != null && loadedName == store.activeID(),
            preparing, failure, progress.value)

    private fun publish(next: OnDeviceReadiness) = changed(next)

    companion object {
        private const val KEY_MODELS = "models"
        private const val MODELS_DIRECTORY = "models"
        private const val PARTIAL_SUFFIX = ".importing"
    }
}
