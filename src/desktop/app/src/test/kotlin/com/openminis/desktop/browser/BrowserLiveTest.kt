// P2-1: end-to-end run against a real Chromium. Opt-in: only runs when OPENMINIS_LIVE_BROWSER=1 and
// OPENMINIS_BROWSER points at a Chromium / Edge / Chrome binary, and the machine has internet access.
// Not part of the normal suite result (it returns early otherwise).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.browser

import com.openminis.desktop.agent.Toolbox
import com.openminis.desktop.core.AppSettings
import com.openminis.desktop.tools.ToolApprover
import com.openminis.desktop.tools.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserLiveTest {
    private class Inner : Toolbox {
        override fun definitions() = emptyList<com.openminis.app.data.model.AgentToolDefinition>()
        override suspend fun execute(name: String, args: JSONObject) = ToolOutcome("inner")
    }

    @Test fun liveChromium() = runBlocking {
        if (System.getenv("OPENMINIS_LIVE_BROWSER") != "1") return@runBlocking
        val root = Files.createTempDirectory("om-live").toFile()
        val profile = File(root, "browser-profile")
        val svc = BrowserService(profileDir = profile, executable = { BrowserLocator.find("") }, screenshotDir = { File(root, "shots") })
        val asked = mutableListOf<String>()
        val tb = BrowserToolbox(Inner(), svc, { AppSettings() }, ToolApprover { asked += it.detail; true }, { "c1" }, { 1 }, {})
        fun call(vararg kv: Pair<String, Any>) = runBlocking { tb.execute("browser", JSONObject().apply { kv.forEach { put(it.first, it.second) } }) }
        val log = StringBuilder()
        try {
            val t0 = System.currentTimeMillis()
            val open = call("action" to "open", "url" to "https://example.com/")
            log.appendLine("OPEN (${System.currentTimeMillis() - t0} ms): ${open.content.take(400)}")
            assertTrue(open.content.contains("Example Domain"), open.content)

            val targets = svc.use { b -> b.send("Target.getTargets").getJSONArray("targetInfos") }
            val types = (0 until targets.length()).map { targets.getJSONObject(it).optString("type") }
            log.appendLine("TARGETS now: $types; at start: ${svc.use { it.startTargets + " in " + it.startMs + " ms, " + it.product }}")

            val links = call("action" to "links"); log.appendLine("LINKS: ${links.content.take(300)}")
            assertTrue(links.content.contains("iana.org"), links.content)
            val h1 = call("action" to "select_text", "selector" to "p"); log.appendLine("SELECT p: ${h1.content.take(300)}")
            assertTrue(h1.content.contains("documentation"))

            // Link click to an unapproved domain: the Fetch gate must stop it (main frame id = target id).
            val click = call("action" to "click", "selector" to "a"); log.appendLine("CLICK: ${click.content}")
            assertTrue(click.content.contains("not approved"), click.content)

            val shot = call("action" to "screenshot"); log.appendLine("SHOT: ${shot.content}")
            val png = File(root, "shots").listFiles()!!.single()
            assertEquals(0x89.toByte(), png.readBytes()[0])

            // A public-looking name that resolves to 127.0.0.1: URL check passes, the proxy must refuse.
            val rebind = call("action" to "open", "url" to "http://127.0.0.1.nip.io/"); log.appendLine("NIP: ${rebind.content.take(400)}")
            assertTrue(rebind.content.contains("refused") || rebind.content.contains("blocked"), rebind.content)

            val local = call("action" to "open", "url" to "http://127.0.0.1/"); log.appendLine("LOCAL: ${local.content}")
            assertTrue(local.denied)

            val tab2 = call("action" to "open", "url" to "https://example.com/", "new_tab" to true); log.appendLine("TAB2: ${tab2.content.take(80)}")
            call("action" to "open", "url" to "https://example.com/", "new_tab" to true)
            val tab4 = call("action" to "open", "url" to "https://example.com/", "new_tab" to true); log.appendLine("TAB4: ${tab4.content}")
            assertTrue(tab4.content.contains("at most 3 tabs"), tab4.content)
            log.appendLine("ASKED: $asked")

            val tree = svc.use { b -> b.processTree().map { it.pid() } }
            log.appendLine("TREE before kill: ${tree.size} processes")
        } finally {
            svc.stopBrowser()
            val left = ProcessSweep.survivors(profile)
            log.appendLine("AFTER KILL: survivors=$left profileExists=${profile.exists()}")
            File(System.getProperty("java.io.tmpdir"), "om-live-report.txt").writeText(log.toString())
            svc.shutdown()
            assertTrue(left.isEmpty(), "leftover processes $left")
            assertTrue(!profile.exists(), "profile not deleted")
        }
    }
}
