package com.abacus.dictaphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.abacus.dictaphone.action.START"
        const val ACTION_STOP = "com.abacus.dictaphone.action.STOP"
        const val ACTION_TRANSCRIPT = "com.abacus.dictaphone.action.TRANSCRIPT"
        const val ACTION_STATUS = "com.abacus.dictaphone.action.STATUS"
        const val ACTION_FINISHED = "com.abacus.dictaphone.action.FINISHED"
        const val ACTION_ERROR = "com.abacus.dictaphone.action.ERROR"

        const val EXTRA_TEXT = "text"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_SEQUENCE = "sequence"

        private const val CHANNEL_ID = "dictaphone_recording"
        private const val NOTIFICATION_ID = 4001
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_MILLIS = 12000L
        private const val PREFS = "dictaphone"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SESSION_ID = "active_session_id"
        private const val KEY_SOURCE_LANG = "source_language"
    }

    private var recording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var uploadThread: Thread? = null
    private val pendingQueue = LinkedBlockingQueue<File>()
    private var sessionId: String = ""
    private var chunkSequence = 0
    private var serverUrl = ""
    private var sourceLanguage = "auto"
    private lateinit var sessionDir: File
    private lateinit var transcriptFile: File

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!recording) startRecording()
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        serverUrl = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "")
            .orEmpty()
        sourceLanguage = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_SOURCE_LANG, "auto")
            .orEmpty()

        if (serverUrl.isBlank()) {
            sendError("Server URL is not configured.")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification("Listening and transcribing…"))

        sessionId = UUID.randomUUID().toString()
        chunkSequence = 0
        sessionDir = File(filesDir, "sessions/$sessionId/pending")
        if (!sessionDir.exists()) sessionDir.mkdirs()
        transcriptFile = File(sessionDir.parentFile, "raw-transcript.txt")
        transcriptFile.writeText("")

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SESSION_ID, sessionId)
            .apply()

        recording = true
        enqueueExistingPendingFiles()
        startUploadWorker()
        sendStatus("Recording and transcribing…")

        recordingThread = Thread { recordLoop() }.also { it.start() }
    }

    private fun enqueueExistingPendingFiles() {
        if (!::sessionDir.isInitialized) return
        sessionDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("wav", true) }
            ?.sortedBy { it.name }
            ?.forEach { pendingQueue.offer(it) }
    }

    private fun startUploadWorker() {
        if (uploadThread?.isAlive == true) return

        uploadThread = Thread {
            while (recording || pendingQueue.isNotEmpty()) {
                val file = pendingQueue.poll(1, TimeUnit.SECONDS) ?: continue
                uploadChunkWithRetry(file)
            }

            if (::transcriptFile.isInitialized) {
                val finalText = transcriptFile.readText().trim()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_SESSION_ID)
                    .putString("last_transcript", finalText)
                    .apply()
                sendBroadcast(Intent(ACTION_FINISHED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_SESSION_ID, sessionId)
                })
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.also { it.start() }
    }

    private fun uploadChunkWithRetry(file: File) {
        var lastError: String? = null
        repeat(3) { attempt ->
            try {
                sendStatus("Transcribing segment ${file.nameWithoutExtension.removePrefix("chunk-")}…")
                val result = NetApiClient.transcribeChunk(
                    baseUrl = serverUrl,
                    audioFile = file,
                    language = sourceLanguage,
                )

                val appendedText = appendTranscript(result.text)
                if (appendedText.isNotBlank()) {
                    sendBroadcast(Intent(ACTION_TRANSCRIPT).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_TEXT, appendedText)
                        putExtra(EXTRA_SESSION_ID, sessionId)
                        putExtra(EXTRA_SEQUENCE, chunkSequence)
                    })
                }

                if (file.exists()) file.delete()
                return
            } catch (e: Exception) {
                lastError = e.message ?: "Transcription failed."
                Thread.sleep((attempt + 1L) * 1500L)
            }
        }

        sendError("A segment could not be transcribed: ${lastError ?: "unknown error"}. It is kept locally for retry.")
    }

    /**
     * Appends only the new portion of a transcription result. If the speech
     * service accidentally repeats the end of the previous result at a chunk
     * boundary, that overlapping prefix is removed before it is stored or sent
     * to the UI.
     */
    private fun appendTranscript(text: String): String {
        val clean = text.trim()
        if (clean.isBlank()) return ""

        val existing = if (transcriptFile.exists()) {
            transcriptFile.readText().trim()
        } else {
            ""
        }

        val delta = removeBoundaryOverlap(existing, clean)
        if (delta.isBlank()) return ""

        val separator = if (existing.isEmpty()) "" else " "
        transcriptFile.appendText(separator + delta)
        return delta
    }

    private fun removeBoundaryOverlap(existing: String, incoming: String): String {
        if (existing.isBlank() || incoming.isBlank()) return incoming

        val existingWords = existing.split(Regex("\\s+")).filter { it.isNotBlank() }
        val incomingWords = incoming.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (incomingWords.isEmpty()) return ""

        val maxOverlap = minOf(existingWords.size, incomingWords.size, 40)
        var bestOverlap = 0

        for (size in maxOverlap downTo 1) {
            val suffix = existingWords.takeLast(size)
            val prefix = incomingWords.take(size)
            if (suffix.size != prefix.size) continue

            var matches = true
            for (index in suffix.indices) {
                if (normalizeToken(suffix[index]) != normalizeToken(prefix[index])) {
                    matches = false
                    break
                }
            }

            if (matches) {
                bestOverlap = size
                break
            }
        }

        // Only treat a multi-word boundary match as accidental overlap. A
        // one-word match is often legitimate repeated speech (for example,
        // “yes” followed by another “yes”). Full duplicate results are safe to
        // suppress even when they contain only one or two words.
        val fullDuplicate = bestOverlap == incomingWords.size
        if (bestOverlap < 3 && !fullDuplicate) return incoming

        return incomingWords.drop(bestOverlap).joinToString(" ")
    }

    private fun normalizeToken(token: String): String {
        return token
            .lowercase(Locale.US)
            .trim()
            .trim('.', ',', '!', '?', ':', ';', '"', '\'')
    }

    private fun recordLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        if (minBuffer <= 0) {
            sendError("This device cannot initialize audio capture at 16 kHz PCM.")
            recording = false
            return
        }

        val bufferSize = maxOf(minBuffer * 2, 4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNELS,
            ENCODING,
            bufferSize,
        )
        audioRecord = recorder

        try {
            recorder.startRecording()
            val buffer = ByteArray(bufferSize)

            while (recording) {
                val file = File(sessionDir, String.format("chunk-%05d.wav", chunkSequence++))
                val rawBytes = ByteArrayOutputStream()
                val startedAt = System.currentTimeMillis()

                while (recording && System.currentTimeMillis() - startedAt < CHUNK_MILLIS) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) rawBytes.write(buffer, 0, read)
                }

                val pcm = rawBytes.toByteArray()
                if (pcm.isNotEmpty()) {
                    WavFile.write(file, pcm, SAMPLE_RATE, 1, 16)
                    pendingQueue.offer(file)
                } else if (file.exists()) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            sendError("Microphone capture failed: ${e.message ?: "unknown error"}")
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
            audioRecord = null
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        sendStatus("Finishing the current audio segment…")
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("AI Dictaphone")
            .setContentText(message)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AI Dictaphone recording",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    private fun sendError(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
        super.onDestroy()
    }
}

private object WavFile {
    fun write(file: File, pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            val dataLength = pcm.size
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val chunkSize = 36 + dataLength

            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, chunkSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 16)
            writeShortLE(out, 1)
            writeShortLE(out, channels)
            writeIntLE(out, sampleRate)
            writeIntLE(out, byteRate)
            writeShortLE(out, blockAlign)
            writeShortLE(out, bitsPerSample)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, dataLength)
            out.write(pcm)
        }
    }

    private fun writeIntLE(out: java.io.OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(out: java.io.OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}
