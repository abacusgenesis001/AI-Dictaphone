package com.abacus.dictaphone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.abacus.dictaphone.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var isRecording = false
    private var isProcessing = false
    private var suppressTranscriptWatcher = false

    private val prefs by lazy {
        getSharedPreferences("dictaphone", Context.MODE_PRIVATE)
    }

    private val languages = listOf(
        LanguageOption("Auto detect", "auto"),
        LanguageOption("English", "en"),
        LanguageOption("German", "de"),
        LanguageOption("Hindi", "hi"),
        LanguageOption("French", "fr"),
        LanguageOption("Spanish", "es"),
        LanguageOption("Italian", "it"),
        LanguageOption("Dutch", "nl"),
        LanguageOption("Portuguese", "pt"),
        LanguageOption("Japanese", "ja"),
        LanguageOption("Korean", "ko"),
        LanguageOption("Arabic", "ar"),
        LanguageOption("Turkish", "tr"),
    )

    private val targetLanguages = languages.filter { it.code != "auto" }

    private val modes = listOf("Dictate", "Translate", "Notes")

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            maybeRequestNotificationsThenStart()
        } else {
            showStatus("Microphone permission is required.", isError = true)
        }
    }

    private val requestNotificationsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startRecordingNow()
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        uri?.let { writeTextToUri(it, binding.outputText.text.toString()) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingService.ACTION_TRANSCRIPT -> {
                    val text = intent.getStringExtra(RecordingService.EXTRA_TEXT).orEmpty()
                    appendTranscript(text)
                    showStatus("Transcribed segment received")
                }
                RecordingService.ACTION_STATUS -> {
                    showStatus(intent.getStringExtra(RecordingService.EXTRA_MESSAGE).orEmpty())
                }
                RecordingService.ACTION_ERROR -> {
                    showStatus(
                        intent.getStringExtra(RecordingService.EXTRA_MESSAGE).orEmpty(),
                        isError = true,
                    )
                }
                RecordingService.ACTION_FINISHED -> {
                    isRecording = false
                    stopTimer()
                    updateRecordingUi()
                    showStatus("Transcription complete")
                    processWithAi()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupUiActions()
        setupTranscriptWatcher()
        loadSavedTranscript()
        updateModeVisibility()
        updateWordCount()
        updateServerLabel()
        updateRecordingUi()

        if (prefs.getString("server_url", "").isNullOrBlank()) {
            showServerSettings(showWelcome = true)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(RecordingService.ACTION_TRANSCRIPT)
            addAction(RecordingService.ACTION_STATUS)
            addAction(RecordingService.ACTION_ERROR)
            addAction(RecordingService.ACTION_FINISHED)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    private fun setupSpinners() {
        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modeSpinner.adapter = modeAdapter
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateModeVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val sourceLabels = languages.map { it.label }
        val sourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sourceLabels)
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.sourceSpinner.adapter = sourceAdapter
        binding.sourceSpinner.setSelection(1)

        val targetLabels = targetLanguages.map { it.label }
        val targetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, targetLabels)
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.targetSpinner.adapter = targetAdapter
        binding.targetSpinner.setSelection(targetLanguages.indexOfFirst { it.code == "en" }.coerceAtLeast(0))
    }

    private fun setupUiActions() {
        binding.micBtn.setOnClickListener {
            if (isRecording) stopRecording() else ensureReadyThenStart()
        }

        binding.serverBtn.setOnClickListener {
            showServerSettings(showWelcome = false)
        }

        binding.testServerBtn.setOnClickListener {
            testServer()
        }

        binding.clearBtn.setOnClickListener { confirmClear() }
        binding.copyTranscriptBtn.setOnClickListener { copyText(binding.transcript.text.toString()) }
        binding.copyOutputBtn.setOnClickListener { copyText(binding.outputText.text.toString()) }
        binding.saveOutputBtn.setOnClickListener {
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
            createDocumentLauncher.launch("ai-dictaphone-$stamp.txt")
        }
    }

    private fun ensureReadyThenStart() {
        if (prefs.getString("server_url", "").isNullOrBlank()) {
            showServerSettings(showWelcome = true)
            return
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        maybeRequestNotificationsThenStart()
    }

    private fun maybeRequestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startRecordingNow()
        }
    }

    private fun startRecordingNow() {
        if (isRecording) return

        suppressTranscriptWatcher = true
        binding.transcript.setText("")
        suppressTranscriptWatcher = false
        binding.outputText.text = ""
        binding.notesText.text = ""
        binding.summaryText.text = ""
        binding.warningsText.text = ""
        binding.outputPanel.visibility = View.GONE
        prefs.edit().remove("last_transcript").apply()
        updateWordCount()
        showStatus("Starting…")

        prefs.edit()
            .putString("source_language", currentSourceLanguage().code)
            .apply()

        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        isRecording = true
        binding.timer.base = SystemClock.elapsedRealtime()
        binding.timer.start()
        updateRecordingUi()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
        showStatus("Finishing transcription…")
        binding.timer.stop()
        updateRecordingUi()
    }

    private fun updateRecordingUi() {
        binding.micBtn.setBackgroundResource(
            if (isRecording) R.drawable.bg_mic_listening else R.drawable.bg_mic_idle,
        )
        binding.micLabel.text = if (isRecording) "Stop" else "Record"
        binding.timer.visibility = if (isRecording) View.VISIBLE else View.VISIBLE
        binding.modeSpinner.isEnabled = !isRecording
        binding.sourceSpinner.isEnabled = !isRecording
        binding.targetSpinner.isEnabled = !isRecording
    }

    private fun stopTimer() {
        binding.timer.stop()
    }

    private fun setupTranscriptWatcher() {
        binding.transcript.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressTranscriptWatcher) return
                updateWordCount()
            }
        })
    }

    private fun appendTranscript(text: String) {
        if (text.isBlank()) return
        val current = binding.transcript.text?.toString()?.trimEnd().orEmpty()
        val clean = text.trim()
        val next = if (current.isEmpty()) clean else "$current $clean"
        suppressTranscriptWatcher = true
        binding.transcript.setText(next)
        binding.transcript.setSelection(next.length)
        suppressTranscriptWatcher = false
        updateWordCount()
    }

    private fun loadSavedTranscript() {
        val last = prefs.getString("last_transcript", "").orEmpty()
        if (last.isNotBlank()) {
            suppressTranscriptWatcher = true
            binding.transcript.setText(last)
            binding.transcript.setSelection(last.length)
            suppressTranscriptWatcher = false
        }
    }

    private fun updateWordCount() {
        val words = binding.transcript.text?.toString()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.size ?: 0
        binding.wordCount.text = "$words words"
    }

    private fun currentMode(): String {
        return when (binding.modeSpinner.selectedItemPosition) {
            1 -> "translate"
            2 -> "notes"
            else -> "dictate"
        }
    }

    private fun currentSourceLanguage(): LanguageOption {
        return languages.getOrElse(binding.sourceSpinner.selectedItemPosition) { languages[1] }
    }

    private fun currentTargetLanguage(): LanguageOption {
        return targetLanguages.getOrElse(binding.targetSpinner.selectedItemPosition) { targetLanguages[0] }
    }

    private fun updateModeVisibility() {
        val mode = currentMode()
        binding.targetRow.visibility = if (mode == "dictate") View.GONE else View.VISIBLE
        binding.modeDescription.text = when (mode) {
            "translate" -> "Speak naturally. The app will clean the transcript and translate it after you stop."
            "notes" -> "Speak naturally. The app will turn the recording into a cleaned transcript, summary and notes."
            else -> "Speak naturally. The app will produce a cleaned transcript while preserving what you meant."
        }
    }

    private fun processWithAi() {
        val text = binding.transcript.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        isProcessing = true
        binding.outputPanel.visibility = View.VISIBLE
        binding.outputText.text = "AI is polishing the transcript…"
        binding.outputMeta.text = ""
        binding.notesText.text = ""
        binding.summaryText.text = ""
        binding.warningsText.text = ""

        val mode = currentMode()
        val source = currentSourceLanguage()
        val target = currentTargetLanguage()
        val serverUrl = prefs.getString("server_url", "").orEmpty()

        backgroundExecutor.execute {
            try {
                val result = NetApiClient.processText(
                    baseUrl = serverUrl,
                    text = text,
                    mode = mode,
                    sourceLanguage = source.label,
                    targetLanguage = target.label,
                )
                runOnUiThread {
                    binding.outputText.text = buildAiOutput(result, mode, target.label)
                    val warningCount = result.warnings.size
                    binding.outputMeta.text = "AI confidence: ${result.confidence.uppercase(Locale.US)}" +
                        if (warningCount > 0) " • $warningCount warning(s)" else ""
                    binding.notesText.text = result.notes
                    binding.summaryText.text = result.summary
                    binding.warningsText.text = result.warnings.joinToString("\n")
                    binding.notesSection.visibility = if (result.notes.isBlank()) View.GONE else View.VISIBLE
                    binding.summarySection.visibility = if (result.summary.isBlank()) View.GONE else View.VISIBLE
                    binding.warningsSection.visibility = if (result.warnings.isEmpty()) View.GONE else View.VISIBLE
                    if (mode == "translate" && result.translation.isNotBlank()) {
                        binding.outputLabel.text = "TRANSLATION"
                        binding.outputText.text = result.translation
                    }
                    isProcessing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isProcessing = false
                    binding.outputText.text = "AI processing failed: ${e.message ?: "unknown error"}"
                    binding.outputMeta.text = ""
                    showStatus("AI processing failed", isError = true)
                }
            }
        }
    }

    private fun buildAiOutput(
        result: NetApiClient.AiResult,
        mode: String,
        targetLabel: String,
    ): String {
        return when (mode) {
            "translate" -> result.translation.ifBlank { result.cleanedText }
            "notes" -> result.cleanedText
            else -> result.cleanedText
        }
    }

    private fun testServer() {
        val serverUrl = prefs.getString("server_url", "").orEmpty()
        if (serverUrl.isBlank()) {
            showServerSettings(showWelcome = false)
            return
        }
        showStatus("Checking server…")
        backgroundExecutor.execute {
            val (ok, message) = NetApiClient.health(serverUrl)
            runOnUiThread {
                showStatus(message, isError = !ok)
            }
        }
    }

    private fun updateServerLabel() {
        val url = prefs.getString("server_url", "").orEmpty()
        binding.serverLabel.text = if (url.isBlank()) "Backend: not configured" else "Backend: ${shortenUrl(url)}"
    }

    private fun shortenUrl(url: String): String {
        return url.removePrefix("https://").removePrefix("http://").removeSuffix("/")
    }

    private fun showServerSettings(showWelcome: Boolean) {
        val input = EditText(this).apply {
            setText(prefs.getString("server_url", "").orEmpty())
            hint = "https://your-site.netlify.app"
            setSingleLine(true)
        }

        val message = if (showWelcome) {
            "First deploy the backend to Netlify, then paste the Netlify site URL here. Do not enter your OpenAI API key in the app."
        } else {
            "Enter the HTTPS URL of your Netlify backend. Your OpenAI key stays on the server."
        }

        AlertDialog.Builder(this)
            .setTitle("AI backend server")
            .setMessage(message)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test", null)
            .setPositiveButton("Save") { _, _ ->
                saveServerUrl(input.text.toString())
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        saveServerUrl(input.text.toString())
                        dialog.dismiss()
                        testServer()
                    }
                }
                dialog.show()
            }
    }

    private fun saveServerUrl(value: String) {
        val clean = value.trim().removeSuffix("/")
        if (!(clean.startsWith("https://") || clean.startsWith("http://"))) {
            showStatus("Server URL must start with https://", isError = true)
            return
        }
        prefs.edit().putString("server_url", clean).apply()
        updateServerLabel()
        showStatus("Server URL saved")
    }

    private fun copyText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Dictaphone", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        if (binding.transcript.text.isNullOrBlank() && binding.outputText.text.isNullOrBlank()) return
        AlertDialog.Builder(this)
            .setMessage("Clear the current transcript and AI result?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                suppressTranscriptWatcher = true
                binding.transcript.setText("")
                suppressTranscriptWatcher = false
                binding.outputText.text = ""
                binding.notesText.text = ""
                binding.summaryText.text = ""
                binding.warningsText.text = ""
                binding.outputPanel.visibility = View.GONE
                prefs.edit().remove("last_transcript").apply()
                updateWordCount()
            }
            .show()
    }

    private fun writeTextToUri(uri: Uri, text: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStatus(message: String, isError: Boolean = false) {
        binding.statusLabel.text = message
        binding.statusLabel.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.danger else R.color.muted),
        )
    }

    override fun onDestroy() {
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    private data class LanguageOption(val label: String, val code: String)
}
