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
    /** In memory only on Windows: settings.json keeps "" and [apiKeyStore] = "dpapi" (P3-1, secrets.json). */
    val apiKey: String = "",
    /** P3-1 marker: "dpapi" = the key is in secrets.json; "" = no key (or a non-Windows dev build). */
    val apiKeyStore: String = "",
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
    /**
     * [security] Run allowlisted read-only commands (ls, cat, dir, Get-ChildItem, …) without asking,
     * only when they contain no shell metacharacters and stay inside the workspace (security/CommandGuard).
     * Files outside the workspace are always refused (the former "confirm writes outside the workspace"
     * setting is gone: an old settings.json key is ignored).
     */
    val autoApproveReadOnlyShell: Boolean = true,
    /** [security] Settings migrations already applied (see [SettingsStore.migrate]). */
    val securityRevision: Int = 0,
    val shell: ShellKind = ShellKind.POWERSHELL,
    val shellTimeoutSeconds: Int = 120,
    val workspaceDir: String = "",
    val memoryEnabled: Boolean = true,
    val skillsEnabled: Boolean = true,
    val darkTheme: Boolean? = null,
    /** [immersive] Undecorated windows with a self-drawn title bar (IMMERSIVE-WINDOW.md); off = system title bar. */
    val immersiveWindow: Boolean = true,
    /** HEADLESS-SCHEDULER.md: a Windows scheduled task runs due tasks while the app is closed (off by default: it changes system state). */
    val headlessScheduler: Boolean = false,
    /** Its wake-up interval in minutes (1 / 5 / 10 / 15). */
    val headlessIntervalMinutes: Int = 5,
    /** Appearance (ui/Appearance): accent palette id, text size factor, UI / code font family ("" = built-in default). */
    val accent: String = "indigo",
    val fontScale: Float = 1f,
    val uiFont: String = "",
    val monoFont: String = "",
    /** [P2-4] Render LaTeX math in chat bubbles (and ask the model for `$…$` / `$$…$$`); off = formulas shown as source. */
    val renderMath: Boolean = true,
    /** UI language: "system" (follow Windows), "en", "zh-CN", "zh-TW" — see i18n/AppLanguage. */
    val language: String = "system",
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
    /** [P1-3] Skill folder ids switched off in Settings › Skills (kept on disk, left out of the prompt). */
    val disabledSkills: List<String> = emptyList(),
    /** [batch13] Settings › System › Phone link: the read-only MCP server phones connect to (off by default). */
    val remoteMcp: com.openminis.desktop.remote.RemoteMcpSettings = com.openminis.desktop.remote.RemoteMcpSettings(),
) {
    fun provider(kind: ProviderKind = activeProvider): ProviderSettings = providers[kind] ?: ProviderSettings()
    /** The model the next turn starts on. */
    fun primaryModel(): ModelRef = ModelRef(activeProvider, provider().effectiveModel(activeProvider))
    /** Primary first, then the configured fallbacks (duplicates removed, order kept). */
    fun modelChain(): List<ModelRef> = (listOf(primaryModel()) + fallbackModels).distinct()
    fun withProvider(kind: ProviderKind, ps: ProviderSettings) = copy(providers = providers + (kind to ps))
    fun workspace(): File = workspaceDir.trim().takeIf { it.isNotEmpty() }?.let(::File) ?: AppPaths.defaultWorkspace()
}

class SettingsStore(
    private val file: File,
    /** P3-1: DPAPI-backed store on Windows; null = keys stay in settings.json (non-Windows dev builds only). */
    private val secrets: com.openminis.desktop.secrets.SecretStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** A stored secret that could not be decrypted at load (other Windows user / machine, damaged file). */
    data class UnreadableSecret(val id: String, val label: String, val reason: String)

    /** Filled by [load]; the blobs are kept (never silently deleted) until the user re-enters or forgets them. */
    @Volatile var unreadable: List<UnreadableSecret> = emptyList(); private set
    /** Set when moving plain-text keys into DPAPI failed at load; settings.json was left untouched. */
    @Volatile var migrationError: String? = null; private set
    /** id → plaintext last written, so an unchanged key is not re-encrypted on every save. Memory only. */
    private val written = HashMap<String, String>()

    @Synchronized fun load(): AppSettings {
        val loaded = try {
            if (file.exists()) json.decodeFromString(AppSettings.serializer(), file.readText(Charsets.UTF_8)) else AppSettings()
        } catch (e: Exception) {
            com.openminis.app.logging.AppLogger.error("Settings", "settings.json unreadable, using defaults", e)
            AppSettings()
        }
        val migrated = migrate(loaded)
        if (migrated != loaded && file.exists())
            com.openminis.app.logging.AppLogger.warning("Settings", "security migration r${loaded.securityRevision} → r${migrated.securityRevision}: shell confirmation re-enabled")
        val (revealed, plainOnDisk) = reveal(migrated)
        if ((migrated != loaded || plainOnDisk) && file.exists()) {
            try {
                saveLocked(revealed)
                if (plainOnDisk) com.openminis.app.logging.AppLogger.info("Settings", "plain-text secrets moved into DPAPI storage and removed from settings.json")
            } catch (e: Exception) {
                migrationError = e.message ?: e.javaClass.simpleName
                com.openminis.app.logging.AppLogger.error("Settings", "could not move plain-text secrets into DPAPI storage: $migrationError")
            }
        }
        return revealed
    }

    /** Fills the in-memory secrets from secrets.json. Returns the settings and whether plaintext was found on disk. */
    private fun reveal(s: AppSettings): Pair<AppSettings, Boolean> {
        val bad = mutableListOf<UnreadableSecret>()
        var plain = false
        fun open(id: String, label: String, value: String, marker: String): String {
            if (value.isNotEmpty()) { if (secrets != null) plain = true; return value }
            if (marker != STORE_DPAPI) return value
            val store = secrets ?: run { bad += UnreadableSecret(id, label, "DPAPI is only available on Windows"); return "" }
            return try { store.get(id).also { written[id] = it } } catch (e: Exception) {
                bad += UnreadableSecret(id, label, e.message ?: e.javaClass.simpleName); ""
            }
        }
        val providers = s.providers.mapValues { (kind, ps) -> ps.copy(apiKey = open(providerKeyId(kind), kind.label, ps.apiKey, ps.apiKeyStore)) }
        val n = s.network
        val network = n.copy(proxyPassword = open(PROXY_PASSWORD_ID, "Proxy password", n.proxyPassword, n.proxyPasswordStore))
        unreadable = bad
        return s.copy(providers = providers, network = network) to plain
    }

    companion object {
        const val SECURITY_REVISION = 1
        const val STORE_DPAPI = "dpapi"
        const val PROXY_PASSWORD_ID = "network.proxyPassword"
        fun providerKeyId(kind: ProviderKind) = "provider.${kind.name}.apiKey"

        /**
         * r1 (security batch): before it, "Always allow" on a shell approval permanently switched
         * `confirmShellCommands` off — a persisted shell allow, which is no longer permitted. That
         * value cannot be told apart from the Settings toggle, so confirmation is switched back on
         * once; the user can turn it off again in Settings (metacharacter / interpreter commands
         * still ask even then). Old `confirmWritesOutsideWorkspace` keys are ignored (hard refusal now).
         */
        fun migrate(s: AppSettings): AppSettings {
            var m = s
            if (m.securityRevision < 1) m = m.copy(confirmShellCommands = true)
            return if (m.securityRevision < SECURITY_REVISION) m.copy(securityRevision = SECURITY_REVISION) else m
        }
    }

    @Synchronized fun save(settings: AppSettings) = saveLocked(settings)

    /**
     * Settings › "Forget keys that cannot be decrypted": drops their blobs and markers. Nothing else does that —
     * a key that cannot be read on this machine is kept until the user re-enters it or forgets it here.
     */
    @Synchronized fun forgetUnreadable(current: AppSettings) {
        unreadable = emptyList()
        saveLocked(current)
    }

    /**
     * With DPAPI: secrets.json is written first (one atomic write), then settings.json without any plaintext.
     * If encryption fails, nothing is written and the exception propagates (the old files stay as they were).
     */
    private fun saveLocked(settings: AppSettings) {
        file.parentFile?.mkdirs()
        val store = secrets
        if (store == null) { atomicWrite(file, json.encodeToString(AppSettings.serializer(), settings)); return }
        val keepUnreadable = unreadable.map { it.id }.toMutableSet()
        val put = LinkedHashMap<String, String>()
        val stored = HashSet<String>()
        fun seal(id: String, value: String): String {
            if (value.isNotEmpty()) {
                if (written[id] != value) put[id] = value
                keepUnreadable.remove(id)   // re-entered: the new key replaces the unreadable blob
                stored += id
                return STORE_DPAPI
            }
            if (id in keepUnreadable) { stored += id; return STORE_DPAPI }
            return ""
        }
        val providers = settings.providers.mapValues { (kind, ps) ->
            ps.copy(apiKey = "", apiKeyStore = seal(providerKeyId(kind), ps.apiKey))
        }
        val n = settings.network
        val network = n.copy(proxyPassword = "", proxyPasswordStore = seal(PROXY_PASSWORD_ID, n.proxyPassword))
        // Unreadable blobs of providers no longer listed are kept as well.
        stored += keepUnreadable
        val remove = store.ids() - stored
        store.update(put, remove)
        put.forEach { (k, v) -> written[k] = v }
        remove.forEach { written.remove(it) }
        unreadable = unreadable.filter { it.id in keepUnreadable }
        atomicWrite(file, json.encodeToString(AppSettings.serializer(), settings.copy(providers = providers, network = network)))
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
