package com.abacus.dictaphone

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object NetApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    data class TranscriptionResult(val text: String)

    data class AiResult(
        val cleanedText: String,
        val translation: String,
        val summary: String,
        val notes: String,
        val confidence: String,
        val warnings: List<String>,
    )

    fun health(baseUrl: String): Pair<Boolean, String> {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "/.netlify/functions/health"))
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    false to "Server returned ${response.code}."
                } else {
                    val json = JSONObject(body)
                    val configured = json.optBoolean("openaiConfigured", false)
                    if (configured) {
                        true to "Server connected and OpenAI is configured."
                    } else {
                        false to "Server is online, but OPENAI_API_KEY is missing."
                    }
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "Could not reach server.")
        }
    }

    fun transcribeChunk(
        baseUrl: String,
        audioFile: File,
        language: String,
        previousContext: String,
    ): TranscriptionResult {
        val audioBody = audioFile.asRequestBody("audio/wav".toMediaType())
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", audioFile.name, audioBody)
            .addFormDataPart("language", language)
            .addFormDataPart("previousContext", previousContext.takeLast(2500))
            .build()

        val request = Request.Builder()
            .url(endpoint(baseUrl, "/.netlify/functions/transcribe-chunk"))
            .post(form)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(errorMessage(body, "Transcription request failed."))
            }
            val json = JSONObject(body)
            return TranscriptionResult(json.optString("text").trim())
        }
    }

    fun processText(
        baseUrl: String,
        text: String,
        mode: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): AiResult {
        val payload = JSONObject().apply {
            put("text", text)
            put("mode", mode)
            put("sourceLanguage", sourceLanguage)
            put("targetLanguage", targetLanguage)
        }

        val request = Request.Builder()
            .url(endpoint(baseUrl, "/.netlify/functions/process-text"))
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(errorMessage(body, "AI processing request failed."))
            }

            val json = JSONObject(body)
            val warningsArray = json.optJSONArray("warnings") ?: JSONArray()
            val warnings = buildList {
                for (index in 0 until warningsArray.length()) {
                    warnings.add(warningsArray.optString(index))
                }
            }

            return AiResult(
                cleanedText = json.optString("cleanedText").trim(),
                translation = json.optString("translation").trim(),
                summary = json.optString("summary").trim(),
                notes = json.optString("notes").trim(),
                confidence = json.optString("confidence", "medium"),
                warnings = warnings,
            )
        }
    }

    private fun endpoint(baseUrl: String, path: String): String {
        val normalized = baseUrl.trim().removeSuffix("/")
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "Server URL must start with https://"
        }
        return normalized + path
    }

    private fun errorMessage(body: String, fallback: String): String {
        return try {
            val json = JSONObject(body)
            json.optString("error").ifBlank { fallback }
        } catch (_: Exception) {
            fallback
        }
    }
}
