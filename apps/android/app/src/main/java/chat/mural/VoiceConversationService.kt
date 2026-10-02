package chat.mural

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Keeps one user-started voice session alive; process death never starts another paid call. */
class VoiceConversationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var ownedSessionID: String? = null

    override fun onCreate() {
        super.onCreate()
        val id = sessionID
        if (id == null) { stopSelf(); return }
        ownedSessionID = id
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.voice_notification_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val end = PendingIntent.getService(this, 0, Intent(this, VoiceConversationService::class.java)
                .setAction(END).putExtra(SESSION, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_voice_notification)
                .setContentTitle(getString(R.string.voice_notification_title))
                .setContentText(getString(R.string.voice_notification_detail))
                .setContentIntent(open).setOngoing(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, getString(R.string.voice_notification_end), end).build()
            val types = if (Build.VERSION.SDK_INT >= 30)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            ServiceCompat.startForeground(this, NOTIFICATION, notification, types)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mural:voice").apply {
                    // The app's maximum session is 60 minutes. This is a final bound, not its timer.
                    acquire(65 * 60 * 1000L)
                }
        } catch (_: Exception) {
            if (sessionID == id) endSession?.invoke()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == END && intent.getStringExtra(SESSION) == sessionID) endSession?.invoke()
        if (sessionID == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (sessionID == ownedSessionID) endSession?.invoke()
        super.onTaskRemoved(rootIntent)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        val end = if (sessionID == ownedSessionID) endSession else null
        if (sessionID == ownedSessionID) { sessionID = null; endSession = null }
        end?.invoke()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice-conversation"
        private const val NOTIFICATION = 139
        private const val END = "chat.mural.END_VOICE"
        private const val SESSION = "session"
        private var sessionID: String? = null
        private var endSession: (() -> Unit)? = null

        fun holds(id: String?): Boolean = id != null && sessionID == id
        fun start(context: Context, id: String, end: () -> Unit) {
            check(sessionID == null) { "A voice conversation already owns the service" }
            sessionID = id; endSession = end
            try { ContextCompat.startForegroundService(context, Intent(context, VoiceConversationService::class.java)) }
            catch (error: Exception) { sessionID = null; endSession = null; throw error }
        }
        fun stop(context: Context, id: String?) {
            if (!holds(id)) return
            sessionID = null; endSession = null
            context.stopService(Intent(context, VoiceConversationService::class.java))
        }
    }
}
