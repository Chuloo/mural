package chat.mural.ondevice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import chat.mural.R
import chat.mural.core.OnDeviceReadiness
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the active On-Device Model: metadata in SharedPreferences, the model file in
 * noBackupFilesDir, and one [RuntimeSession] behind the Model Runtime seam (LiteRT-LM by
 * default, injectable for tests). Main-confined like HostedReadinessController: every change
 * publishes an [OnDeviceReadiness], so the Conversation Provider never guesses whether it can
 * start and never substitutes another provider when the model is missing.
 */
class OnDeviceModelController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: ModelRuntime = LiteRtModelRuntime(),
    private val changed: (OnDeviceReadiness) -> Unit = {},
) {
    private val preferences = context.getSharedPreferences("mural_on_device_models", Context.MODE_PRIVATE)
    private val store = OnDeviceModelStore(preferences.getString(KEY_MODELS, null)) { json ->
        preferences.edit().putString(KEY_MODELS, json).apply()
    }
    private val directory get() = File(context.noBackupFilesDir, MODELS_DIRECTORY)
    private var session: RuntimeSession? = null
    private var loadedName: String? = null
    private var job: Job? = null

    fun imported() = store.models.map { it.name }

    /** Publishes readiness from the imported metadata without loading a model. */
    fun refresh() { publish(current()) }

    /** Loads the active model, if one is imported and not held yet. */
    fun initialize() {
        refresh()
        val model = store.active() ?: return
        if (session != null && loadedName == model.name) return
        job?.cancel()
        job = scope.launch { load(model) }
    }

    /** Switches the active model among the imported ones and loads it. */
    fun select(name: String) {
        if (store.select(name)) initialize()
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

    /** The loaded session for a Helper. Readiness gates every Helper, so a missing session is
     * an honest refusal rather than a silent multi-gigabyte reload mid-reply. */
    fun requireSession(): RuntimeSession =
        session ?: throw ModelRuntimeException(context.getString(R.string.error_on_device_model_needed))

    fun close() {
        job?.cancel()
        closeSession()
    }

    private suspend fun load(model: OnDeviceModel) {
        publish(current(preparing = true))
        try {
            val loaded = runtime.load(File(directory, model.name).absolutePath)
            closeSession()
            session = loaded; loadedName = model.name
            publish(current())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            closeSession()
            publish(current(failure = context.getString(R.string.error_on_device_model_failed)))
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
        OnDeviceReadiness(store.active()?.name, session != null && loadedName == store.active()?.name, preparing, failure)

    private fun publish(next: OnDeviceReadiness) = changed(next)

    companion object {
        private const val KEY_MODELS = "models"
        private const val MODELS_DIRECTORY = "models"
        private const val PARTIAL_SUFFIX = ".importing"
    }
}
