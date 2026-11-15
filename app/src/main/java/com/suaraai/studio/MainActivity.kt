package com.suaraai.studio

import android.annotation.SuppressLint
import android.content.ContentValues
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var lastGeneratedWav: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        tts = TextToSpeech(this, this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidTTSBridge(), "AndroidTTS")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts.language = Locale("id", "ID")
        }
    }

    private fun configureVoice(lang: String, gender: String, emotion: String) {
        val locale = when (lang) {
            "en-US" -> Locale.US
            "ms-MY" -> Locale("ms", "MY")
            else -> Locale("id", "ID")
        }
        tts.language = locale

        val matchingVoice = tts.voices?.firstOrNull { v ->
            v.locale.language == locale.language &&
            if (gender == "pria") {
                v.name.contains("male", true) || v.name.contains("idd", true) || v.name.contains("iol", true)
            } else {
                v.name.contains("female", true) || v.name.contains("idc", true) || v.name.contains("sfg", true)
            }
        }
        if (matchingVoice != null) {
            tts.voice = matchingVoice
        }

        val (rate, basePitch) = when (emotion) {
            "ceria"    -> 1.08f to 1.18f
            "antusias" -> 1.18f to 1.24f
            "marah"    -> 1.12f to 0.82f
            "bingung"  -> 0.92f to 1.14f
            "sedih"    -> 0.82f to 0.82f
            "puitis"   -> 0.78f to 0.90f
            else       -> 1.00f to 1.00f
        }
        val genderPitchOffset = if (gender == "pria") -0.10f else 0.08f
        tts.setSpeechRate(rate)
        tts.setPitch((basePitch + genderPitchOffset).coerceIn(0.5f, 2.0f))
    }

    private fun mergeWavFiles(parts: List<File>, output: File) {
        if (parts.isEmpty()) return
        if (parts.size == 1) {
            parts[0].copyTo(output, overwrite = true)
            return
        }
        val header = ByteArray(44)
        FileInputStream(parts[0]).use { it.read(header) }

        var totalAudioLen = 0L
        for (part in parts) {
            if (part.length() > 44) {
                totalAudioLen += (part.length() - 44)
            }
        }
        val totalDataLen = totalAudioLen + 36
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen.toInt())
        ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalAudioLen.toInt())

        FileOutputStream(output).use { out ->
            out.write(header)
            val buffer = ByteArray(4096)
            for (part in parts) {
                FileInputStream(part).use { input ->
                    input.skip(44)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun saveToDownloads(sourceFile: File, displayName: String, mimeType: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SuaraAI")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                    }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SuaraAI")
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, displayName)
                sourceFile.copyTo(dest, overwrite = true)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    inner class AndroidTTSBridge {
        @JavascriptInterface
        fun synthesize(text: String, lang: String, gender: String, emotion: String) {
            if (!ttsReady) {
                notifyJs("onError('Mesin suara Android belum siap.')")
                return
            }
            configureVoice(lang, gender, emotion)

            // Pecah teks panjang otomatis per ~3000 karakter agar tanpa batas
            val chunks = text.chunked(3000)
            val tempParts = mutableListOf<File>()
            val batchId = UUID.randomUUID().toString().take(8)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {
                    notifyJs("onError('Gagal merender bagian audio.')")
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "chunk_${chunks.lastIndex}") {
                        val merged = File(cacheDir, "narasi_$batchId.wav")
                        mergeWavFiles(tempParts, merged)
                        tempParts.forEach { it.delete() }
                        lastGeneratedWav = merged
                        notifyJs("onAudioReady()")
                    }
                }
            })

            chunks.forEachIndexed { index, chunk ->
                val partFile = File(cacheDir, "part_${batchId}_$index.wav")
                tempParts.add(partFile)
                tts.synthesizeToFile(chunk, null, partFile, "chunk_$index")
            }
        }

        @JavascriptInterface
        fun playAudio() {
            val file = lastGeneratedWav ?: return
            runOnUiThread {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        notifyJs("onPlaybackEnded()")
                    }
                }
            }
        }

        @JavascriptInterface
        fun stopAudio() {
            runOnUiThread {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
            }
        }

        @JavascriptInterface
        fun downloadFile(format: String) {
            val file = lastGeneratedWav
            if (file == null || !file.exists()) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Buat suara terlebih dahulu!", Toast.LENGTH_SHORT).show()
                }
                return
            }
            val ext = if (format.lowercase() == "mp3") "mp3" else "wav"
            val mime = if (ext == "mp3") "audio/mpeg" else "audio/wav"
            val name = "SuaraAI_${System.currentTimeMillis()}.$ext"
            val ok = saveToDownloads(file, name, mime)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this@MainActivity, "Tersimpan di Downloads/SuaraAI/$name", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Gagal menyimpan file.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun notifyJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
