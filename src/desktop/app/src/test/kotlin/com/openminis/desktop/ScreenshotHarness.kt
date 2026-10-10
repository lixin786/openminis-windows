// Renders the main window off-screen to PNGs for visual review (no assertion on pixels).
// Runs only with -Dopenminis.screenshots=<dir>; otherwise it is a no-op.
package com.openminis.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.openminis.desktop.core.*
import com.openminis.desktop.ui.App
import com.openminis.desktop.ui.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class ScreenshotHarness {
    @Test fun render() {
        val out = System.getProperty("openminis.screenshots")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        out.mkdirs()
        // I18n is global and the rest of the suite runs in English.
        try { renderAll(out) } finally { com.openminis.desktop.i18n.I18n.apply("en") }
    }

    private fun renderAll(out: File) {
        for (lang in listOf("zh-CN")) for (dark in listOf(false, true)) {
            val root = TestSupport.tmp("om-shot")
            val paths = AppPaths(root)
            val store = ConversationStore(paths.conversationsDir)
            val now = System.currentTimeMillis()
            store.save(Conversation(id = "c1", title = "把 readme 改成中文", updatedAt = now - 3 * 60_000, messages = listOf(
                ChatMessage(role = ChatRole.USER, text = "帮我把工作区的 readme 改成中文"),
                ChatMessage(role = ChatRole.ASSISTANT, rounds = listOf(Round(text = "好的，先看看工作区里有什么。", toolCalls = listOf(
                    ToolCall("t1", "list_dir", """{"path":"."}""", "README.md\nsrc/", status = ToolStatus.DONE, durationMs = 12),
                    ToolCall("t2", "file_read", """{"path":"README.md"}""", "# Hello", status = ToolStatus.DONE, durationMs = 8),
                    ToolCall("t3", "shell", """{"command":"Get-Date"}""", "2026年10月10日", status = ToolStatus.DONE, durationMs = 640),
                    ToolCall("t4", "shell", """{"command":"rm -rf build"}""", "The user declined to run this command.", isError = true, status = ToolStatus.DENIED),
                    ToolCall("t5", "file_write", """{"path":"README.md"}""", "Error: disk full", isError = true, status = ToolStatus.FAILED, durationMs = 30),
                )), Round(text = "已经把 **README.md** 翻译成中文并保存。\n\n- 标题：你好\n- `src/` 目录未改动"))),
            )))
            store.save(Conversation(id = "c2", title = "PowerShell 查日期", updatedAt = now - 26 * 3_600_000, pinned = true))
            store.save(Conversation(id = "c3", title = "整理下载目录", updatedAt = now - 3 * 86_400_000L))
            store.save(Conversation(id = "c4", title = "MCP 调试记录", updatedAt = now - 40 * 86_400_000L))
            SettingsStore(paths.settingsFile).save(AppSettings(language = lang, darkTheme = dark,
                activeProvider = ProviderKind.OPENAI_COMPATIBLE).withProvider(ProviderKind.OPENAI_COMPATIBLE,
                ProviderSettings(baseUrl = "https://api.siliconflow.cn/v1", model = "deepseek-ai/DeepSeek-V3")))
            com.openminis.desktop.i18n.I18n.apply(lang)
            val state = AppState(paths, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val tag = (if (dark) "dark" else "light")
            fun shot(name: String) {
                val scene = ImageComposeScene(1280, 800, Density(1f)) { App(state) }
                repeat(3) { scene.render(it * 16_000_000L) }
                val img = scene.render(100_000_000L)
                File(out, "$name-$tag.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }
            shot("empty")
            state.select("c1"); shot("chat")
            runCatching { state.shutdown() }
        }
    }
}
