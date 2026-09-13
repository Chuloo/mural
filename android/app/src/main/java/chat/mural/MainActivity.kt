package chat.mural

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import chat.mural.core.ArchiveCodec
import chat.mural.core.MandarinPinyin
import chat.mural.network.IcuHanReader
import chat.mural.ui.MuralApp
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MuralViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MandarinPinyin.reader = IcuHanReader()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            var microphoneMessage by rememberSaveable { mutableStateOf<String?>(null) }
            var microphonePermanentlyDenied by rememberSaveable { mutableStateOf(false) }
            var requestedMicrophone by rememberSaveable { mutableStateOf(false) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    microphoneMessage = null
                    microphonePermanentlyDenied = false
                    vm.start()
                } else {
                    val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    microphonePermanentlyDenied = requestedMicrophone && !canAskAgain
                    microphoneMessage = if (microphonePermanentlyDenied) {
                        getString(R.string.notice_microphone_blocked)
                    } else {
                        getString(R.string.notice_microphone_permission_needed)
                    }
                }
            }

            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) lifecycleScope.launch {
                    runCatching {
                        val encoded = vm.exportData()
                        withContext(Dispatchers.IO) { writeBoundedUtf8(uri, encoded) }
                    }.onSuccess {
                        Toast.makeText(this@MainActivity, getString(R.string.settings_backup_exported_toast), Toast.LENGTH_SHORT).show()
                    }.onFailure { showFailure(it) }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) lifecycleScope.launch {
                    runCatching {
                        val encoded = withContext(Dispatchers.IO) { readBoundedUtf8(uri) }
                        vm.importData(encoded)
                    }.onSuccess { imported ->
                        if (imported) Toast.makeText(this@MainActivity, getString(R.string.settings_backup_imported_toast), Toast.LENGTH_SHORT).show()
                    }.onFailure { showFailure(it) }
                }
            }

            MuralApp(
                vm = vm,
                microphoneMessage = microphoneMessage,
                onRequestMicrophone = {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        microphoneMessage = null
                        vm.start()
                    } else {
                        requestedMicrophone = true
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onOpenAppSettings = if (microphonePermanentlyDenied) ({ openAppSettings() }) else null,
                onExport = { exportLauncher.launch("Mural-learning-backup.json") },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            )
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) vm.background()
        super.onStop()
    }

    private fun readBoundedUtf8(uri: Uri): String {
        contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { getString(R.string.error_backup_too_large) }
                output.write(buffer, 0, count)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
        error(getString(R.string.error_file_open_failed))
    }

    private fun writeBoundedUtf8(uri: Uri, text: String) {
        val data = text.toByteArray(StandardCharsets.UTF_8)
        require(data.size <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { getString(R.string.error_backup_too_large) }
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
            ?: error(getString(R.string.error_file_open_failed))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
        )
    }

    private fun showFailure(error: Throwable) {
        Toast.makeText(this, error.localizedMessage ?: getString(R.string.common_operation_failed), Toast.LENGTH_LONG).show()
    }
}
