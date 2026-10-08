// OpenMinis Desktop — settings persisted as JSON (replaces Android SharedPreferences/DataStore).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class ProviderKind(val label: String, val defaultBaseUrl: String, val defaultModel: String) {
    ANTHROPIC("Anthropic", "https://api.anthropic.com", "claude-sonnet-4-6"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5"),
    XAI("xAI", "https://api.x.ai/v1", "grok-4"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    /** Any OpenAI Chat Completions–compatible endpoint (Ollama, LM Studio, vLLM, relays…). */
    OPENAI_COMPATIBLE("OpenAI-compatible (custom)", "http://localhost:11434/v1", "llama3.1"),
}

@Serializable
data class ProviderSettings(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    /** Image input for this provider's models: null = auto (upstream catalog / provider default), true/false = override. */
    val imageInput: Boolean? = null,
) {
    fun effectiveBaseUrl(kind: ProviderKind) = baseUrl.trim().ifBlank { kind.defaultBaseUrl }.trimEnd('/')
    fun effectiveModel(kind: ProviderKind) = model.trim().ifBlank { kind.defaultModel }
}

@Serializable
enum class ShellKind(val label: String) {
    POWERSHELL("PowerShell"),
    WSL("WSL (bash via wsl.exe)"),
    CMD("cmd.exe"),
}

@Serializable
data class AppSettings(
    val activeProvider: ProviderKind = ProviderKind.ANTHROPIC,
    val providers: Map<ProviderKind, ProviderSettings> = emptyMap(),
    val systemPrompt: String = "",
    val thinkingLevel: String = "OFF",
    val maxOutputTokens: Int = 8192,
    val maxToolRounds: Int = 40,
    val toolsEnabled: Boolean = true,
    /** Ask before every shell command (default on — the desktop has no sandbox). */
    val confirmShellCommands: Boolean = true,
    /** Ask before writing/editing files outside the workspace. */
    val confirmWritesOutsideWorkspace: Boolean = true,
    val shell: ShellKind = ShellKind.POWERSHELL,
    val shellTimeoutSeconds: Int = 120,
    val workspaceDir: String = "",
    val memoryEnabled: Boolean = true,
    val skillsEnabled: Boolean = true,
    val darkTheme: Boolean? = null,
    /**
     * Ordered fallback chain tried after the active provider/model (upstream ModelGroup with
     * RoutingStrategy.fallback). Each entry uses that provider's credentials from [providers].
     */
    val fallbackModels: List<ModelRef> = emptyList(),
    /** Fall back on ANY error, not just retryable ones (upstream FallbackStrategy.always). */
    val fallbackOnAnyError: Boolean = false,
    /** Compact automatically when the request nears the context window (upstream autoCompactOnThreshold). */
    val autoCompact: Boolean = true,
    /** Context window in tokens; 0 = auto (upstream catalog + ModelContextWindowHeuristic). */
    val contextWindowOverride: Int = 0,
    /** Text attachments larger than this are not inlined; the model gets the path instead. */
    val maxInlineTextChars: Int = 60_000,
    /** Settings → Network (proxy, connect/TLS timeout). */
    val network: com.openminis.desktop.net.NetworkSettings = com.openminis.desktop.net.NetworkSettings(),
) {
    fun provider(kind: ProviderKind = activeProvider): ProviderSettings = providers[kind] ?: ProviderSettings()
    /** The model the next turn starts on. */
    fun primaryModel(): ModelRef = ModelRef(activeProvider, provider().effectiveModel(activeProvider))
    /** Primary first, then the configured fallbacks (duplicates removed, order kept). */
    fun modelChain(): List<ModelRef> = (listOf(primaryModel()) + fallbackModels).distinct()
    fun withProvider(kind: ProviderKind, ps: ProviderSettings) = copy(providers = providers + (kind to ps))
    fun workspace(): File = workspaceDir.trim().takeIf { it.isNotEmpty() }?.let(::File) ?: AppPaths.defaultWorkspace()
}

class SettingsStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): AppSettings = try {
        if (file.exists()) json.decodeFromString(AppSettings.serializer(), file.readText()) else AppSettings()
    } catch (e: Exception) {
        com.openminis.app.logging.AppLogger.error("Settings", "settings.json unreadable, using defaults", e)
        AppSettings()
    }

    fun save(settings: AppSettings) {
        file.parentFile?.mkdirs()
        atomicWrite(file, json.encodeToString(AppSettings.serializer(), settings))
    }
}

/** Write via temp file + rename so a crash never leaves a half-written JSON file. */
fun atomicWrite(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    try {
        java.nio.file.Files.move(
            tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: Exception) {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
