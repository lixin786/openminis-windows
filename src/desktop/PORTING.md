# Porting notes — OpenMinis Android → Windows desktop

Source: `src/android/app` of [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) at commit `b4c0661`
(v1.14, Kotlin + Jetpack Compose, ~580 main `.kt` files). Target: Compose Multiplatform for Desktop 1.7.3,
Kotlin 2.1.0, JVM 17, Windows x64. License: GPLv3, same as upstream; upstream attribution is kept and every new
or modified file carries a header saying so.

## Strategy

1. **Reuse platform-neutral code verbatim** under its original package (`com.openminis.app.*`) so it can be
   re-synced from upstream with a plain copy.
2. **Shim the handful of Android framework APIs** that code touches (`android.util.Log`, `android.util.Base64`,
   `android.graphics.Bitmap/BitmapFactory`, `android.os.Build/Debug`) with tiny JVM implementations
   (`app/src/main/kotlin/android/**`). Bitmap is backed by `java.awt.image.BufferedImage` + ImageIO, so the
   upstream image down-scaling (`ImageBudget`) still works.
3. **Rewrite the Android-bound layers** (ViewModel, Room, Activity/Compose screens, services) as a compact
   desktop layer in `com.openminis.desktop.*`.

## What was ported

### Copied unchanged (≈12k lines)

| Upstream path | Notes |
|---|---|
| `provider/LLMProvider.kt`, `JsonExt`, `ToolPairing`, `ToolJsonRepair`, `ImageBudget`, `ThinkingLevelCatalog`, `OpenCodeSessionHeader` | provider contract + helpers |
| `provider/anthropic/AnthropicProvider.kt` | Messages API, SSE streaming, tools, thinking |
| `provider/openai/OpenAIProvider.kt` (+ `ReasoningDetails`, `ResponsesToolPairing`, `ThinkPrefixStreamParser`) | Chat Completions + Responses API; used for OpenAI, OpenRouter, xAI, DeepSeek and any OpenAI-compatible endpoint |
| `provider/gemini/GeminiProvider.kt` | streamGenerateContent, function calls + thought signatures |
| `provider/thinking/*` | thinking-level wire-format resolver |
| `data/model/*` (except one edit below) | `LLMMessage`, `LLMStreamChunk`, `AgentContentPart`, `AgentToolDefinition`, `LLMModel` catalog, … |
| `agent/ToolLoopDetector.kt` | repeated-tool-call / loop circuit breaker, used by the desktop agent loop |
| `debug/LLMRequestLog.kt`, `diagnostics/MemorySnapshot.kt`, `diagnostics/LargeAllocProbe.kt` | referenced by providers |
| `SkillRepository.parseSkillMd` | SKILL.md frontmatter parser (copied into `desktop/skills/SkillLoader.kt`) |
| `data/ContextPolicy.kt` (ContextPolicy + ContextSizeMeter), `data/ContextOverflowGuard.kt`, `data/BPETokenizer.kt` | batch 1 — compaction thresholds, token estimate + calibration, overflow detection |
| `shared/TextSegmenter.kt`, `shared/SystemSegmentEngine.kt` | batch 1 — word segmentation facade used by memory search |
| `ui/chat/ImageInputPreflight.kt` | batch 1 — image-rejection hint classifier |

### Minimal edits

| File | Change |
|---|---|
| `data/model/SubAgentDefinition.kt` | `displayName(context)` / `displayDescription(context)` lost their `Context` parameter (no Android string resources). |
| `provider/UserAgentOverride.kt` | UA string says `Desktop` instead of `Android`. |
| `data/db/ProviderThinkingRuleEntity.kt` | Room annotations stripped (plain data class). |
| `backup/BackupFormat.kt` | only the two ISO-8601 serializers the data model needs were extracted. |
| `logging/AppLogger.kt` | rewritten: daily log files in `%APPDATA%\OpenMinis\logs`, same API surface. |
| `shared/JiebaEngine.kt` | same class/API; backend swapped from CppJieba JNI to the pure-Java `com.huaban:jieba-analysis:1.0.2` (Apache-2.0); `posTag` returns empty (documented "no POS signal"). |
| shims `android/content/Context.kt`, `android/icu/text/BreakIterator.kt` (typealias to `java.text.BreakIterator`) | let the segmenter/tokenizer files above compile unchanged. |
| `network/NetworkMonitor.kt`, `data/FastModePrefs.kt`, `tools/VisionGroupResolver.kt`, `auth/ClaudeOAuthManager.kt`, `BuildConfig.kt` | stubs exposing only the members the providers reference. |

### Upstream tests

35 upstream JVM unit-test classes for the provider layer (`src/test/java/.../provider/**`, ~290 tests) were
copied and run unchanged on the desktop build (JUnit 4 via the vintage engine) — they all pass. Test files that
reference un-ported classes (Codex/Copilot OAuth, models.dev catalog, voice providers, model-release index …)
or that read Android source paths from disk (`DeepSeekFlashThinkingTest`, `ModelOverridesRefreshMergeTest`)
were not copied; `ReasoningDetailsTest` got one extra candidate path for the desktop source layout.

Batch 1 added 6 more upstream test classes, verbatim: `data/ContextOverflowGuardTest`, `data/ContextPolicyUserCapTest`,
`data/InLoopContextPolicyTest`, `shared/TextSegmenterLanguageDetectionTest`, `ui/chat/ImageInputPreflightTest`, and
`data/CompactSplitPredicateTest` (one import line changed: `ChatViewModel.Companion.shouldSplitOnError` →
`com.openminis.desktop.agent.Compaction.shouldSplitOnError`).

## What was replaced

| Android | Desktop |
|---|---|
| `Context.filesDir`, SharedPreferences, DataStore, EncryptedSharedPreferences | JSON files under `%APPDATA%\OpenMinis` (`settings.json`, atomic temp-file + rename writes). **API keys are stored in plain text** in the user profile — Windows DPAPI/Credential Manager integration is a TODO. |
| Room chat database (`data/db`, `data/repository`, `data/session`) | one JSON file per conversation (`conversations/<id>.json`, kotlinx-serialization). No migrations, no FTS search (sidebar search is an in-memory substring match). |
| `ChatViewModel` (17.5k lines: streaming, tools, compaction, fallback, offload, voice…) | `desktop/agent/AgentEngine.kt`: streams via the upstream `LLMProvider`, accumulates text / thinking / reasoning_content / tool calls, executes tools sequentially, feeds results back as `AgentContentPart.ToolUse/ToolResult`, guarded by the upstream `ToolLoopDetector` and a max-rounds limit; cancellable (Stop / Esc). Batch 1: measures every outgoing request with the upstream `ContextSizeMeter` (calibrated per model from reported usage) and follows the upstream `ContextPolicy.inLoopStep` table (compact / send / stop); the ViewModel's compaction functions are ported as pure functions in `desktop/agent/Compaction.kt`, its retry/fallback decision in `desktop/agent/FallbackChain.kt`, its attachment shaping in `desktop/core/Attachments.kt`. |
| PRoot (Android) / iSH (iOS) Linux sandbox, `sandbox/**`, terminal | `desktop/tools/ShellRunner.kt`: runs on the host via **PowerShell** (`pwsh.exe` if on PATH, else `powershell.exe`, `-EncodedCommand` UTF-16LE to avoid quoting bugs, UTF-8 output), **cmd.exe**, or **WSL** (`wsl.exe --cd <ws> -e bash -lc`). Working directory = `%USERPROFILE%\OpenMinis\workspace` (configurable). Timeout with process-tree kill, 30k-char head+tail output truncation. **Confirm-before-run toggle (default on)**; file writes/edits outside the workspace also need approval. There is no isolation — this is the biggest behavioural difference from Android. |
| `tools/FileReadTool`, `FileWriteTool`, `FileEditTool`, `MemoryTools` | `desktop/tools/ToolRegistry.kt` with the same tool names and semantics on host paths (`file_read`, `file_write`, `file_edit`, `list_dir`, `memory_write`, `memory_get`). |
| Skills DB + GitHub/zip import | folder scan of `%APPDATA%\OpenMinis\skills\*\SKILL.md` and `<workspace>\.skills\*\SKILL.md`; the system prompt lists name/description/path and the model reads the SKILL.md with `file_read`. No import UI, no per-session overrides. |
| Memory (daily logs + GLOBAL.md) | same file layout in `%APPDATA%\OpenMinis\memory`; GLOBAL.md + last two daily logs are injected into the system prompt. `memory_get` (batch 1) is a ranked search (`desktop/tools/MemorySearch.kt`): upstream `TextSegmenter` (Jieba for Han runs, BreakIterator otherwise) + Han bigrams, BM25 over log entries / GLOBAL.md sections, recency boost. Note: upstream `MemoryRepository.getMemory` is still a plain all-keywords substring match — jieba upstream is used by voice correction, not memory — so this is a desktop-side upgrade built from upstream parts. |
| Jetpack Compose screens (`ui/**`, 113k lines) | new desktop UI (`desktop/ui/*`): resizable sidebar + chat pane, Material 3, Markdown via `com.mikepenz:multiplatform-markdown-renderer-m3`, tool cards, thinking fold, approval dialog, settings window, menu bar. |
| Provider configuration (`ProviderRepository`, `ProviderFactory`, multi-instance, model catalogs from models.dev) | `desktop/core/DesktopProviderFactory.kt`: one credential set per provider kind, model id free text + suggestions from the upstream `LLMModel` catalog. |

## 桌面端不适用 (not applicable on desktop — intentionally not ported, not TODO)

| Upstream feature | Why it does not apply |
|---|---|
| Notifications, foreground service, Live Updates / Dynamic Island, power / wake locks | Android lifecycle constructs; a desktop window stays alive by itself. |
| Accessibility automation (AccessibilityService) | Android-only API. |
| Share sheet, deep links, home-screen widgets | Android intents / AppWidget. |
| Speech (ASR/TTS, VAD, ONNX Runtime voice mode) | Out of scope for the desktop port by user decision. |
| Debug server (127.0.0.1:5321), crash reporter | Upstream dev tooling. |

## Safety model

The desktop build runs commands **directly on the Windows host — there is no sandbox** (accepted by the user; the
earlier "Windows Sandbox / AppContainer / WSL-only isolation" idea is dropped). The safety net is
**"Ask before running every shell command" — ON by default**, plus approval for file writes/edits outside the workspace.

## Roadmap (tracked)

Status: ✅ done · 🟡 partial · ⬜ not started

### P0
| # | Item | Status | Notes |
|---|---|---|---|
| P0-1 | Context compaction | ✅ batch 1 | Upstream algorithms ported (v2 anchor markers, warm-up of last 3 user turns with >1000-char tool results pruned, warm-up trimmed to budget, summary prompt + BEGIN/END framing verbatim, split-on-size-error ≤3 deep / ≤6 calls, 120 s idle timer, 15 min cap, compaction through the fallback chain, context-overflow 400 → ratio raise → compact → retry). Auto-trigger via upstream `ContextPolicy` tiers (or proportional thresholds with a Settings window override); manual via toolbar button, Chat → Compact context (Ctrl+Shift+K) or `/compact`; divider with "View summary" / "Undo". Differences: upstream's "auto-compact OFF ⇒ prompt the user before send" dialog is replaced by a Settings toggle (default ON); the GH#352 "offload the single oversized part to disk" self-heal is not ported (desktop has no offload store) — overflow goes straight to compaction. |
| P0-2 | Image / attachment input | ✅ batch 1 | Drag & drop onto the window, Ctrl+V (files from Explorer, screenshots/bitmaps; plain text still pastes as text), paperclip button / Chat → Attach files. Chips with thumbnail + remove. Copies stored under `%APPDATA%\OpenMinis\attachments\<chatId>\`, referenced from the conversation JSON. Images: upstream `ImageBudget.downscaleForModel` + `applyMessageBudget` per message, `planRequestBudget` per request, upstream caption / downscale note; non-vision models get the upstream placeholder (per-provider "Image input: Auto/On/Off" setting). Text files are inlined (`<attached-file>`, cap configurable, default 60k chars); other files are listed in upstream's `<user-attached-files>` block with host paths for the tools. PDF/Office files are NOT parsed (path only). |
| P0-3 | Model fallback chain | ✅ batch 1 | Settings → Fallback models (add / reorder / remove; each uses that provider's key). Upstream rules: transient (network / 5xx / overloaded 529 / empty stream) → same-model retries 1 s/2 s/4 s, then next model; 429, 5xx, InvalidApiKey and ProviderError (upstream `isFallbackable`) → next model immediately; "fall back on any error" = upstream `FallbackStrategy.always`; members without a key are skipped and reported. Desktop deviation: network errors also fall back after retries (upstream does not). The switch is turn-local: the next turn starts on the active model again (upstream rebinds the session to the fallback model). |
| P0-4 | Memory retrieval upgrade | ✅ batch 1 | See "Memory" row above. Not every keyword must match any more; results ranked; `limit` parameter. No embeddings/vector search. |

### P1
| # | Item | Status | Notes |
|---|---|---|---|
| P1-1 | MCP stdio client (`mcp/`) | ⬜ | stdio transport is natural on desktop; upstream also has HTTP/OAuth MCP. |
| P1-2 | Sub-agents (`agent/jobs`, SubAgentDefinition) | ⬜ | data model already ported. |
| P1-3 | Skill import UI (GitHub / zip) + per-conversation skill override | ⬜ | today: folder scan only. |
| P1-4 | Scheduled tasks via Windows Task Scheduler (`schtasks`), no resident background service | ⬜ | app would be launched headless by the task (`--run-task <id>`). |

### P2
| # | Item | Status | Notes |
|---|---|---|---|
| P2-1 | Built-in browser tool (JCEF or Playwright) | ⬜ | upstream is WebView-based. |
| P2-2 | Image / audio generation UI + vision groups | ⬜ | provider code present; `VisionGroupResolver` is a stub. |
| P2-3 | OAuth logins (Claude Code, Codex/ChatGPT, Copilot) | ⬜ | Copilot device flow and OpenRouter PKCE look possible with public values; Claude Code / Codex need client ids & customization values that are not in the public mirror — impossible to reproduce faithfully; API keys stay the supported path. |
| P2-4 | KaTeX math rendering | ⬜ | needs a WebView/JCEF or a native LaTeX renderer. |
| P2-5 | i18n (upstream 22 locales) | ⬜ | UI strings are English literals today. |
| P2-6 | Backup / restore + update check | ⬜ | upstream uses rclone + Android storage. |

### P3
| # | Item | Status | Notes |
|---|---|---|---|
| P3-1 | API keys via Windows DPAPI / Credential Manager | ⬜ | today: plain JSON in the user profile. |
| P3-2 | Real MSI/EXE build on Windows + code-signing plan | 🟡 | `packageMsi`/`packageExe` + GitHub Actions workflow exist (unsigned, SmartScreen warns); signing not set up. |

### Also not ported (no priority assigned)
Cloud offload, web apps (`webapp/`), tray icon, terminal UI, models.dev catalog refresh, multi-instance providers / model groups UI.

## Hotfix 1 (after the first real-Windows test, zh-CN Windows 11 + Temurin 17)

| Issue | Fix |
|---|---|
| Streamed assistant text decoded with the platform charset (GBK on zh-CN JDK 17) → mojibake | Upstream providers read SSE with `InputStreamReader(byteStream())` (Android defaults to UTF-8). One-line `[desktop]` edit in `OpenAIProvider`, `AnthropicProvider`, `GeminiProvider`: `Charsets.UTF_8`. Audit: every other decode in the desktop code is Kotlin stdlib (UTF-8 by default) or now explicit; process output goes through `core/TextDecoding.kt` (BOM → UTF-16LE heuristic for wsl.exe → strict UTF-8 → per-line fallback to `native.encoding`, e.g. GBK); `file_read` decodes UTF-8 or falls back to the legacy code page and says so. `:desktop:test` runs the WHOLE suite with `-Dfile.encoding=GBK`; new tests stream Chinese + emoji in 1-byte chunks for all three providers. MSI/EXE get `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`. |
| JVM TLS to a gateway hangs where curl works (system proxy not used) | `desktop/net/DesktopHttp.kt`: Settings → Network (System = env `HTTPS_PROXY`/`HTTP_PROXY`/`ALL_PROXY`/`NO_PROXY`, else the Windows proxy via `java.net.useSystemProxies`; None; Manual HTTP or SOCKS5 + auth + bypass list; loopback always direct in env mode). Applied with a `[desktop]` `.let(DesktopHttp::harden)` on each provider's OkHttpClient. Connect + proxy tunnel + TLS watchdog (default 20 s) cancels the call → NetworkError → retries → fallback, with a clear message appended to the error. |
| Window opened larger than a 1280×800 HiDPI screen | `core/WindowPlacement.kt`: clamp to the usable area (logical px), center, remember position/size in `window.json`, restore only if ≥60 % lies on a current monitor. Settings window clamped too. |
| Keyboard use of the approval dialog | Enter = Allow, Esc = Deny, Ctrl+Enter / A = Always allow, Allow button focused, hints on buttons, main window brought to front. Settings: Esc = cancel, Ctrl+S = save. Tab/Shift+Tab leave text fields. contentDescription on key controls. Screen readers need Java Access Bridge enabled (`jabswitch -enable`); Compose's accessibility tree is exposed through it — not verified with Narrator/NVDA here. |

## Third-party additions (desktop)

| Library | Version | License | Use |
|---|---|---|---|
| com.huaban:jieba-analysis (+ commons-lang3) | 1.0.2 | Apache-2.0 | Chinese segmentation for memory search (replaces upstream's MIT cppjieba JNI). |
