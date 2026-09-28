package com.waypoint.copilot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.WindowManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val PIPER = "waypoint.natural"
        const val PIPER_VOICE = "lessac"
        const val REQ_MIC = 42
        val PLUM: Int = Color.parseColor("#2B1F3F")
    }

    private lateinit var web: WebView
    private lateinit var audio: AudioManager
    private lateinit var piper: PiperVoice
    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var piperReady = false
    @Volatile private var piperOff = false
    @Volatile private var engine = PIPER
    @Volatile private var speechRate = 1f
    private var wantedVoice = ""
    private var systemTts: TextToSpeech? = null
    @Volatile private var systemReady = false
    private var systemEngine: String? = null
    private var speakToken = 0
    private var focus: AudioFocusRequest? = null

    private var recognizer: SpeechRecognizer? = null
    private var pendingLang: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        window.statusBarColor = PLUM
        window.navigationBarColor = PLUM
        audio = getSystemService(AudioManager::class.java)

        web = WebView(this).apply {
            setBackgroundColor(PLUM)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    notifyMic(hasMic())
                    notifyTtsReady()
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        initSystemTts(null)
        piper = PiperVoice(this, attrs, object : PiperVoice.Listener {
            override fun onReady() = runOnUiThread { piperReady = true; notifyTtsReady() }
            override fun onFailed() = runOnUiThread { piperReady = false; notifyTtsReady() }
            override fun onDone(token: Int) = ui("voiceDone") { releaseFocus(token) }
        })
        // If the natural voice took the app down last time, don't load it again
        // until the user asks, so the app can't get stuck crashing on open.
        val prefs = getSharedPreferences("waypoint", MODE_PRIVATE)
        val voiceCrashed = File(filesDir, "piper.busy").let { val had = it.exists(); it.delete(); had }
        if (voiceCrashed) prefs.edit().putBoolean("piperOff", true).apply()
        piperOff = prefs.getBoolean("piperOff", false)
        if (!piperOff) piper.start()

        showProblems(CrashLog.take(this), voiceCrashed)

        // Ask for the mic now, while parked, not in the middle of a drive.
        if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    // ---------- helpers to talk to the page ----------
    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }
    private fun notifyTtsReady() = js("window.onNativeTtsReady&&window.onNativeTtsReady()")
    private fun notifyMic(granted: Boolean) = js("window.onNativeMicPermission&&window.onNativeMicPermission($granted)")
    private fun sendSpeech(msg: JSONObject) = js("window.onNativeSpeech&&window.onNativeSpeech($msg)")
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- voices ----------
    private fun initSystemTts(pkg: String?) {
        systemTts?.shutdown()
        systemReady = false
        val onInit = TextToSpeech.OnInitListener { status ->
            runOnUiThread {
                val t = systemTts
                if (status == TextToSpeech.SUCCESS && t != null) {
                    systemReady = true
                    t.setAudioAttributes(attrs)
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { tokenOf(id)?.let { runOnUiThread { releaseFocus(it) } } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { tokenOf(id)?.let { runOnUiThread { releaseFocus(it) } } }
                    })
                    applySystemVoice()
                }
                notifyTtsReady()
            }
        }
        systemEngine = pkg
        systemTts = if (pkg == null) TextToSpeech(this, onInit) else TextToSpeech(this, onInit, pkg)
    }

    private fun tokenOf(id: String?) = id?.removePrefix("wp")?.toIntOrNull()

    private fun installedVoices(): List<Voice> = try {
        systemTts?.voices.orEmpty().filter {
            it.locale.language == "en" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
    } catch (_: Throwable) { emptyList() }

    private fun bestVoice(): Voice? = installedVoices().sortedWith(
        compareByDescending<Voice> { if (it.locale.country == "US") 1 else 0 }
            .thenBy { if (it.isNetworkConnectionRequired) 1 else 0 }
            .thenByDescending { it.quality }
    ).firstOrNull()

    private fun applySystemVoice() {
        val t = systemTts ?: return
        if (!systemReady) return
        t.setSpeechRate(speechRate)
        val chosen = if (wantedVoice.isNotEmpty()) installedVoices().firstOrNull { it.name == wantedVoice } else null
        val v = chosen ?: bestVoice()
        if (v != null) t.voice = v else t.setLanguage(Locale.US)
    }

    private fun usePiper() = engine == PIPER && piperReady

    private fun requestFocus(token: Int) {
        releaseFocus(null)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
        audio.requestAudioFocus(req)
        focus = req
        speakToken = token
    }

    /** Lets music come back up, but only if the finished line is still the latest one. */
    private fun releaseFocus(token: Int?) {
        if (token != null && token != speakToken) return
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun stopAll() {
        piper.stop()
        try { systemTts?.stop() } catch (_: Throwable) {}
    }

    private fun speakNow(text: String) {
        stopAll()
        val token = speakToken + 1
        requestFocus(token)
        when {
            usePiper() -> piper.speak(text, speechRate, token)
            systemReady -> systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wp$token")
            else -> releaseFocus(token)
        }
    }

    // ---------- after a crash ----------
    private fun showProblems(crash: String?, voiceCrashed: Boolean) {
        if (crash != null) {
            AlertDialog.Builder(this)
                .setTitle("Waypoint closed unexpectedly last time")
                .setMessage("Tap Copy details and paste them to Claude so it can be fixed.\n\n" + crash.take(1500))
                .setPositiveButton("Copy details") { _, _ ->
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Waypoint crash", crash))
                }
                .setNegativeButton("Close", null)
                .setOnDismissListener { if (voiceCrashed || piperOff) showVoiceOff() }
                .show()
        } else if (voiceCrashed) {
            showVoiceOff()
        }
    }

    private fun showVoiceOff() {
        AlertDialog.Builder(this)
            .setTitle("Natural voice turned off")
            .setMessage("The Waypoint natural voice crashed while starting on this phone, so the buddy is using your phone's normal voice for now.")
            .setPositiveButton("OK", null)
            .setNegativeButton("Try natural voice again") { _, _ ->
                getSharedPreferences("waypoint", MODE_PRIVATE).edit().putBoolean("piperOff", false).apply()
                recreate()
            }
            .show()
    }

    /** Runs page requests on the main thread; a problem is logged instead of closing the app. */
    private fun ui(what: String, block: () -> Unit) = runOnUiThread {
        try { block() } catch (t: Throwable) { Log.e("Waypoint", "Failed: $what", t) }
    }
    private fun <T> safe(what: String, fallback: T, block: () -> T): T =
        try { block() } catch (t: Throwable) { Log.e("Waypoint", "Failed: $what", t); fallback }

    // ---------- listening ----------
    private fun startListening(lang: String, preferOffline: Boolean = true) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendSpeech(JSONObject().put("type", "error").put("error", "unavailable"))
            return
        }
        stopAll()
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                sendSpeech(JSONObject().put("type", "result").put("text", text))
            }
            override fun onError(e: Int) {
                // 12/13: offline language pack missing, so try again online.
                if (preferOffline && (e == 12 || e == 13)) { startListening(lang, false); return }
                val code = when (e) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                    else -> "no-speech"
                }
                sendSpeech(JSONObject().put("type", "error").put("error", code))
            }
            override fun onReadyForSpeech(b: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(bytes: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(i: Int, b: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_MIC) return
        val ok = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        notifyMic(ok)
        val lang = pendingLang ?: return
        pendingLang = null
        if (ok) startListening(lang)
        else sendSpeech(JSONObject().put("type", "error").put("error", "not-allowed"))
    }

    // ---------- back button: the page decides (it stays put while driving) ----------
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.waypointBack&&window.waypointBack())?'1':'0'") { r ->
            if (r != "\"1\"") finish()
        }
    }

    override fun onDestroy() {
        piper.release()
        systemTts?.shutdown()
        recognizer?.destroy()
        releaseFocus(null)
        super.onDestroy()
    }

    // ---------- what the page can call as window.Android ----------
    private inner class Bridge {
        @JavascriptInterface fun speak(text: String) = ui("speak") { speakNow(text) }

        @JavascriptInterface fun stopSpeaking() = ui("stopSpeaking") { stopAll(); releaseFocus(null) }

        @JavascriptInterface fun getEngines(): String = safe("getEngines", "{}") {
            val list = JSONArray().put(JSONObject().put("name", PIPER).put("label", "Waypoint natural voice (offline)"))
            try {
                systemTts?.engines?.forEach { list.put(JSONObject().put("name", it.name).put("label", it.label)) }
            } catch (_: Throwable) {}
            JSONObject()
                .put("engines", list)
                .put("defaultEngine", try { systemTts?.defaultEngine ?: "" } catch (_: Throwable) { "" })
                .put("current", engine)
                .toString()
        }

        @JavascriptInterface fun getVoices(): String = safe("getVoices", "[]") {
            val out = JSONArray()
            if (engine == PIPER) {
                out.put(JSONObject().put("name", PIPER_VOICE).put("lang", "en-US").put("quality", 400)
                    .put("network", false).put("label", when {
                        piperOff -> "Lessac (off after a crash, using phone voice)"
                        piperReady -> "Lessac (natural, offline)"
                        else -> "Lessac (getting ready…)"
                    }))
                return@safe out.toString()
            }
            installedVoices().forEach {
                out.put(JSONObject().put("name", it.name).put("lang", it.locale.toLanguageTag())
                    .put("quality", it.quality).put("network", it.isNetworkConnectionRequired))
            }
            out.toString()
        }

        @JavascriptInterface fun setEngine(name: String) = ui("setEngine") {
            engine = name.ifEmpty { PIPER }
            if (engine == PIPER) {
                if (systemEngine != null) initSystemTts(null) else notifyTtsReady()
            } else if (engine != systemEngine) {
                initSystemTts(engine)
            } else notifyTtsReady()
        }

        @JavascriptInterface fun setVoice(name: String) = ui("setVoice") {
            wantedVoice = if (name == PIPER_VOICE) "" else name
            applySystemVoice()
        }

        @JavascriptInterface fun setRate(rate: Double) = ui("setRate") {
            speechRate = rate.toFloat().coerceIn(0.5f, 2f)
            systemTts?.setSpeechRate(speechRate)
        }

        @JavascriptInterface fun listen(lang: String) = ui("listen") {
            if (hasMic()) startListening(lang)
            else { pendingLang = lang; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
        }

        @JavascriptInterface fun stopListening() = ui("stopListening") {
            try { recognizer?.cancel() } catch (_: Throwable) {}
        }

        @JavascriptInterface fun keepAwake(on: Boolean) = ui("keepAwake") {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
