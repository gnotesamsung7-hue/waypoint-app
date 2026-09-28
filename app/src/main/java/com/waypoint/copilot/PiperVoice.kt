package com.waypoint.copilot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The Waypoint natural voice: Piper "Lessac" running fully on the phone via sherpa-onnx. */
class PiperVoice(private val ctx: Context, private val attrs: AudioAttributes, private val listener: Listener) {

    interface Listener {
        fun onReady()
        fun onFailed()
        fun onDone(token: Int)
    }

    private val exec = Executors.newSingleThreadExecutor()
    // Exists only while native voice code is running. If the app dies in there,
    // it's still on disk next launch, so we know the voice crashed the app.
    private val busy = File(ctx.filesDir, "piper.busy")
    private val current = AtomicInteger(0)
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null

    fun start() = exec.execute {
        try {
            busy.createNewFile()
            // espeak-ng-data must be real files on disk; the model is read from the APK.
            val dataDir = File(ctx.filesDir, "piper/espeak-ng-data")
            val marker = File(ctx.filesDir, "piper/.version")
            val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
            if (!marker.exists() || marker.readText() != version) {
                File(ctx.filesDir, "piper").deleteRecursively()
                copyAsset("piper/espeak-ng-data", dataDir)
                marker.writeText(version)
            }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "piper/model.onnx",
                        tokens = "piper/tokens.txt",
                        dataDir = dataDir.absolutePath
                    ),
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            val engine = OfflineTts(assetManager = ctx.assets, config = config)
            val rate = engine.sampleRate()
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, rate * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            tts = engine
            busy.delete()
            listener.onReady()
        } catch (e: Throwable) {
            busy.delete()
            Log.e("Waypoint", "Natural voice failed to load", e)
            listener.onFailed()
        }
    }

    fun speak(text: String, speed: Float, token: Int) {
        val id = current.incrementAndGet()
        exec.execute {
            val engine = tts ?: return@execute
            val out = track ?: return@execute
            if (id != current.get()) return@execute
            try {
                busy.createNewFile()
                out.pause(); out.flush(); out.play()
                engine.generateWithCallback(text = text, sid = 0, speed = speed) { samples ->
                    if (id != current.get()) 0
                    else { out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING); 1 }
                }
                busy.delete()
                if (id == current.get()) {
                    out.stop() // lets the last buffer finish playing
                    listener.onDone(token)
                }
            } catch (e: Throwable) {
                busy.delete()
                Log.e("Waypoint", "Natural voice failed to speak", e)
                listener.onDone(token)
            }
        }
    }

    fun stop() {
        current.incrementAndGet()
        track?.let { try { it.pause(); it.flush() } catch (_: Throwable) {} }
    }

    fun release() {
        stop()
        exec.execute {
            try { track?.release() } catch (_: Throwable) {}
            try { tts?.release() } catch (_: Throwable) {}
            track = null; tts = null
        }
        exec.shutdown()
    }

    private fun copyAsset(path: String, dest: File) {
        val children = ctx.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                ctx.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            } catch (e: FileNotFoundException) {
                dest.mkdirs() // an empty folder
            }
        } else {
            dest.mkdirs()
            children.forEach { copyAsset("$path/$it", File(dest, it)) }
        }
    }
}
