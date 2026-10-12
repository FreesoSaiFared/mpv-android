package `is`.xyz.mpv.commentary
import android.content.Context
import android.os.*
import android.util.Log
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject
import kotlin.math.abs

/** Local Chromebook playback clock; phone alignment is an independent next step. */
class MorningPlaybackTimeline(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = SimpleDateFormat("'morning-'yyyyMMdd", Locale.ROOT).format(Date())
    private val out: File
    private var active = false
    private var previousPath: String? = null
    private var previousPosition = 0.0
    private var previousNanos = 0L
    private var previousPaused: Boolean? = null
    init {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            "MorningShow/$session")
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        out = File(directory, "player_$stamp.events.jsonl")
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            runCatching { sample() }
                .onFailure { Log.w("MorningPlaybackTimeline", "Sampling failed", it) }
            handler.postDelayed(this, 500L)
        }
    }
    fun start() {
        if (active) return
        active = true
        handler.post(ticker)
    }
    fun stop() {
        active = false
        handler.removeCallbacks(ticker)
    }
    private fun sample() {
        val path = MPVLib.getPropertyString("path") ?: return
        if (path.isBlank()) return
        val position = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        val paused = MPVLib.getPropertyBoolean("pause") ?: false
        val speed = MPVLib.getPropertyDouble("speed") ?: 1.0
        val now = SystemClock.elapsedRealtimeNanos()
        var event = "playback_tick"
        if (previousPath != null && previousPath != path) event = "clip_changed"
        else if (previousPaused != null && previousPaused != paused) event = "pause_changed"
        else if (previousNanos > 0 && !paused &&
            abs(position - previousPosition -
                ((now - previousNanos) / 1.0e9) * speed) > 2.0)
            event = "seek_or_discontinuity"
        val line = JSONObject().put("schema", "morning-show-playback/1")
            .put("event", event).put("session_id", session)
            .put("speaker", "chromebook-player")
            .put("media_path", path).put("position_sec", position)
            .put("paused", paused).put("speed", speed)
            .put("elapsed_realtime_nanos", now)
            .put("wall_time_utc_ms", System.currentTimeMillis())
            .toString() + "\n"
        runCatching { out.appendText(line) }
            .onFailure { Log.w("MorningPlaybackTimeline", "Write failed", it) }
        previousPath = path
        previousPosition = position
        previousPaused = paused
        previousNanos = now
    }
}
