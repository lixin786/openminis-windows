// P3-1 — API keys and the proxy password leave settings.json: DPAPI blobs in secrets.json, a marker in settings.json,
// one-time migration of plain-text keys, no silent loss when a blob cannot be decrypted, and no plaintext on disk.
package com.openminis.desktop

import com.openminis.desktop.core.*
import com.openminis.desktop.net.NetworkSettings
import com.openminis.desktop.secrets.*
import java.io.File
import kotlin.test.*

class SecretsDpapiTest {
    private val anthropicKey = "sk-ant-api03-PLAINTEXT-SHOULD-NEVER-HIT-DISK-0123456789"
    private val openaiKey = "sk-proj-ANOTHER-SECRET-VALUE-9876543210"
    private val proxyPw = "pr0xy-P@ss-word-ABC"

    /** Reversible stand-in for DPAPI with a per-"user" key; a different user cannot unprotect. */
    private class FakeDpapi(private val user: Byte = 0x5A, var failProtect: Boolean = false) : SecretProtector {
        override val scheme = "dpapi"
        var protects = 0
        override fun protect(plain: ByteArray): ByteArray {
            if (failProtect) throw IllegalStateException("DPAPI encryption failed: Win32Exception")
            protects++
            return byteArrayOf(user) + plain.map { (it.toInt() xor user.toInt() xor 0x33).toByte() }
        }
        override fun unprotect(blob: ByteArray): ByteArray {
            if (blob.isEmpty() || blob[0] != user) throw SecretUnreadableException("DPAPI could not decrypt (Win32Exception: Key not valid for use in specified state.)")
            return blob.drop(1).map { (it.toInt() xor user.toInt() xor 0x33).toByte() }.toByteArray()
        }
    }

    private fun tmp(): File = kotlin.io.path.createTempDirectory("p31").toFile()
    private fun store(dir: File, p: SecretProtector) = SettingsStore(File(dir, "settings.json"), SecretStore(File(dir, "secrets.json"), p))

    /** Every file under [dir] (settings.json, secrets.json, temp files, leftovers) — none may contain a secret. */
    private fun assertNoPlaintextOnDisk(dir: File, vararg secrets: String) {
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val bytes = f.readBytes()
            val text = String(bytes, Charsets.UTF_8)
            secrets.forEach { s ->
                assertFalse(text.contains(s), "${f.name} contains a plaintext secret")
                // also not as UTF-16 (what a careless Windows API might write)
                assertFalse(String(bytes, Charsets.UTF_16LE).contains(s), "${f.name} contains a UTF-16 plaintext secret")
            }
        }
    }

    private fun legacySettings(dir: File) {
        val s = AppSettings(
            providers = mapOf(ProviderKind.ANTHROPIC to ProviderSettings(apiKey = anthropicKey, model = "m1"),
                              ProviderKind.OPENAI to ProviderSettings(apiKey = openaiKey)),
            network = NetworkSettings(proxyUser = "bob", proxyPassword = proxyPw),
        )
        SettingsStore(File(dir, "settings.json")).save(s)  // the pre-P3-1 format: keys in plain JSON
        assertTrue(File(dir, "settings.json").readText().contains(anthropicKey))
    }

    @Test fun firstStartMovesPlainKeysIntoDpapiAndClearsSettingsJson() {
        val dir = tmp()
        try {
            legacySettings(dir)
            val fake = FakeDpapi()
            val loaded = store(dir, fake).load()
            // In memory the app still has the keys…
            assertEquals(anthropicKey, loaded.provider(ProviderKind.ANTHROPIC).apiKey)
            assertEquals(openaiKey, loaded.provider(ProviderKind.OPENAI).apiKey)
            assertEquals(proxyPw, loaded.network.proxyPassword)
            assertEquals("m1", loaded.provider(ProviderKind.ANTHROPIC).model)
            // …on disk only the marker and ciphertext.
            val json = org.json.JSONObject(File(dir, "settings.json").readText())
            val a = json.getJSONObject("providers").getJSONObject("ANTHROPIC")
            assertEquals("", a.getString("apiKey")); assertEquals("dpapi", a.getString("apiKeyStore"))
            assertEquals("", json.getJSONObject("network").getString("proxyPassword"))
            assertEquals("dpapi", json.getJSONObject("network").getString("proxyPasswordStore"))
            assertEquals(setOf("provider.ANTHROPIC.apiKey", "provider.OPENAI.apiKey", "network.proxyPassword"),
                org.json.JSONObject(File(dir, "secrets.json").readText()).getJSONObject("entries").keySet())
            assertNoPlaintextOnDisk(dir, anthropicKey, openaiKey, proxyPw)
            // Second start: read back from DPAPI, nothing re-written.
            val again = store(dir, fake).load()
            assertTrue(store(dir, fake).let { it.load(); it.unreadable.isEmpty() })
            assertEquals(anthropicKey, again.provider(ProviderKind.ANTHROPIC).apiKey)
        } finally { dir.deleteRecursively() }
    }

    @Test fun savingNeverWritesPlaintextAndUnchangedKeysAreNotReEncrypted() {
        val dir = tmp()
        try {
            val fake = FakeDpapi()
            val st = store(dir, fake)
            var s = st.load().withProvider(ProviderKind.ANTHROPIC, ProviderSettings(apiKey = anthropicKey))
            st.save(s); assertEquals(1, fake.protects)
            st.save(s.copy(systemPrompt = "x")); assertEquals(1, fake.protects, "same key: no new encryption")
            s = s.withProvider(ProviderKind.ANTHROPIC, ProviderSettings(apiKey = openaiKey))
            st.save(s); assertEquals(2, fake.protects)
            assertNoPlaintextOnDisk(dir, anthropicKey, openaiKey)
            assertEquals(openaiKey, store(dir, fake).load().provider(ProviderKind.ANTHROPIC).apiKey)
            // Clearing the key removes its blob and its marker.
            st.save(s.withProvider(ProviderKind.ANTHROPIC, ProviderSettings()))
            assertTrue(org.json.JSONObject(File(dir, "secrets.json").readText()).getJSONObject("entries").isEmpty)
            assertEquals("", org.json.JSONObject(File(dir, "settings.json").readText()).getJSONObject("providers").getJSONObject("ANTHROPIC").getString("apiKeyStore"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun otherUserOrMachineGetsAClearListAndTheBlobIsNeverSilentlyDropped() {
        val dir = tmp()
        try {
            legacySettings(dir)
            store(dir, FakeDpapi(user = 1)).load()
            val secretsBefore = File(dir, "secrets.json").readText()
            // "Another Windows account": cannot decrypt.
            val st = store(dir, FakeDpapi(user = 2))
            val s = st.load()
            assertEquals("", s.provider(ProviderKind.ANTHROPIC).apiKey)
            assertEquals(setOf("provider.ANTHROPIC.apiKey", "provider.OPENAI.apiKey", "network.proxyPassword"), st.unreadable.map { it.id }.toSet())
            assertTrue(st.unreadable.first().reason.contains("could not decrypt"))
            assertEquals(secretsBefore, File(dir, "secrets.json").readText(), "load never rewrites unreadable blobs")
            // Saving unrelated settings keeps the blobs and the markers.
            st.save(s.copy(systemPrompt = "changed"))
            val after = org.json.JSONObject(File(dir, "secrets.json").readText()).getJSONObject("entries")
            assertEquals(3, after.length())
            assertEquals("dpapi", org.json.JSONObject(File(dir, "settings.json").readText()).getJSONObject("providers").getJSONObject("OPENAI").getString("apiKeyStore"))
            // Re-entering one key replaces just that blob.
            st.save(s.withProvider(ProviderKind.OPENAI, ProviderSettings(apiKey = "sk-new-key-on-this-pc")))
            assertEquals(setOf("provider.ANTHROPIC.apiKey", "network.proxyPassword"), st.unreadable.map { it.id }.toSet())
            assertEquals("sk-new-key-on-this-pc", store(dir, FakeDpapi(user = 2)).load().provider(ProviderKind.OPENAI).apiKey)
            // Forget: the remaining unreadable blobs go, only on request.
            val st2 = store(dir, FakeDpapi(user = 2)); val s2 = st2.load()
            assertEquals(2, st2.unreadable.size)
            st2.forgetUnreadable(s2)
            assertTrue(st2.unreadable.isEmpty())
            assertEquals(setOf("provider.OPENAI.apiKey"), org.json.JSONObject(File(dir, "secrets.json").readText()).getJSONObject("entries").keySet())
            assertNoPlaintextOnDisk(dir, anthropicKey, openaiKey, proxyPw, "sk-new-key-on-this-pc")
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingOrDamagedSecretsFileIsReportedNotHidden() {
        val dir = tmp()
        try {
            legacySettings(dir)
            store(dir, FakeDpapi()).load()
            File(dir, "secrets.json").writeText("{ not json")
            val st = store(dir, FakeDpapi())
            val s = st.load()
            assertEquals(3, st.unreadable.size)
            st.save(s.copy(systemPrompt = "x"))
            assertEquals("{ not json", File(dir, "secrets.json").readText(), "nothing to write: the damaged file is untouched")
            st.save(s.withProvider(ProviderKind.XAI, ProviderSettings(apiKey = "xai-key-123456")))
            assertTrue(dir.listFiles()!!.any { it.name.startsWith("secrets.json.damaged-") }, "damaged file kept aside before the rewrite")
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedEncryptionLeavesTheOldFilesAloneAndSaysSo() {
        val dir = tmp()
        try {
            legacySettings(dir)
            val before = File(dir, "settings.json").readText()
            val st = store(dir, FakeDpapi(failProtect = true))
            val s = st.load()
            assertEquals(anthropicKey, s.provider(ProviderKind.ANTHROPIC).apiKey, "the app still works this session")
            assertNotNull(st.migrationError)
            assertEquals(before, File(dir, "settings.json").readText(), "no half-migrated state")
            assertFalse(File(dir, "secrets.json").exists())
            assertFailsWith<IllegalStateException> { st.save(s.copy(systemPrompt = "x")) }
            assertEquals(before, File(dir, "settings.json").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun withoutDpapiTheOldFormatIsUnchanged() {
        val dir = tmp()
        try {
            legacySettings(dir)
            val s = SettingsStore(File(dir, "settings.json")).load()
            assertEquals(anthropicKey, s.provider(ProviderKind.ANTHROPIC).apiKey)
            assertFalse(File(dir, "secrets.json").exists())
            assertNull(SettingsStore(File(dir, "settings.json")).migrationError)
        } finally { dir.deleteRecursively() }
    }

    @Test fun i18nForTheNewTexts() {
        for (l in listOf("en", "zh-CN", "zh-TW")) listOf("settings.keys_dpapi", "secrets.unreadable", "secrets.unreadable_item",
            "secrets.forget", "secrets.migration_failed", "secrets.save_failed").forEach {
            assertTrue(com.openminis.desktop.i18n.I18n.table(l)[it].orEmpty().isNotBlank(), "$l:$it")
        }
    }

    /** Real DPAPI round trip; runs on Windows only (the user's test machine), skipped elsewhere. */
    @Test fun realDpapiRoundTripOnWindows() {
        val p = SecretProtector.platform() ?: return
        val blob = p.protect(anthropicKey.toByteArray(Charsets.UTF_8))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(anthropicKey))
        assertEquals(anthropicKey, String(p.unprotect(blob), Charsets.UTF_8))
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<SecretUnreadableException> { p.unprotect(tampered) }
        val dir = tmp()
        try {
            legacySettings(dir)
            val s = SettingsStore(File(dir, "settings.json"), SecretStore(File(dir, "secrets.json"), p)).load()
            assertEquals(anthropicKey, s.provider(ProviderKind.ANTHROPIC).apiKey)
            assertNoPlaintextOnDisk(dir, anthropicKey, openaiKey, proxyPw)
        } finally { dir.deleteRecursively() }
    }
}
