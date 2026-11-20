package com.home.tvremote.picks

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * The phone's own model (Gemini Nano via Android's on-device AI, on supported phones such as the
 * Pixel 9). Free, works offline, and nothing leaves the phone. Android downloads and updates the
 * model itself; if it isn't downloaded yet, we start the download and this engine is skipped until ready.
 */
object OnDeviceAi : AiEngine {
    override val name = "On-phone"
    override val compact = true

    enum class State { Unknown, Ready, Downloading, NotSupported, Error }

    @Volatile var state: State = State.Unknown
        private set
    @Volatile var detail: String = ""
        private set

    private val model: GenerativeModel by lazy { Generation.getClient() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Check (and if needed start downloading) the on-phone model. Safe to call often. */
    suspend fun refresh(): State {
        state = try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> State.Ready
                FeatureStatus.DOWNLOADABLE -> { startDownload(); State.Downloading }
                FeatureStatus.DOWNLOADING -> State.Downloading
                else -> State.NotSupported
            }
        } catch (e: Throwable) {
            // Phones without Android's on-device AI (or without Play services) land here.
            detail = e.message ?: e.javaClass.simpleName
            State.NotSupported
        }
        return state
    }

    private fun startDownload() = scope.launch {
        runCatching {
            model.download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadCompleted -> state = State.Ready
                    is DownloadStatus.DownloadFailed -> { state = State.Error; detail = "Download failed" }
                    else -> state = State.Downloading
                }
            }
        }.onFailure { state = State.Error; detail = it.message ?: "Download failed" }
    }

    override suspend fun json(system: String, user: String): JSONObject {
        if (state != State.Ready && refresh() != State.Ready) throw PicksException("On-phone AI isn't ready ($state)")
        val request = GenerateContentRequest.Builder(SystemInstruction(system), TextPart(user)).apply {
            temperature = 0.2f
            maxOutputTokens = 300
        }.build()
        val response = withTimeout(25_000) { model.generateContent(request) }
        val text = response.candidates.firstOrNull()?.text ?: throw PicksException("On-phone AI returned nothing")
        return extractJson(text)
    }

    /** One line for setup / Diagnostics. */
    fun describe(): String = when (state) {
        State.Ready -> "Ready on this phone"
        State.Downloading -> "Downloading to this phone (Android does this in the background)"
        State.NotSupported -> "Not available on this phone" + if (detail.isNotEmpty()) " ($detail)" else ""
        State.Error -> "Problem: $detail"
        State.Unknown -> "Checking…"
    }
}
