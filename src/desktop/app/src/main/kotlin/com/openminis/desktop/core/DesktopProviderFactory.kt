// OpenMinis Desktop — builds the upstream (ported) LLMProvider implementations from settings.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider

object DesktopProviderFactory {

    /** Well-known model ids for the model dropdown (the user can type any id). */
    fun suggestedModels(kind: ProviderKind): List<String> = when (kind) {
        ProviderKind.ANTHROPIC -> LLMModel.allAnthropic.map { it.id }
        ProviderKind.OPENAI -> LLMModel.allOpenAI.map { it.id }.filterNot { it.startsWith("codex-mini") }
        ProviderKind.GEMINI -> LLMModel.allGemini.map { it.id }
        ProviderKind.OPENROUTER -> listOf("anthropic/claude-sonnet-4.5", "openai/gpt-4o-mini", "google/gemini-2.5-flash", "deepseek/deepseek-chat")
        ProviderKind.XAI -> listOf("grok-4", "grok-3-mini")
        ProviderKind.DEEPSEEK -> listOf("deepseek-chat", "deepseek-reasoner")
        ProviderKind.OPENAI_COMPATIBLE -> listOf(kind.defaultModel)
    }

    /** Reuse the upstream catalog entry (context window, reasoning flags) when the id is known. */
    fun resolveModel(kind: ProviderKind, id: String): LLMModel {
        val catalog = LLMModel.allAnthropic + LLMModel.allOpenAI + LLMModel.allGemini
        catalog.firstOrNull { it.id == id }?.let { return it }
        val providerName = when (kind) {
            ProviderKind.ANTHROPIC -> "Anthropic"
            ProviderKind.GEMINI -> "Google"
            ProviderKind.OPENAI -> "OpenAI"
            else -> kind.label
        }
        val reasoning = id.contains("reason") || id.startsWith("o3") || id.startsWith("o4") ||
            id.startsWith("gpt-5") || id.contains("claude") || id.contains("gemini-2.5") || id.contains("gemini-3")
        return LLMModel(id, id, providerName, supportsReasoning = if (reasoning) true else null)
    }

    /** [resolveModel] plus the user's per-provider image-input override (Settings → provider). */
    fun resolveModel(kind: ProviderKind, id: String, imageInput: Boolean?): LLMModel {
        val m = resolveModel(kind, id)
        return when (imageInput) {
            null -> m
            true -> m.copy(inputModalities = listOf("text", "image"))
            false -> m.copy(inputModalities = listOf("text"))
        }
    }

    fun create(settings: AppSettings, kind: ProviderKind = settings.activeProvider): LLMProvider =
        create(settings, ModelRef(kind, settings.provider(kind).effectiveModel(kind)))

    /** Provider for one link of the fallback chain: [ref]'s provider credentials + [ref]'s model id. */
    fun create(settings: AppSettings, ref: ModelRef): LLMProvider {
        val kind = ref.provider
        val ps = settings.provider(kind)
        val key = ps.apiKey.trim()
        val base = ps.effectiveBaseUrl(kind)
        val model = resolveModel(kind, ref.model.trim().ifBlank { ps.effectiveModel(kind) }, ps.imageInput)
        if (key.isEmpty() && kind != ProviderKind.OPENAI_COMPATIBLE) {
            throw IllegalStateException("No API key configured for ${kind.label}. Open Settings (Ctrl+,) to add one.")
        }
        return when (kind) {
            ProviderKind.ANTHROPIC -> AnthropicProvider(apiKey = key, model = model, basePath = base)
            ProviderKind.GEMINI -> GeminiProvider(apiKey = key, model = model, basePath = base)
            ProviderKind.OPENROUTER -> OpenAIProvider(
                apiKey = key, model = model, basePath = base,
                extraHeaders = mapOf("HTTP-Referer" to "https://github.com/OpenMinis/OpenMinis", "X-Title" to "OpenMinis Desktop"),
            )
            ProviderKind.OPENAI, ProviderKind.XAI, ProviderKind.DEEPSEEK ->
                OpenAIProvider(apiKey = key, model = model, basePath = base)
            ProviderKind.OPENAI_COMPATIBLE ->
                OpenAIProvider(apiKey = key.ifEmpty { "sk-local" }, model = model, basePath = base)
        }
    }
}
