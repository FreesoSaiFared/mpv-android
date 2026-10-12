package `is`.xyz.mpv.commentary

import android.os.SystemClock
import org.json.JSONObject

object CaptureTimeline {
    fun anchor(session: String, speaker: String, event: String, detail: String): String =
        JSONObject().put("schema", "morning-show-event/1")
            .put("session_id", session)
            .put("speaker", speaker)
            .put("event", event)
            .put("detail", detail)
            .put("elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos())
            .put("wall_time_utc_ms", System.currentTimeMillis())
            .toString()
}
