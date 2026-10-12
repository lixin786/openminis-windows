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
| `Context.filesDir`, SharedPreferences, DataStore, EncryptedSharedPreferences | JSON files under `%APPDATA%\OpenMinis` (`settings.json`, atomic temp-file + rename writes). API keys and the proxy password are encrypted with **Windows DPAPI** (CurrentUser scope) in `secrets.json`; `settings.json` keeps only a marker (P3-1). |
| Room chat database (`data/db`, `data/repository`, `data/session`) | one JSON file per conversation (`conversations/<id>.json`, kotlinx-serialization). No migrations, no FTS search (sidebar search is an in-memory substring match). |
| `ChatViewModel` (17.5k lines: streaming, tools, compaction, fallback, offload, voice…) | `desktop/agent/AgentEngine.kt`: streams via the upstream `LLMProvider`, accumulates text / thinking / reasoning_content / tool calls, executes tools sequentially, feeds results back as `AgentContentPart.ToolUse/ToolResult`, guarded by the upstream `ToolLoopDetector` and a max-rounds limit; cancellable (Stop / Esc). Batch 1: measures every outgoing request with the upstream `ContextSizeMeter` (calibrated per model from reported usage) and follows the upstream `ContextPolicy.inLoopStep` table (compact / send / stop); the ViewModel's compaction functions are ported as pure functions in `desktop/agent/Compaction.kt`, its retry/fallback decision in `desktop/agent/FallbackChain.kt`, its attachment shaping in `desktop/core/Attachments.kt`. |
| PRoot (Android) / iSH (iOS) Linux sandbox, `sandbox/**`, terminal | `desktop/tools/ShellRunner.kt`: runs on the host via **PowerShell** (`pwsh.exe` if on PATH, else `powershell.exe`, `-EncodedCommand` UTF-16LE to avoid quoting bugs, UTF-8 output), **cmd.exe**, or **WSL** (`wsl.exe --cd <ws> -e bash -lc`). Working directory = `%USERPROFILE%\OpenMinis\workspace` (configurable). Timeout with process-tree kill, 30k-char head+tail output truncation. **Confirm-before-run toggle (default on)** plus the security gates below (denylist hard block, metacharacter guard, read-only allowlist, hard path confinement). There is no isolation — this is the biggest behavioural difference from Android. |
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
**"Ask before running every shell command" — ON by default**, plus the five gates of the security batch (next section).

## Security hardening (batch4, README "安全加固")

All in `com.openminis.desktop.security` (pure, no UI) and wired in `tools/ToolRegistry.kt`, `ui/AppState.kt`, `mcp/McpManager.kt`.

| # | Gate | Status | Implementation |
|---|---|---|---|
| 1 | Denylist hard block | ✅ | `CommandGuard.inspect` → `BLOCK`: never offered for approval, tool result `Refused by the safety denylist: <reason>`. POSIX: recursive `rm` / `find -delete` on `/`, `/*`, `~`, `$HOME`, bare `*`, top-level system dirs (paths normalised first, `/tmp/../` → `/`); `mkfs*`; `dd of=/dev/…` (incl. `of = /dev/`); `> /dev/sdX` (sd/hd/vd/xvd/nvme/mmcblk/disk/md/dm/loop); `shred` / `wipefs` / `fdisk` / `parted` on `/dev/`; fork bombs; `shutdown` / `poweroff` / `reboot` / `halt` / `init 0|6` / `systemctl reboot`; recursive `chmod` / `chown` on root/home; `mv` of or onto root/home. Windows (case-insensitive): `rd|rmdir /s`, `del|erase` on drive roots, `%USERPROFILE%`, `%SystemRoot%`, `C:\Windows`, `C:\Users`, `Program Files`, UNC shares; `Remove-Item` and aliases `rm` / `ri` / `del` / `rd` / `rmdir` with `-r` / `-Recurse` on the same targets (also `$env:USERPROFILE`, `~`, `\\?\C:\`); `format C:`; `Format-Volume` / `Clear-Disk` / `Initialize-Disk` / `Remove-Partition`; `diskpart`; `cipher /w`; `bcdedit` / `bootrec`; `Stop-Computer` / `Restart-Computer`; `shutdown /s|/r` (only `shutdown /a` passes); `vssadmin delete|resize shadows`, `wmic shadowcopy delete`, `wbadmin delete`; `reg delete HKLM…`. **Nested payloads** are rescanned with the same rules, recursively (max 8 levels, deeper = block): `sh|bash|zsh|dash|ksh -c`, `eval`, `exec`, `$( … )`, backticks, `cmd /c|/k|/r`, `powershell|pwsh -Command|-c|<bare>`, `iex` / `Invoke-Expression`, `-EncodedCommand|-enc|-ec|-e` (base64 UTF-16LE decoded; not base64 / odd length / U+FFFD = **block**), and wrapper prefixes (`sudo`, `env`, `nohup`, `timeout N`, `xargs`, `wsl`, `start`, …). `${IFS}` / `$IFS` are expanded first; a recursive delete whose target is a dangerous location built from a variable (`rm -rf /$x`, `~/$x`, `"$ROOT/"*`, `%X%\*`) is blocked (fail-closed). Quoting is read both the POSIX way (`\"` escapes) and the cmd/PowerShell way (backslash = path) and the union is checked. MCP command lines go through the same denylist before any launch (trusted or not). |
| 2 | Metacharacter guard | ✅ | `> < | ; & \` $ ( )` and CR/LF in the command ⇒ every no-confirmation path is off: no read-only auto-approval, no session memory, and the dialog is shown **even when "Ask before running every shell command" is off**. Interpreter invocations and commands with nested payloads are treated the same way. `echo key >> ~/.ssh/authorized_keys` → dialog. |
| 3 | Read-only allowlist | ✅ (setting, default ON) | Settings › Tools & shell › "Run read-only commands without asking". `ls cat head tail wc grep egrep fgrep date which pwd whoami uname file stat basename dirname id uptime nproc md5sum sha1sum sha256sum cmp diff tree du df echo dir type where findstr ver vol Get-ChildItem/gci Get-Content/gc Select-String/sls Get-Location Get-Date Get-Item Get-Command Measure-Object Write-Output Get-FileHash` — only with no metacharacter, no `%`, no path outside the working directory (absolute / `~` / drive / UNC / `..` / `X:` provider paths such as `Env:`), and none of `-exec -delete -o --output -i -s -w -f -r -OutFile -Wait -Stream …`. Not on the list on purpose: find, sed, awk, xargs, env, printenv, git, sort, hostname. |
| 4 | Path hard confinement | ✅ | `PathGuard.resolve`: `file_read` / `list_dir` = workspace + **read-only** `%APPDATA%\OpenMinis\skills` and `%APPDATA%\OpenMinis\attachments` (the system prompt / attachment blocks hand the model those host paths; `<workspace>\.skills` is inside the workspace); `file_write` / `file_edit` / shell cwd = workspace only. Outside = **hard refusal, no dialog** (the former "Ask before writing files outside the workspace" setting and its "Always allow" are removed; an old `confirmWritesOutsideWorkspace` key is ignored). Realpath of the nearest existing ancestor (no link following to find it, then `toRealPath()` = symlinks, junctions, case and 8.3 names), dangling links refused; Windows: `\\?\` stripped, `\\.\` / GLOBALROOT refused, UNC refused unless the workspace itself is UNC (checked lexically, before any network access), `C:foo` and `\foo` refused, ADS `file:stream` refused, reserved names (CON, NUL, COM1 …) and names ending in `.` / space refused, case-insensitive compare. A workspace that is a drive root is refused. Memory files are reached through the memory tools, not file tools. Known limits: hard links; TOCTOU between check and write. The shell itself can still `cd ..` — that is what the confirmation is for. |
| 5 | Fail-closed | ✅ | `FailClosedApprover`: exception in the dialog / approver, no main window (`AppState.windowAvailable`, headless AWT), or no answer within 10 min ⇒ deny. The UI approver serialises dialogs (a second request waits instead of replacing the first). Main chat, sub agents, scheduled runs, MCP launches and the `scheduled_task create` confirmation all go through it; `ToolRegistry` wraps any approver it is given. |
| 6 | Attack regression tests | ✅ | `security/AttackRegressionTest` (19), `CommandGuardTest` (8), `PathGuardTest` (14) — see README for the list. Symlink cases create real links in a temp dir and skip only if the OS forbids it. |
| 7 | Interpreters never remembered | ✅ | Nothing about shell approvals is persisted any more. The old "Always allow" on a shell dialog (which switched confirmation off **permanently**) is gone; instead "Allow for this session" remembers the exact command string in memory only, and is not offered for interpreters (`sh bash zsh python python3 node perl ruby php pwsh powershell cmd wscript cscript mshta rundll32 osascript wsl …`, also behind `sudo` / `env` / `VAR=x`), metacharacter commands, nested payloads or denylisted commands. Migration: settings r1 turns `confirmShellCommands` back ON once (an old "Always allow" left it off and cannot be told apart from the toggle). MCP follows the same rule (user decision 2026-10-09): the fingerprint binds only the command line, so for `python server.py` / `npx pkg` whoever can edit the script or package would get permanent code execution. `security/McpTrustPolicy` decides: interpreters, package runners (npx npm pnpm yarn bunx uvx uv pipx poetry tsx …), scripts by extension or `#!` shebang, Windows commands without `.exe`/`.com` (they start through `cmd.exe /c`), non-PE `.exe` files and symlinks resolving to any of those ⇒ confirmed on **every start**, no session or disk trust, no "Always allow" offered; stale `mcp_trust.json` entries for them are dropped on load; Settings › Connect is consent for one start. Only a direct native executable keeps the permanent SHA-256 fingerprint allow (a changed command line asks again). Consequence: an interpreter-based server that crashes or idles out asks again when restarted. |
| 8 | PROVENANCE.md | ✅ | `PROVENANCE.md` next to `LICENSE`: upstream repo, locked commit `b4c0661d…` (v1.14), GPL v3 obligations, third-party licenses. |

Deviations / judgment calls: the allowlist also refuses arguments that leave the workspace (reading `C:\…` with `type`
asks); `echo` is allowlisted (it can only print without metacharacters); the 10-minute approval timeout is a fixed
constant; the "confirm every command" toggle still exists (README requirement 1) but no longer lets metacharacter /
interpreter commands through silently. Not done: per-argument PowerShell AST parsing (the guard is a lexer, so the
denylist is a safety net, not a proof — confirmation stays the primary control).

## Roadmap (tracked)

Status: ✅ done · 🟡 partial · ⬜ not started — table refreshed 2026-10-11 (ui-batch after 13.3 + P3-1).
"Verified" = accepted by the user on real Windows 11; "unit tests + code" = not hand-tested on Windows.

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
| P1-1 | MCP stdio client (`mcp/`) | ✅ | batch2 — see *P1-1 MCP* below. MCP **client** over HTTP / OAuth: not done (`url` entries are kept, not started). Phone interop went the other way: the desktop is an MCP **server** (batch13, below). |
| P1-2 | Sub-agents (`agent/jobs`, SubAgentDefinition) | ✅ | batch2 — see *P1-2 Sub agents* below. |
| P1-3 | Skill import UI (GitHub / zip) + per-conversation skill override | 🟡 | Import ✅ batch3 — see *P1-3 Skills* below. Per-conversation **skill** override not done (batch9 did per-conversation **MCP** only). |
| P1-4 | Scheduled tasks | ✅ | batch3 in-app timer + headless runs while the app is closed (`HEADLESS-SCHEDULER.md`, `docs/p1/P1-4-HEADLESS-DELIVERY.md`; needs real-machine check) — see *P1-4 Scheduled tasks* below. |

### P2
| # | Item | Status | Notes |
|---|---|---|---|
| P2-1 | Built-in browser tool (JCEF or Playwright) | ⬜ | upstream is WebView-based. |
| P2-2 | Image / audio generation UI + vision groups | ⬜ | provider code present; `VisionGroupResolver` is a stub. |
| P2-3 | OAuth logins (Claude Code, Codex/ChatGPT, Copilot) | ⬜ | Copilot device flow and OpenRouter PKCE look possible with public values; Claude Code / Codex need client ids & customization values that are not in the public mirror — impossible to reproduce faithfully; API keys stay the supported path. |
| P2-4 | KaTeX math rendering | ✅ | JLaTeXMath 1.0.7 (pure Java, no WebView) in `ui/math/`: `$…$`, `\(…\)` inline (per-formula `InlineTextContent`, AboveBaseline = baseline-true), `$$…$$`, `\[…\]` block (centred, horizontal scroll + scrollbar when wide); paragraphs / lists / headings / quotes / tables; price guard, code skipped, unclosed = literal while streaming, ⚠ + source on parse errors; bitmaps at device pixels. Literal `$` is escaped for the GFM parser (it used to drop `$5 … $10` spans). A formula never crosses a block boundary (heading / table row: same line; else up to a blank line or the next block start). Settings › Appearance › "Render math" (default on): on = `SystemPrompt.MATH_HINT` asks for `$…$` / `$$…$$`; off = formulas shown as source, no hint. Spike: branch `spike/p2-4-math`. |
| P2-5 | i18n | ✅ (3 locales) | batch5 `61a45f0` + batch6 `3ca66f1`, verified: properties tables en / zh-CN / zh-TW, Settings › Language (system / en / zh-CN / zh-TW, live switch, follows Windows by default), `I18nTest` guards same keys, placeholders, no untranslated Chinese, no hard-coded English in the UI. Only the UI is translated; text sent to the model stays English. Upstream's other 19 locales are not ported. |
| P2-6 | Backup / restore + update check | ✅ (needs real-machine check) | one zip + system file dialog, keys off by default, pre-restore backup + typed `restore` + rename-aside rollback; About › Check for updates (GitHub Releases via proxy settings, no download). Spec `BACKUP-RESTORE.md`, delivery `docs/p2/P2-6-DELIVERY.md`. |

### P3
| # | Item | Status | Notes |
|---|---|---|---|
| P3-1 | API keys via Windows DPAPI | ✅ (unit tests + code) | DPAPI `CryptProtectData`, CurrentUser scope, app entropy, UI forbidden (JNA). Keys and proxy password → `secrets.json` (ciphertext only); `settings.json` keeps `apiKeyStore` / `proxyPasswordStore` = `"dpapi"`. First start moves plain-text keys over and clears them; if encryption fails nothing is rewritten and a toast says so. A blob another Windows user / PC cannot decrypt is listed in Settings › Models and a startup toast, and kept until the key is re-entered or "Forget" is pressed (never silently cleared). Credential Manager not used. Real-DPAPI test runs on Windows only. The phone-link token followed in the next batch (P3-1b). |
| P3-1b | Phone-link token via DPAPI | ✅ (unit tests + code; real DPAPI test on Windows) | Token format unchanged (32 random bytes, base64url). Stored as `remote-mcp-token.dpapi` (`{version,scheme,blob}`); an existing plain `remote-mcp-token` is migrated at app start (even with the phone link off), keeping the same token so paired phones keep working, then overwritten and deleted. Undecryptable file (other account/PC): new token, old file kept as `.unreadable-<ts>`, notice in Settings › Phone link. Encryption failure: plain file left untouched / new token kept in memory only, never written in plain text. Non-Windows keeps the plain file. |
| P3-2 | Real MSI/EXE build on Windows + code-signing plan | 🟡 | `packageMsi` / `packageExe` verified on Chinese Windows 11 (batch6: ASCII-only jpackage metadata fixes "Input length = 1" without any UTF-8 environment variable). Unsigned (SmartScreen warns); signing not set up. |

### Desktop batches after P1 (UI, window, phone link)
| Batch | Item | Status | Notes |
|---|---|---|---|
| batch4 | Security hardening | ✅ verified | See *Security hardening* above (532/0/0/0 on Windows). |
| batch7 | Session management: rename, pin, search | ✅ verified | `b429bf6`. F2 / right-click / Ctrl+F not hand-tested (unit tests + code). |
| batch8 | Tool cards, edit & resend, jump to latest | ✅ verified | `32981eb`. |
| batch9 | Per-conversation MCP panel | ✅ verified | `0cbe9db`. |
| batch10 | Appearance: theme, accent, text size, fonts | ✅ | `0af4461`. |
| batch11 / 11.1 | UI refresh 1: risk-tiered approvals, 4-state tool cards | ✅ verified | `6fe75c6`, `6ca3241` (UI-REFRESH.md). |
| batch12 / 12.1 | UI refresh 2: sidebar, model pill, empty state, settings rail, Lucide icons, Inter + JetBrains Mono | ✅ verified | `df4728f`, `b2ea105`. Noto Sans SC not bundled (accepted). |
| immersive 1 | Frameless windows, self-drawn title bar, 8-way resize, work-area maximize, `window.json` geometry, escape switch | ✅ verified | `582302a` (IMMERSIVE-WINDOW.md, docs/immersive/). Taskbar-button minimize while focused: pending check (README). |
| immersive 2 | DWM rounded corners + shadow | ✅ (unit tests + code; needs real-machine check) | JNA: window procedure subclassed, frame style bits added so DWM draws the shadow, WM_NCCALCSIZE keeps client = window (AWT insets stay 0); `DWMWA_WINDOW_CORNER_PREFERENCE` ROUND floating / DONOTROUND maximized, shadow margins off when maximized. Win10: shadow only, square corners and the 1 px outline. SC_MAXIMIZE / Win+Up go to the app's own maximize; SC_KEYMENU swallowed (no native system menu beside the app's). Off switch: `-Dopenminis.dwm=false`, or the immersive setting. |
| 13.1 | Phone link: MCP server skeleton (LAN-only bind, Bearer, sessions, lockout, 2025-06-18 streamable HTTP) | ✅ verified | `8b72ee7` (MCP-SERVER.md, docs/batch13/). |
| 13.2 | Five read-only phone tools, PathGuard, sensitive-file denylist, audit log | ✅ verified | `c25a117`. |
| 13.3 | Connections list + disconnect (10-min refusal), audit log view, strings | ✅ verified (650/0/0/0) | `f2a416b`. The two UI sections: unit tests + code. Phone `add --url` field test skipped by the user. |

### Also not ported (no priority assigned)
Cloud offload, web apps (`webapp/`), tray icon, terminal UI, models.dev catalog refresh, multi-instance providers / model groups UI.

## Hotfix 1 (after the first real-Windows test, zh-CN Windows 11 + Temurin 17)

| Issue | Fix |
|---|---|
| Streamed assistant text decoded with the platform charset (GBK on zh-CN JDK 17) → mojibake | Upstream providers read SSE with `InputStreamReader(byteStream())` (Android defaults to UTF-8). One-line `[desktop]` edit in `OpenAIProvider`, `AnthropicProvider`, `GeminiProvider`: `Charsets.UTF_8`. Audit: every other decode in the desktop code is Kotlin stdlib (UTF-8 by default) or now explicit; process output goes through `core/TextDecoding.kt` (BOM → UTF-16LE heuristic for wsl.exe → strict UTF-8 → per-line fallback to `native.encoding`, e.g. GBK); `file_read` decodes UTF-8 or falls back to the legacy code page and says so. `:desktop:test` runs the WHOLE suite with `-Dfile.encoding=GBK`; new tests stream Chinese + emoji in 1-byte chunks for all three providers. MSI/EXE get `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`. |
| JVM TLS to a gateway hangs where curl works (system proxy not used) | `desktop/net/DesktopHttp.kt`: Settings → Network (System = env `HTTPS_PROXY`/`HTTP_PROXY`/`ALL_PROXY`/`NO_PROXY`, else the Windows proxy via `java.net.useSystemProxies`; None; Manual HTTP or SOCKS5 + auth + bypass list; loopback always direct in env mode). Applied with a `[desktop]` `.let(DesktopHttp::harden)` on each provider's OkHttpClient. Connect + proxy tunnel + TLS watchdog (default 20 s) cancels the call → NetworkError → retries → fallback, with a clear message appended to the error. |
| Window opened larger than a 1280×800 HiDPI screen | `core/WindowPlacement.kt`: clamp to the usable area (logical px), center, remember position/size in `window.json`, restore only if ≥60 % lies on a current monitor. Settings window clamped too. |
| Keyboard use of the approval dialog | Enter = Allow, Esc = Deny, Ctrl+Enter / A = Always allow, Allow button focused, hints on buttons, main window brought to front. Settings: Esc = cancel, Ctrl+S = save. Tab/Shift+Tab leave text fields. contentDescription on key controls. Screen readers need Java Access Bridge enabled (`jabswitch -enable`); Compose's accessibility tree is exposed through it — not verified with Narrator/NVDA here. |

## Hotfix 2 (HTTP/2 stream reset at +3004 ms)

| Issue | Fix |
|---|---|
| OkHttp over HTTP/2: `200 text/event-stream` headers arrive, then `StreamResetException: stream was reset: CANCEL` before any body byte, while `curl --http1.1` streams fine. | **Settings → Network → HTTP protocol**: *Auto* (default: HTTP/2 when the server offers it) or *HTTP/1.1 only* (every hardened client: providers, future MCP HTTP / skill import). In *Auto*, an HTTP/2 stream reset makes `FallbackChain` mark that **host** HTTP/1.1-only for the session (`DesktopHttp.downgradeToHttp1`), rebuild the provider and retry **immediately**, without spending the 1 s/2 s/4 s same-model budget and without jumping to a fallback model; a second reset on the same host goes through the normal retry/fallback path. Providers pass their base URL to `DesktopHttp.harden(client, basePath)` (one-line `[desktop]` edit in the three providers). |
| Can't tell who reset the stream (OkHttp reports a local `call.cancel()` on an HTTP/2 stream with the very same text, `stream was reset: CANCEL`). | `WatchdogListener` logs and appends to the turn error: protocol, ms since call start / since response headers, body bytes, and **reset origin: LOCAL call.cancel() (by whom) / PEER**. Test `localCancelAfterHeadersLooksExactlyLikeTheReport…` proves the local case produces the exact reported message. |
| Connect/TLS timeout field clamped every keystroke to ≥3 (editing "20" passed through "2" → saved as **3 s**). | No per-keystroke clamp; the watchdog treats values < 3 as the 20 s default, caps at 300, and is additionally disarmed as soon as request headers go out (pooled/coalesced connections). |

## Hotfix 3 (proxy cutting streams)

Real-Windows finding after hotfix2: two independent ~3 s killers — the local proxy (Clash 127.0.0.1:7900) cut SSE at ~3.0 s (curl via proxy: 0 bytes; curl direct: 10.1 s, 58 events), and the gateway reset HTTP/2 streams (`reset origin: PEER`). Working config there: Proxy = Direct + HTTP/1.1 only.

| Issue | Fix |
|---|---|
| After the HTTP/1.1 downgrade the stream still dies through the proxy (`EOFException`, body bytes=0); nothing tells the user that the proxy is the problem. | `WatchdogListener` now records **every** failed call (`DesktopHttp.StreamFailure`: protocol, route DIRECT / proxy, ms since start / headers, body complete?, origin LOCAL/PEER). When the response headers arrived, then the peer cut the body **through a proxy**, `FallbackChain` shows once per turn: "…cut after its headers while going through HTTP proxy /127.0.0.1:7900 … set Settings → Network → Proxy: Direct, or add <host> to the proxy bypass list", then continues with the normal retry / fallback. Not shown for direct routes, local cancels, HTTP errors, or failures older than the turn. No automatic proxy bypass: going direct can break users who need the proxy to reach the host. |
| Reset / failure diagnostic was only in the log. | The diagnostic line (and the proxy hint when it applies) is appended to the turn's error text (copyable / reportable), and the HTTP/1.1 downgrade notice carries the reset line too. |

## P1-3 Skills (batch3)

- Settings › Skills: every installed skill with an on/off switch (`AppSettings.disabledSkills`, folder ids; off = kept on disk, left out of the prompt), detail (version, path, description, body), open folder, delete (confirmed; only folders directly under a skills root).
- Import, ported from upstream `SkillRepository`: pasted SKILL.md (`importFromContent`, replace by slug), `.zip` / `.skill` archive with SKILL.md at the root or one folder deep (`importFromArchive`, siblings extracted, `..` / absolute paths refused and reported, 50 MB cap), GitHub `tree` / `blob` / raw URL → raw SKILL.md + sibling files through the contents API (depth ≤ 5, one retry, partial download reported), any other URL = a direct SKILL.md. Desktop addition: import a local folder (dot-folders such as `.git` skipped). Downloads use the app's proxy settings. All file I/O is explicit UTF-8.
- A name with no ASCII letters / digits (e.g. Chinese only) gets the folder id `skill-<hash>` (upstream's slug would be empty).

## P1-4 Scheduled tasks (batch3)

- Model: upstream `ScheduledTask` / `PrefilledToolCall` copied verbatim (`com.openminis.app.scheduled`), stored as the same JSON rows in `%APPDATA%\OpenMinis\scheduled_tasks.json`.
- Triggers: time of day once / daily / weekdays / chosen days with a from / until date, after a delay, every N minutes (optionally N times), when another task finishes (`{{result}}` = its answer). Targets: a new chat each time, or a follow-up in an existing chat. Per-task model (`PROVIDER/model`) and reasoning override. Run records (newest first, 50 kept) with "Open chat".
- **Timer:** an in-process timer (1 s tick, `DesktopScheduler`) runs tasks while OpenMinis is open. **While the app is closed**, tasks run only when *Settings › Scheduled tasks › Run while the app is closed* is on (off by default): one Windows scheduled task (`OpenMinis\Scheduled tasks`, interactive-only, no admin) starts the app with `--run-due-tasks` every 1/5/10/15 min; it runs what is due headless and exits (HEADLESS-SCHEDULER.md). The two never fire together (`run-due-tasks.lock`). With the switch off, the old rule holds and the Scheduled tasks window says so. Catch-up = upstream's: an overdue after / interval fires once at start; a time-of-day slot missed while the app was closed is skipped — unless background runs are on, where a slot up to 30 min old (persisted `checkedUpTo` + `lastFiredAt`) still runs. "Run now" does not count toward `--count`. A fire while the same task is still running is skipped.
- A run writes into its chat like a normal turn (live reply visible when that chat is open, "Stop" in the chat); the user's own send in that chat waits. Its shell / file / MCP approvals use the normal dialog, labelled `Scheduled task «label»: …`.
- Agent tool `scheduled_task` = upstream `minis-scheduled` (list / create / delete / enable / disable / run, same option names). Desktop safety addition: every *create* asks the user. Not offered to sub agents or inside scheduled runs.
- Not yet: `rerun` / `child-of-current` targets, prefilled tool calls (`--command` / `--tool`; the field round-trips), completion notifications.

## batch3 follow-up (small screens)

- Settings and Scheduled tasks windows: initial size = preferred size clamped to the usable area of the monitor the active window is on, minus a 24 px margin, centered (`WindowPlacements.dialogPlacement`, pure, `DialogPlacementTest`); resizable; content in a scrolling column with a visible scrollbar (`ScrollColumn`), so every section — including Skills › Import — is reachable on 1366×768 at 125–150 %.
- MCP, Sub agents and Skills (list, detail, import) are sections of the Settings window, so they scroll with it. In-window dialogs: the error / info toast scrolls (max 320 dp), the approval dialog's command block is capped at 260 dp and scrolls.

## P1-1 MCP (batch2)

- Config: upstream `MCPRepository` shape and file name (`%APPDATA%\OpenMinis\mcp_servers.json`, `{ "mcpServers": { name: { command, args, env, note, startupTimeoutSeconds, enabled/disabled } } }`), the 4 upstream import variants (Claude Desktop / Cursor JSON pastes in), last-write-wins by name, order kept. Desktop keys: `cwd` (blank = workspace), `callTimeoutSeconds` (default 300 s = upstream stdio), `confirmToolCalls`. `url` entries (HTTP) are kept but not started — the HTTP transport comes with phone interop.
- Transport: JSON-RPC 2.0 over newline-delimited **UTF-8** stdio (`initialize` protocolVersion 2025-06-18 → `notifications/initialized` → `tools/list` with `nextCursor` paging → `tools/call`), one long-lived multiplexed process per server, stderr kept for the log and error texts, `ping` / `roots/list` answered, `notifications/cancelled` on timeout. Env values expand `$VAR` / `${VAR}` / `$$VAR` / `$${VAR}` (upstream `expand_env`). On Windows a command without extension or ending in `.cmd`/`.bat` (`npx`, `uvx`, …) runs via `cmd.exe /d /c`; stop kills the whole process tree.
- Safety: starting a server runs a program on the host, so every **new or changed command line** (fingerprint of command, args, env, cwd) asks first — Allow = this app session, Always allow / Settings › Connect = remembered (`mcp_trust.json`) — direct native executables only since the security batch; interpreter / package-runner / script launches ask on every start. Optional per-server confirmation of each tool call. Shell confirmation unchanged.
- **Deviation:** upstream gives the model `minis-mcp-cli` inside its Linux guest; the desktop has no guest, so tools are offered natively as `mcp__<server>__<tool>` (provider-safe, ≤ 64 chars) and the system prompt lists the servers with their notes (upstream Top-20 fragment). Nested JSON Schemas pass through to OpenAI / Anthropic and are reduced to Gemini's subset (`AgentToolParam.rawSchema`, `JsonSchemaCompat`).
- Lifecycle: servers start before a turn when enabled (failures shown as a toast, the turn continues without them), a crash during a call restarts the server (0.5 s / 2 s backoff) and retries; all stopped on exit. Not yet: upstream 10-min idle TTL, per-conversation MCP overrides (`SessionMcpsSheet`), MCP tools inside sub agents.
- Settings › MCP servers: list with status / tool count / last error, enable switch, Connect, edit (command, args one per line, env KEY=VALUE, cwd, note, startup timeout, per-call confirmation), delete, Import JSON.

## P1-2 Sub agents (batch2)

- Tool `subagent_task` with upstream's schema (same name, params, enums, required list); roster = upstream `SubAgentDefinition` / `SubAgentRoster` (built-in "General Sub Agent" + up to 10 user agents, upstream length limits), persisted in `%APPDATA%\OpenMinis\subagents.json`. Settings › Sub agents: on/off, concurrency (default 3, upstream), delegation depth (default 1 = upstream; max 3, never unbounded), default / light model for `model_choice`, per-agent name / description / instructions / pinned model / allowed tools.
- Each delegation runs its own `AgentEngine` loop (identity + instructions sections from upstream `HelperRunner`, own tool subset, never `memory_write`), model resolved like upstream `ModelTierResolver` (pinned > model_choice; unusable → inherit), parent chain kept as fallback. Several `subagent_task` calls in one round run in parallel up to the concurrency limit.
- **Deviation:** upstream runs helpers in the background and delivers `[Background task finished …]` later; desktop runs them in the foreground of the tool call (`wait` always true; `steer` / `cancel` / `resume` return `unsupported_action`). The user's Stop cancels the parent and every child.
- Shell / file approvals of a child go through the same confirmation dialog, labelled `Sub agent «name»: …`, one at a time; confirmation defaults are untouched.
- Chat shows a live block per running sub agent (agent, title, state, current tool, elapsed, model; click to expand its latest output).
- Engine: `AgentEngine` takes a `Toolbox` (ToolRegistry wrapped) and installs an `AgentTurnContext` (settings, current model, depth) around tool execution.
- Fix found while testing: Stop while waiting for response headers used to block until the server answered (the blocking `execute()` never saw the coroutine cancel); all three providers now cancel the HTTP call on coroutine cancellation.

## Third-party additions (desktop)

| Library | Version | License | Use |
|---|---|---|---|
| com.huaban:jieba-analysis (+ commons-lang3) | 1.0.2 | Apache-2.0 | Chinese segmentation for memory search (replaces upstream's MIT cppjieba JNI). |
| net.java.dev.jna:jna + jna-platform | 5.15.0 | Apache-2.0 / LGPL-2.1 (dual; used under Apache-2.0) | P3-1: Windows DPAPI (`Crypt32Util`) for API keys. Loaded only on Windows. |
