// Attachments: storage/classification, ImageBudget shaping, wire format per provider, persistence.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.provider.ImageBudget
import com.openminis.desktop.TestSupport.body
import com.openminis.desktop.TestSupport.openAiText
import com.openminis.desktop.TestSupport.user
import com.openminis.desktop.agent.ConversationContextState
import com.openminis.desktop.agent.HistoryMapper
import com.openminis.desktop.agent.TurnListener
import com.openminis.desktop.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class AttachmentTest {
    private val root = TestSupport.tmp("om-root")
    private val store = AttachmentStore(root)
    private val src = TestSupport.tmp("om-src")

    private fun png(name: String, w: Int, h: Int): File = File(src, name).also { f ->
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = java.awt.Color.ORANGE; fillRect(0, 0, w, h); dispose() }
        ImageIO.write(img, "png", f)
    }

    @Test fun importClassifiesAndKeepsOriginals() {
        val image = store.importFile("c1", png("shot.png", 40, 30))
        val text = store.importFile("c1", File(src, "notes.md").apply { writeText("# 笔记\nhello") })
        val noExt = store.importFile("c1", File(src, "README").apply { writeText("plain words") })
        val bin = store.importFile("c1", File(src, "data.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 0, 9)) })
        val zh = store.importFile("c1", File(src, "报告 v2.txt").apply { writeText("中文") })
        assertEquals(AttachmentKind.IMAGE, image.kind); assertEquals("image/png", image.mimeType)
        assertEquals(AttachmentKind.TEXT, text.kind)
        assertEquals(AttachmentKind.TEXT, noExt.kind, "no NUL bytes + valid UTF-8 → text")
        assertEquals(AttachmentKind.FILE, bin.kind)
        assertEquals("报告 v2.txt", store.fileOf(zh).name, "Unicode names kept")
        assertTrue(store.fileOf(image).isFile && store.fileOf(image).path.contains("attachments"))
        // Same name twice → upstream-style _1 suffix, original untouched.
        val again = store.importFile("c1", File(src, "notes.md"))
        assertEquals("notes_1.md", store.fileOf(again).name)
        assertEquals("attachments/c1/notes.md", text.relativePath)
    }

    @Test fun oversizeAndMissingFilesAreRejected() {
        assertFailsWith<IllegalArgumentException> { store.importFile("c1", File(src, "nope.txt")) }
    }

    @Test fun clipboardImageBecomesPng() {
        val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val a = store.importImage("c2", img)
        assertEquals(AttachmentKind.IMAGE, a.kind)
        assertTrue(a.fileName.startsWith("pasted-image-") && a.fileName.endsWith(".png"))
        assertEquals(ImageBudget.ImageFormat.PNG, ImageBudget.sniffFormat(store.fileOf(a).readBytes()))
    }

    @Test fun shaperDownscalesBigImagesAndInlinesText() {
        val bigImg = store.importFile("c", png("wide.png", 3000, 800))
        val txt = store.importFile("c", File(src, "code.py").apply { writeText("print('hi')\n".repeat(10)) })
        val bin = store.importFile("c", File(src, "x.bin").apply { writeBytes(ByteArray(10)) })
        val shaped = AttachmentShaper(store) { 60_000 }.shape(listOf(bigImg, txt, bin))
        val img = shaped.imageParts.filterIsInstance<AgentContentPart.ImageData>().single()
        val decoded = ImageIO.read(img.data.inputStream())
        assertEquals(ImageBudget.MAX_EDGE_PX, maxOf(decoded.width, decoded.height), "fitted within upstream MAX_EDGE_PX")
        assertEquals(store.fileOf(bigImg).absolutePath, img.linuxPath)
        val texts = shaped.imageParts.filterIsInstance<AgentContentPart.Text>().map { it.text }
        assertTrue(texts.first().startsWith("[attached image: "))
        assertTrue(texts.any { it.startsWith("[Image downscaled before sending: the original is 3000×800") }, texts.toString())
        assertTrue(shaped.textSuffix.contains("<attached-file name=\"code.py\"") && shaped.textSuffix.contains("print('hi')"))
        assertTrue(shaped.textSuffix.contains("<user-attached-files>") && shaped.textSuffix.contains("x.bin"))
    }

    @Test fun longTextIsTruncatedWithPathHint() {
        val t = store.importFile("c", File(src, "log.txt").apply { writeText("y".repeat(5000)) })
        val shaped = AttachmentShaper(store) { 1000 }.shape(listOf(t))
        assertTrue(shaped.textSuffix.contains("truncated: showing the first 1000 of 5000"))
    }

    @Test fun mapperBuildsImagePartsAndText() {
        val a = store.importFile("c", png("p.png", 20, 20))
        val m = HistoryMapper(AttachmentShaper(store) { 60_000 }).toLLMMessages(listOf(user("what is this?", a))).single()
        assertTrue(m.contentParts.any { it is AgentContentPart.ImageData })
        assertEquals("what is this?", (m.contentParts.last() as AgentContentPart.Text).text)
        // Image-only message still gets a text part.
        val m2 = HistoryMapper(AttachmentShaper(store) { 60_000 }).toLLMMessages(listOf(user("", a))).single()
        assertTrue((m2.contentParts.last() as AgentContentPart.Text).text.isNotBlank())
    }

    private fun sendWithImage(kind: ProviderKind, server: MockWebServer, imageInput: Boolean?, model: String): String = runBlocking {
        val a = store.importFile("c", png("img.png", 64, 48))
        var s = TestSupport.settingsFor(kind, server, model)
        s = s.withProvider(kind, s.provider(kind).copy(imageInput = imageInput))
        val engine = TestSupport.engine({ s }, AttachmentShaper(store) { s.maxInlineTextChars })
        val msg = engine.runTurn(listOf(user("describe", a)), s, "sys", ConversationContextState(), object : TurnListener {})
        assertNull(msg.error, msg.error)
        server.takeRequest().body.readUtf8()
    }

    @Test fun imagesReachOpenAiCompatibleWhenEnabled() {
        val server = MockWebServer().apply { enqueue(openAiText("a cat")); start() }
        val sent = sendWithImage(ProviderKind.OPENAI_COMPATIBLE, server, imageInput = true, model = "llava")
        assertTrue(sent.contains("image_url") && sent.contains("data:image/png;base64,"), sent.take(400))
        server.shutdown()
    }

    @Test fun textOnlyCustomModelGetsUpstreamPlaceholder() {
        val server = MockWebServer().apply { enqueue(openAiText("no eyes")); start() }
        val sent = sendWithImage(ProviderKind.OPENAI_COMPATIBLE, server, imageInput = false, model = "llama3")
        assertFalse(sent.contains("base64,"))
        assertTrue(sent.contains("does not support vision input"))
        server.shutdown()
    }

    @Test fun imagesReachAnthropicAsImageBlocks() {
        val server = MockWebServer()
        fun ev(type: String, json: String) = "event: $type\ndata: $json\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ev("message_start", """{"type":"message_start","message":{"id":"m","type":"message","role":"assistant","content":[],"model":"claude-haiku-4-5","usage":{"input_tokens":5,"output_tokens":1}}}""") +
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"orange"}}""") +
            ev("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""") +
            ev("message_stop", """{"type":"message_stop"}""")))
        server.start()
        val a = store.importFile("c", png("img.png", 64, 48))
        val s = AppSettings(activeProvider = ProviderKind.ANTHROPIC, toolsEnabled = false, workspaceDir = TestSupport.tmp().absolutePath)
            .withProvider(ProviderKind.ANTHROPIC, ProviderSettings(apiKey = "k", baseUrl = server.url("/").toString().trimEnd('/'), model = "claude-haiku-4-5"))
        val msg = runBlocking {
            TestSupport.engine({ s }, AttachmentShaper(store) { 60_000 }).runTurn(listOf(user("color?", a)), s, "sys", ConversationContextState(), object : TurnListener {})
        }
        assertEquals("orange", msg.displayText, msg.error)
        val b = body(server.takeRequest())
        val content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val types = (0 until content.length()).map { content.getJSONObject(it).getString("type") }
        assertTrue("image" in types && "text" in types, types.toString())
        server.shutdown()
    }

    @Test fun attachmentsPersistWithConversation() {
        val a = store.importFile("c", png("p.png", 5, 5))
        val c = Conversation(messages = listOf(user("look", a)))
        val dir = TestSupport.tmp()
        ConversationStore(dir).save(c)
        val back = ConversationStore(dir).loadAll().single().messages.single()
        assertEquals(listOf(a), back.attachments)
        assertTrue(store.fileOf(back.attachments.single()).isFile)
        store.deleteConversation("c")
        assertFalse(store.fileOf(a).exists())
    }
}
