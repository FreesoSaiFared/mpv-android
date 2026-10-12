package `is`.xyz.mpv.commentary
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Pulls playback anchors from i9ES without interrupting local audio capture. */
class CentralSync(private val endpoint:String, private val token:String,
                  private val session:String, private val speaker:String,
                  private val events:File) {
    @Volatile private var active = false
    private var thread:Thread? = null
    private fun open(path:String, method:String="GET"):HttpURLConnection {
        val url=URL(endpoint.trimEnd('/')+path)
        require(url.protocol=="https" ||
            (url.host=="127.0.0.1" && url.protocol=="http"))
        val c=url.openConnection() as HttpURLConnection
        c.requestMethod=method
        c.connectTimeout=4500;c.readTimeout=4500
        c.setRequestProperty("Authorization","Bearer "+token)
        return c
    }
    fun start() {
        if (endpoint.isBlank()||token.isBlank()||active)return
        active=true
        thread=Thread({
            while(active) {
                val sent=SystemClock.elapsedRealtimeNanos()
                try {
                    val c=open("/api/playback")
                    val response=c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val received=SystemClock.elapsedRealtimeNanos()
                    val data=JSONObject().put("schema","morning-show-hub-sync/1")
                        .put("session_id",session).put("speaker",speaker)
                        .put("client_send_mono_ns",sent)
                        .put("client_receive_mono_ns",received)
                        .put("server_state",JSONObject(response))
                        .put("server_host",URL(endpoint).host)
                    synchronized(events){events.appendText(data.toString()+"\n")}
                } catch(e:Exception){
                    Log.w("MorningCentralSync","Hub temporarily unavailable",e)
                }
                try{Thread.sleep(1100)}catch(_:InterruptedException){break}
            }
        },"MorningShowSync").apply { isDaemon=true; start() }
    }
    fun stop() {active=false;thread?.interrupt()}
    fun upload(vararg files:File):Boolean {
        if(endpoint.isBlank()||token.isBlank())return false
        var success=true
        files.forEach{file->
            if(!file.exists()||file.length()==0L)return@forEach
            try {
                val uri="/api/recordings/$session/$speaker/${file.name}"
                val c=open(uri,"POST")
                c.doOutput=true
                c.setRequestProperty("Content-Type","application/octet-stream")
                c.setChunkedStreamingMode(65536)
                file.inputStream().use{src->
                    c.outputStream.use{dst->src.copyTo(dst,65536)}
                }
                val code=c.responseCode
                if(code!=201&&code!=409)success=false
                c.disconnect()
            }catch(e:Exception){
                Log.w("MorningCentralSync","Upload deferred: ${file.name}",e)
                success=false
            }
        }
        return success
    }
}
