package alias.klips.net.ai

import alias.klips.net.core.model.*
import alias.klips.net.core.security.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class AiCapabilityCategory(val label: String) {
    TEXT_LLM("Text & Script AI"),
    IMAGE_GEN("Image Generation"),
    VIDEO_GEN("Video Generation"),
    AUDIO_SPEECH("Audio & Voice AI"),
    TRANSCRIPTION("Speech-to-Text")
}

enum class AiTaskType(
    val label: String,
    val requiredCapability: AiCapabilityCategory
) {
    TEXT_GENERATION("Text Generation", AiCapabilityCategory.TEXT_LLM),
    SCRIPT_WRITING("Script Writing", AiCapabilityCategory.TEXT_LLM),
    SUBTITLE_ASSISTANCE("Subtitle Assistance", AiCapabilityCategory.TEXT_LLM),
    IMAGE_GENERATION("Image Generation", AiCapabilityCategory.IMAGE_GEN),
    VIDEO_GENERATION("Video Generation", AiCapabilityCategory.VIDEO_GEN),
    AUDIO_GENERATION("Audio Generation", AiCapabilityCategory.AUDIO_SPEECH),
    SPEECH_TO_TEXT("Speech-to-Text", AiCapabilityCategory.TRANSCRIPTION),
    TEXT_TO_SPEECH("Text-to-Speech", AiCapabilityCategory.AUDIO_SPEECH)
}

enum class AiStudioSection(val label: String) {
    OVERVIEW("Overview"),
    PROVIDERS("Providers"),
    MODELS("Models"),
    TEXT_STUDIO("Text Studio"),
    IMAGE_STUDIO("Image Studio"),
    VIDEO_STUDIO("Video Studio"),
    AUDIO_STUDIO("Audio Studio"),
    TRANSCRIPTION("Transcription"),
    HISTORY("History"),
    USAGE_DIAGNOSTICS("Usage & Diagnostics")
}

enum class AiProviderType(val label: String, val defaultBaseUrl: String, val defaultModelId: String) {
    OPENAI_COMPATIBLE("OpenAI-Compatible API", "https://api.openai.com/v1", "gpt-4o-mini"),
    GOOGLE_GEMINI("Google Gemini API", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
    CUSTOM_REST("Custom OpenAI / Local Endpoint", "http://localhost:11434/v1", "llama-3.3-70b")
}

enum class AiPricingTier(val label: String) {
    FREE_LOCAL("Free On-Device Local Analysis"),
    LIMITED_FREE_TIER("Provider Free-Tier / Quota"),
    PAID_API("Paid Per-Token / Per-Second API"),
    UNKNOWN_PRICING("Custom Endpoint (Unknown Pricing)")
}

enum class ProviderConnectionStatus(val label: String) {
    UNTESTED("Untested"),
    TESTING("Testing..."),
    CONNECTED("Connected"),
    FAILED("Connection Error")
}

data class AiProviderConfig(
    val id: String,
    val name: String,
    val providerType: AiProviderType = AiProviderType.OPENAI_COMPATIBLE,
    val baseUrl: String,
    val modelId: String,
    val discoveredModels: List<String> = listOf(modelId),
    val supportedCapabilities: Set<AiCapabilityCategory>,
    val pricingTier: AiPricingTier = AiPricingTier.PAID_API,
    val isEnabled: Boolean = true,
    val timeoutSeconds: Int = 30,
    val maxRetries: Int = 1,
    val customHeadersJson: String = "{}",
    val hasConfiguredKey: Boolean = false,
    val connectionStatus: ProviderConnectionStatus = ProviderConnectionStatus.UNTESTED,
    val lastStatusMessage: String = "Configure API key or test endpoint"
)

enum class StructuredEditActionType(val label: String) {
    ADD_MARKER("Add Timeline Marker"),
    CREATE_TEXT_TITLE("Insert Lower-Third Title Layer"),
    INSERT_SUBTITLE_CUE("Insert Synchronized Subtitle Cue"),
    APPLY_CINEMA_GRADE("Apply Teal & Orange Color Grade"),
    REMOVE_SILENCE_GAPS("Ripple-Close Timeline Gaps"),
    ADD_CROSS_DISSOLVES("Add 0.5s Cross Dissolve Transitions")
}

data class StructuredEditOperation(
    val id: String = "act_${UUID.randomUUID().toString().take(6)}",
    val type: StructuredEditActionType,
    val description: String,
    val targetTimeUs: Long = 0L,
    val parameterText: String = ""
)

data class StructuredEditPlan(
    val id: String = "plan_${UUID.randomUUID().toString().take(8)}",
    val title: String,
    val rationale: String,
    val operations: List<StructuredEditOperation>,
    val sourceLabel: String
)

data class AiJobRecord(
    val id: String,
    val capability: AiCapabilityCategory,
    val taskType: AiTaskType = AiTaskType.TEXT_GENERATION,
    val providerId: String = "",
    val providerName: String,
    val modelId: String = "",
    val prompt: String,
    val status: String, // QUEUED, RUNNING, COMPLETED, FAILED
    val outputSummary: String,
    val timestampMs: Long = System.currentTimeMillis()
)

object AiProviderEngine {

    val defaultProviders = listOf(
        AiProviderConfig(
            id = "openai_compatible",
            name = "OpenAI Primary (GPT-4o)",
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-4o-mini",
            discoveredModels = listOf("gpt-4o-mini", "gpt-4o", "o3-mini", "dall-e-3", "whisper-1", "tts-1-hd"),
            supportedCapabilities = setOf(
                AiCapabilityCategory.TEXT_LLM,
                AiCapabilityCategory.IMAGE_GEN,
                AiCapabilityCategory.AUDIO_SPEECH,
                AiCapabilityCategory.TRANSCRIPTION
            ),
            pricingTier = AiPricingTier.PAID_API
        ),
        AiProviderConfig(
            id = "openrouter_compatible",
            name = "OpenRouter Multi-Model Gateway (OpenAI-Compatible)",
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://openrouter.ai/api/v1",
            modelId = "anthropic/claude-3.5-sonnet",
            discoveredModels = listOf(
                "anthropic/claude-3.5-sonnet",
                "deepseek/deepseek-r1",
                "meta-llama/llama-3.3-70b-instruct",
                "luma/ray-2"
            ),
            supportedCapabilities = setOf(
                AiCapabilityCategory.TEXT_LLM,
                AiCapabilityCategory.IMAGE_GEN,
                AiCapabilityCategory.VIDEO_GEN
            ),
            pricingTier = AiPricingTier.PAID_API
        ),
        AiProviderConfig(
            id = "groq_openai_compatible",
            name = "Groq Fast Inference (OpenAI-Compatible)",
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.groq.com/openai/v1",
            modelId = "llama-3.3-70b-versatile",
            discoveredModels = listOf(
                "llama-3.3-70b-versatile",
                "mixtral-8x7b-32768",
                "whisper-large-v3-turbo"
            ),
            supportedCapabilities = setOf(
                AiCapabilityCategory.TEXT_LLM,
                AiCapabilityCategory.TRANSCRIPTION
            ),
            pricingTier = AiPricingTier.LIMITED_FREE_TIER
        ),
        AiProviderConfig(
            id = "google_gemini",
            name = "Google Gemini API (BYOK)",
            providerType = AiProviderType.GOOGLE_GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelId = "gemini-2.5-flash",
            discoveredModels = listOf("gemini-2.5-flash", "gemini-2.5-pro", "imagen-3.0-generate-002"),
            supportedCapabilities = setOf(
                AiCapabilityCategory.TEXT_LLM,
                AiCapabilityCategory.IMAGE_GEN,
                AiCapabilityCategory.TRANSCRIPTION
            ),
            pricingTier = AiPricingTier.LIMITED_FREE_TIER
        ),
        AiProviderConfig(
            id = "elevenlabs_audio",
            name = "ElevenLabs Voice & SFX Adapter",
            providerType = AiProviderType.CUSTOM_REST,
            baseUrl = "https://api.elevenlabs.io/v1",
            modelId = "eleven_multilingual_v2",
            discoveredModels = listOf("eleven_multilingual_v2", "eleven_turbo_v2_5"),
            supportedCapabilities = setOf(AiCapabilityCategory.AUDIO_SPEECH),
            pricingTier = AiPricingTier.PAID_API
        )
    )

    val defaultTaskRouting: Map<AiTaskType, String> = mapOf(
        AiTaskType.TEXT_GENERATION to "openai_compatible",
        AiTaskType.SCRIPT_WRITING to "openrouter_compatible",
        AiTaskType.SUBTITLE_ASSISTANCE to "groq_openai_compatible",
        AiTaskType.IMAGE_GENERATION to "openai_compatible",
        AiTaskType.VIDEO_GENERATION to "openrouter_compatible",
        AiTaskType.AUDIO_GENERATION to "elevenlabs_audio",
        AiTaskType.SPEECH_TO_TEXT to "groq_openai_compatible",
        AiTaskType.TEXT_TO_SPEECH to "openai_compatible"
    )

    /**
     * Creates a new independent OpenAI-compatible or custom provider configuration with a unique UUID.
     */
    fun createNewProviderConfig(
        name: String,
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        capabilities: Set<AiCapabilityCategory>,
        pricingTier: AiPricingTier = AiPricingTier.PAID_API,
        timeoutSeconds: Int = 30,
        customHeadersJson: String = "{}"
    ): AiProviderConfig {
        val cleanName = name.trim().ifBlank { "OpenAI-Compatible Endpoint" }
        val cleanUrl = baseUrl.trim().trimEnd('/').ifBlank { providerType.defaultBaseUrl }
        val cleanModel = modelId.trim().ifBlank { providerType.defaultModelId }
        val safeCaps = capabilities.ifEmpty { setOf(AiCapabilityCategory.TEXT_LLM) }
        return AiProviderConfig(
            id = "prov_${UUID.randomUUID().toString().replace("-", "").take(10)}",
            name = cleanName,
            providerType = providerType,
            baseUrl = cleanUrl,
            modelId = cleanModel,
            discoveredModels = listOf(cleanModel),
            supportedCapabilities = safeCaps,
            pricingTier = pricingTier,
            isEnabled = true,
            timeoutSeconds = timeoutSeconds.coerceIn(5, 180),
            customHeadersJson = customHeadersJson.ifBlank { "{}" },
            hasConfiguredKey = false
        )
    }

    /**
     * Duplicates a provider configuration without copying secret API keys.
     */
    fun duplicateProviderConfig(source: AiProviderConfig): AiProviderConfig {
        return source.copy(
            id = "prov_${UUID.randomUUID().toString().replace("-", "").take(10)}",
            name = "${source.name} (Copy)",
            hasConfiguredKey = false,
            connectionStatus = ProviderConnectionStatus.UNTESTED,
            lastStatusMessage = "Duplicated configuration (API key not copied for security)"
        )
    }

    /**
     * Constructs a verifiable HTTP request for an OpenAI-compatible or Gemini endpoint.
     * Exposed for deterministic unit testing and real network execution.
     */
    fun buildChatCompletionRequest(
        provider: AiProviderConfig,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String
    ): Request {
        val cleanBase = provider.baseUrl.trim().trimEnd('/')
        val isGemini = provider.providerType == AiProviderType.GOOGLE_GEMINI ||
            provider.id == "google_gemini" ||
            cleanBase.contains("generativelanguage.googleapis.com")

        val builder = Request.Builder()
        applyCustomHeaders(builder, provider.customHeadersJson)

        return if (isGemini) {
            val url = "$cleanBase/models/${provider.modelId}:generateContent?key=${apiKey.trim()}"
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "$systemPrompt\n\n$userPrompt"))
                        })
                    })
                })
            }
            builder
                .url(url)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        } else {
            val url = "$cleanBase/chat/completions"
            val payload = JSONObject().apply {
                put("model", provider.modelId)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                })
                put("temperature", 0.4)
            }
            builder
                .url(url)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }
    }

    /**
     * Constructs a GET /models request for discovering available models on an OpenAI-compatible or Gemini endpoint.
     */
    fun buildModelsDiscoveryRequest(
        provider: AiProviderConfig,
        apiKey: String
    ): Request {
        val cleanBase = provider.baseUrl.trim().trimEnd('/')
        val isGemini = provider.providerType == AiProviderType.GOOGLE_GEMINI ||
            provider.id == "google_gemini" ||
            cleanBase.contains("generativelanguage.googleapis.com")

        val builder = Request.Builder()
        applyCustomHeaders(builder, provider.customHeadersJson)

        return if (isGemini) {
            builder
                .url("$cleanBase/models?key=${apiKey.trim()}")
                .get()
                .build()
        } else {
            val req = builder.url("$cleanBase/models").get()
            if (apiKey.isNotBlank()) {
                req.header("Authorization", "Bearer ${apiKey.trim()}")
            }
            req.build()
        }
    }

    private fun applyCustomHeaders(builder: Request.Builder, customHeadersJson: String) {
        if (customHeadersJson.isBlank() || customHeadersJson == "{}") return
        runCatching {
            val obj = JSONObject(customHeadersJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (k.isNotBlank() && v.isNotBlank()) {
                    builder.header(k.trim(), v.trim())
                }
            }
        }
    }

    /**
     * Tests connectivity and authentication against the configured provider endpoint.
     * Never fakes a connection success.
     */
    suspend fun testProviderConnection(
        provider: AiProviderConfig,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (provider.baseUrl.isBlank() || (!provider.baseUrl.startsWith("http://") && !provider.baseUrl.startsWith("https://"))) {
            return@withContext Result.failure(
                IllegalArgumentException("Invalid Base URL '${provider.baseUrl}'. Must start with https:// or http://.")
            )
        }
        val isLocalEndpoint = provider.baseUrl.contains("localhost") || provider.baseUrl.contains("127.0.0.1")
        if (apiKey.isBlank() && !isLocalEndpoint) {
            return@withContext Result.failure(
                IllegalStateException("Missing API key for '${provider.name}'. Configure your key in Keystore first.")
            )
        }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(provider.timeoutSeconds.coerceIn(5, 60).toLong(), TimeUnit.SECONDS)
                .readTimeout(provider.timeoutSeconds.coerceIn(5, 60).toLong(), TimeUnit.SECONDS)
                .build()
            val request = buildModelsDiscoveryRequest(provider, apiKey)
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val safeBody = CredentialVault.redactSecrets(bodyStr.take(200), listOf(apiKey))
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}: $safeBody")
                    )
                }
                val discovered = parseModelsFromResponse(bodyStr, provider.modelId)
                Result.success(discovered)
            }
        } catch (t: Throwable) {
            val safeErr = CredentialVault.redactSecrets(t.message ?: "Connection failed", listOf(apiKey))
            Result.failure(IllegalStateException("Endpoint test failed: $safeErr"))
        }
    }

    fun parseModelsFromResponse(rawJson: String, fallbackModelId: String): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val json = JSONObject(rawJson)
            if (json.has("data")) {
                val arr = json.getJSONArray("data")
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    val id = item?.optString("id")?.trim().orEmpty()
                    if (id.isNotBlank()) result.add(id)
                }
            } else if (json.has("models")) {
                val arr = json.getJSONArray("models")
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    val name = item?.optString("name")?.substringAfterLast('/')?.trim().orEmpty()
                    if (name.isNotBlank()) result.add(name)
                }
            }
        }
        if (fallbackModelId.isNotBlank() && fallbackModelId !in result) {
            result.add(0, fallbackModelId)
        }
        return result.distinct()
    }

    /**
     * Executes a real network request to the configured BYOK provider.
     * NEVER simulates a fake AI response if the API key is missing or the network call fails.
     */
    suspend fun executeByokTextPrompt(
        provider: AiProviderConfig,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!provider.isEnabled) {
            return@withContext Result.failure(
                IllegalStateException("Provider '${provider.name}' is currently disabled. Enable it in AI Studio > Providers.")
            )
        }
        val isLocalEndpoint = provider.baseUrl.contains("localhost") || provider.baseUrl.contains("127.0.0.1")
        if (apiKey.isBlank() && !isLocalEndpoint) {
            return@withContext Result.failure(
                IllegalStateException(
                    "No API key configured for ${provider.name}. Enter your BYOK API key in the AI Provider Center, or use On-Device Content Intelligence."
                )
            )
        }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(provider.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(provider.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()
            val request = buildChatCompletionRequest(provider, apiKey, systemPrompt, userPrompt)
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val safeBody = CredentialVault.redactSecrets(bodyStr.take(240), listOf(apiKey))
                    return@withContext Result.failure(
                        IllegalStateException("Provider HTTP ${response.code}: $safeBody")
                    )
                }
                val json = JSONObject(bodyStr)
                val text = if (json.has("candidates")) {
                    json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                } else {
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }
                Result.success(text.trim())
            }
        } catch (t: Throwable) {
            val redactedMsg = CredentialVault.redactSecrets(t.message ?: "Network error", listOf(apiKey))
            Result.failure(IllegalStateException("AI request failed: $redactedMsg"))
        }
    }

    /**
     * Performs genuine local on-device timeline & media analysis to generate an inspectable
     * StructuredEditPlan without uploading user media or faking cloud AI results.
     */
    fun generateOnDeviceStructuredEditPlan(
        project: KlipsupProject,
        workflowFocus: String
    ): StructuredEditPlan {
        val ops = mutableListOf<StructuredEditOperation>()
        val videoClips = project.tracks.filter { it.type == TrackType.VIDEO }.flatMap { it.clips }.sortedBy { it.startTimeUs }

        // 1. Detect timeline gaps between sequential clips
        for (i in 0 until videoClips.size - 1) {
            val gapUs = videoClips[i + 1].startTimeUs - videoClips[i].endTimeUs
            if (gapUs > 150_000L) {
                ops.add(
                    StructuredEditOperation(
                        type = StructuredEditActionType.REMOVE_SILENCE_GAPS,
                        description = "Close ${gapUs / 1000}ms dead-air gap after '${videoClips[i].label}'",
                        targetTimeUs = videoClips[i].endTimeUs
                    )
                )
            }
        }

        // 2. Propose chapter markers at clip transitions
        videoClips.forEachIndexed { idx, clip ->
            if (idx > 0) {
                ops.add(
                    StructuredEditOperation(
                        type = StructuredEditActionType.ADD_MARKER,
                        description = "Add scene boundary marker at '${clip.label}' (${project.settings.fps.formatTimecode(clip.startTimeUs)})",
                        targetTimeUs = clip.startTimeUs,
                        parameterText = "Scene ${idx + 1}: ${clip.label}"
                    )
                )
            }
        }

        // 3. Propose lower-third title if none exists at intro
        val hasIntroTitle = project.tracks.any { it.type == TrackType.TEXT && it.clips.isNotEmpty() }
        if (!hasIntroTitle || workflowFocus.contains("Title", ignoreCase = true)) {
            ops.add(
                StructuredEditOperation(
                    type = StructuredEditActionType.CREATE_TEXT_TITLE,
                    description = "Insert broadcast Lower-Third Title at 00:00:00:15",
                    targetTimeUs = 500_000L,
                    parameterText = "${project.name} — Master Sequence"
                )
            )
        }

        // 4. Propose cross-dissolve transitions & cinema color grade
        ops.add(
            StructuredEditOperation(
                type = StructuredEditActionType.ADD_CROSS_DISSOLVES,
                description = "Apply 500ms smooth Cross Dissolve transitions across ${videoClips.size} video cuts"
            )
        )
        ops.add(
            StructuredEditOperation(
                type = StructuredEditActionType.APPLY_CINEMA_GRADE,
                description = "Apply 'Teal & Orange Cinema' 3D LUT (75% intensity) + +0.15 EV exposure boost"
            )
        )

        return StructuredEditPlan(
            title = "Automated Sequence Polish ($workflowFocus)",
            rationale = "Analyzed ${project.tracks.size} tracks and ${videoClips.size} video clips (${project.settings.fps.formatTimecode(project.totalDurationUs)} total duration). All operations are non-destructive and support single-step Undo.",
            operations = ops,
            sourceLabel = "On-Device Timeline Intelligence Engine"
        )
    }

    /**
     * Applies a validated StructuredEditPlan to the project deterministically.
     */
    fun applyStructuredEditPlan(project: KlipsupProject, plan: StructuredEditPlan): KlipsupProject {
        var current = project
        for (op in plan.operations) {
            when (op.type) {
                StructuredEditActionType.ADD_MARKER -> {
                    val marker = TimelineMarker(
                        id = "mkr_${UUID.randomUUID().toString().take(6)}",
                        timeUs = op.targetTimeUs,
                        label = op.parameterText.ifBlank { "AI Scene Marker" }
                    )
                    current = current.copy(markers = (current.markers + marker).sortedBy { it.timeUs })
                }
                StructuredEditActionType.CREATE_TEXT_TITLE -> {
                    val textTrack = current.tracks.firstOrNull { it.type == TrackType.TEXT }
                    if (textTrack != null) {
                        val newClip = TimelineClip(
                            id = "clip_txt_${UUID.randomUUID().toString().take(6)}",
                            mediaAssetId = null,
                            label = op.parameterText.ifBlank { "Studio Lower Third" },
                            trackId = textTrack.id,
                            startTimeUs = op.targetTimeUs,
                            durationUs = 3_500_000L,
                            textData = TextLayerData(
                                text = op.parameterText.ifBlank { "KLIPSUP STUDIO" },
                                animationType = TextAnimationType.LOWER_THIRD_SLIDE
                            ),
                            badgeColorHex = TrackType.TEXT.badgeColorHex
                        )
                        current = current.copy(
                            tracks = current.tracks.map {
                                if (it.id == textTrack.id) it.copy(clips = (it.clips + newClip).sortedBy { c -> c.startTimeUs }) else it
                            }
                        )
                    }
                }
                StructuredEditActionType.INSERT_SUBTITLE_CUE -> {
                    val cue = SubtitleCue(
                        id = "sub_${UUID.randomUUID().toString().take(6)}",
                        startTimeUs = op.targetTimeUs,
                        endTimeUs = op.targetTimeUs + 2_500_000L,
                        text = op.parameterText.ifBlank { "Automated caption segment" }
                    )
                    current = current.copy(subtitles = (current.subtitles + cue).sortedBy { it.startTimeUs })
                }
                StructuredEditActionType.APPLY_CINEMA_GRADE -> {
                    current = current.copy(
                        tracks = current.tracks.map { track ->
                            if (track.type == TrackType.VIDEO) {
                                track.copy(
                                    clips = track.clips.map { clip ->
                                        clip.copy(
                                            colorGrade = clip.colorGrade.copy(
                                                enabled = true,
                                                exposure = 0.15f,
                                                contrast = 1.08f,
                                                lutName = "Teal & Orange Cinema",
                                                lutIntensity = 0.75f
                                            )
                                        )
                                    }
                                )
                            } else track
                        }
                    )
                }
                StructuredEditActionType.ADD_CROSS_DISSOLVES -> {
                    val transition = ClipTransition(type = TransitionType.CROSS_DISSOLVE, durationUs = 500_000L)
                    current = current.copy(
                        tracks = current.tracks.map { track ->
                            if (track.type == TrackType.VIDEO) {
                                track.copy(
                                    clips = track.clips.map { clip ->
                                        clip.copy(transitionIn = transition, transitionOut = transition)
                                    }
                                )
                            } else track
                        }
                    )
                }
                StructuredEditActionType.REMOVE_SILENCE_GAPS -> {
                    current = current.copy(
                        tracks = current.tracks.map { track ->
                            if (track.type == TrackType.VIDEO && !track.isLocked) {
                                var cursorUs = 0L
                                val packed = track.clips.sortedBy { it.startTimeUs }.map { clip ->
                                    val updated = clip.copy(startTimeUs = cursorUs)
                                    cursorUs += clip.durationUs
                                    updated
                                }
                                track.copy(clips = packed)
                            } else track
                        }
                    )
                }
            }
        }
        return current.copy(updatedAtEpochMs = System.currentTimeMillis())
    }
}
