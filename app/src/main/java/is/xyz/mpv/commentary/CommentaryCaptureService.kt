package `is`.xyz.mpv.commentary
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.*
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

class CommentaryCaptureService : Service() {
    companion object {
        const val START = "is.xyz.mpv.commentary.START"
        const val STOP = "is.xyz.mpv.commentary.STOP"
        const val ANCHOR = "is.xyz.mpv.commentary.ANCHOR"
        const val PREFS = "morning_show_capture"
        const val CHANNEL = "morning_show_recording"
    }
    private var recorder: MediaRecorder? = null
    private var events: File? = null
    private var sync: CentralSync? = null
    private var audio: File? = null
    private var session = ""
    private var speaker = ""
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(CHANNEL, "Morning Show microphone",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (recorder == null) begin(intent)
            ANCHOR -> if (recorder != null) log("anchor",
                intent.getStringExtra("note") ?: "manual")
            STOP -> finishRecording()
        }
        return START_NOT_STICKY
    }
    private fun store(recording: Boolean, status: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("recording", recording).putString("status", status)
            .putString("file", audio?.absolutePath ?: "")
            .putString("session", session).putString("speaker", speaker).apply()
    }
    private fun clean(v: String) =
        v.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "unknown" }
    private fun begin(intent: Intent) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            store(false, "Microphone permission needed"); stopSelf(); return
        }
        session = clean(intent.getStringExtra("session") ?: "morning")
        speaker = clean(intent.getStringExtra("speaker") ?: "Unknown")
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir,
            "MorningShow/$session")
        if (!dir.exists() && !dir.mkdirs()) {
            store(false, "Cannot create audio directory"); stopSelf(); return
        }
        val suffix = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
        val stem = "${speaker}_${suffix}"
        audio = File(dir, "$stem.m4a")
        events = File(dir, "$stem.events.jsonl")
        val meta = JSONObject().put("schema", "morning-show-audio/1")
            .put("session_id", session).put("speaker", speaker)
            .put("audio_filename", audio!!.name)
            .put("events_filename", events!!.name)
            .put("capture_video", false).put("video_adapter", "none")
        File(dir, "$stem.session.json").writeText(meta.toString(2))
        try {
            startForeground(5102, notice())
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
                else MediaRecorder()
            recorder = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(audio!!.absolutePath)
            r.prepare(); r.start()
            log("recording_started", "explicit")
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            sync = CentralSync(prefs.getString("hub_url", "") ?: "",
                prefs.getString("hub_token", "") ?: "", session, speaker, events!!)
            sync?.start()
            store(true, "Recording $speaker in $session")
        } catch (e: Exception) {
            log("recording_error", e.javaClass.simpleName)
            recorder?.let { runCatching { it.reset(); it.release() } }
            recorder = null
            store(false, "Failed: ${e.javaClass.simpleName}")
            stopForegroundCompat()
            stopSelf()
        }
    }
    private fun log(event: String, detail: String) {
        val line = CaptureTimeline.anchor(session, speaker, event, detail) + "\n"
        events?.let { runCatching { it.appendText(line) } }
    }
    private fun notice(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, CommentaryRecorderActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Morning Show microphone")
            .setContentText("Recording $speaker — tap Stop to finish")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
    }
    private fun finishRecording() {
        val r = recorder ?: run { stopSelf(); return }
        sync?.stop()
        log("recording_stop_requested", "explicit")
        val result = runCatching { r.stop() }
        runCatching { r.reset(); r.release() }
        recorder = null
        log(if (result.isSuccess) "recording_saved" else "recording_failed",
            audio?.name ?: "")
        store(false, if (result.isSuccess) "Saved ${audio?.name}"
            else "Recording could not be finalized")
        val hub = sync
        val tracks = listOfNotNull(audio, events,
            audio?.let { File(it.parentFile, it.nameWithoutExtension + ".session.json") })
        if (result.isSuccess && hub != null) {
            Thread({
                val ok = hub.upload(*tracks.toTypedArray())
                store(false, if (ok) "Synced to i9ES" else "Saved locally; upload pending")
                stopForegroundCompat()
                stopSelf()
            }, "MorningShowUpload").start()
        } else { stopForegroundCompat(); stopSelf() }
    }
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }
    override fun onDestroy() {
        if (recorder != null) finishRecording()
        super.onDestroy()
    }
}
