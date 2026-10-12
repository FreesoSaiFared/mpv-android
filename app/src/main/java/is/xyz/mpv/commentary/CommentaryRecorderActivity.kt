package `is`.xyz.mpv.commentary
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

/** Audio-only, explicitly activated recording companion. */
class CommentaryRecorderActivity : AppCompatActivity() {
    private lateinit var sessionField: EditText
    private lateinit var speakerSpinner: Spinner
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { showState(); ui.postDelayed(this, 1000) }
    }
    private val amber = Color.rgb(232, 171, 81)
    private val light = Color.rgb(229, 229, 220)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (32 * dp).toInt(),
                (24 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(Color.rgb(17, 25, 29))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        fun heading(value: String, size: Float, color: Int = light) {
            root.addView(TextView(this).apply {
                text = value; textSize = size; setTextColor(color)
                setPadding(0, 0, 0, (14 * dp).toInt())
            })
        }
        heading("MORNING SHOW / 01", 13f, amber)
        heading("Commentary Recorder", 27f)
        heading("Audio-only • microphone active only when you press Start", 15f)
        heading("SESSION", 12f, amber)
        sessionField = EditText(this).apply {
            setSingleLine(true); setTextColor(light); textSize = 17f
            setText(SimpleDateFormat("'morning-'yyyyMMdd", Locale.ROOT).format(Date()))
        }
        root.addView(sessionField)
        heading("PRESENTER", 12f, amber)
        speakerSpinner = Spinner(this)
        speakerSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Friso", "Vanessa"))
        root.addView(speakerSpinner)
        heading("CAPTURE", 12f, amber)
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label; textSize = 16f; minHeight = (54 * dp).toInt()
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = (10 * dp).toInt()
                    }
            })
        }
        button("START MICROPHONE") { startCapture() }
        button("SYNC MARKER / CLAP") { sendCommand(CommentaryCaptureService.ANCHOR) }
        button("STOP AND SAVE") { sendCommand(CommentaryCaptureService.STOP) }
        status = TextView(this).apply {
            textSize = 15f; setTextColor(light)
            setPadding(0, (14 * dp).toInt(), 0, 0)
        }
        root.addView(status)
        showState()
    }
    private fun startCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 27)
            return
        }
        val start = Intent(this, CommentaryCaptureService::class.java).apply {
            action = CommentaryCaptureService.START
            putExtra("session", sessionField.text.toString())
            putExtra("speaker", speakerSpinner.selectedItem.toString())
        }
        ContextCompat.startForegroundService(this, start)
        status.text = "Starting microphone…"
    }
    private fun sendCommand(command: String) {
        val cmd = Intent(this, CommentaryCaptureService::class.java).apply {
            action = command
            putExtra("note", "clap_or_clip_boundary")
        }
        startService(cmd)
    }
    @Deprecated("Legacy permission result")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 27 && grantResults.firstOrNull() ==
            PackageManager.PERMISSION_GRANTED) startCapture()
        else if (requestCode == 27) status.text = "Microphone permission denied"
    }
    private fun showState() {
        val p = getSharedPreferences(CommentaryCaptureService.PREFS, MODE_PRIVATE)
        val active = p.getBoolean("recording", false)
        status.text = (if (active) "● RECORDING\n" else "○ STOPPED\n") +
            p.getString("status", "Ready to record") + "\n" +
            p.getString("file", "")
    }
    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { ui.removeCallbacks(refresh); super.onPause() }
}
