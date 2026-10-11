// P3-1 follow-up — the phone-link token is stored with DPAPI; the old plain-text file is migrated and wiped.
package com.openminis.desktop

import com.openminis.desktop.remote.RemoteTokenStore
import com.openminis.desktop.secrets.SecretProtector
import com.openminis.desktop.secrets.SecretUnreadableException
import java.io.File
import kotlin.test.*

class RemoteTokenDpapiTest {
    private class FakeDpapi(private val user: Byte = 0x5A, var fail: Boolean = false) : SecretProtector {
        override val scheme = "dpapi"
        override fun protect(plain: ByteArray): ByteArray {
            if (fail) throw IllegalStateException("DPAPI encryption failed: Win32Exception")
            return byteArrayOf(user) + plain.map { (it.toInt() xor user.toInt() xor 0x33).toByte() }
        }
        override fun unprotect(blob: ByteArray): ByteArray {
            if (blob.isEmpty() || blob[0] != user) throw SecretUnreadableException("DPAPI could not decrypt (Win32Exception)")
            return blob.drop(1).map { (it.toInt() xor user.toInt() xor 0x33).toByte() }.toByteArray()
        }
    }

    private fun tmp() = kotlin.io.path.createTempDirectory("tokdp").toFile()
    private fun assertNowhere(dir: File, token: String) = dir.walkTopDown().filter { it.isFile }.forEach {
        assertFalse(it.readText(Charsets.ISO_8859_1).contains(token), "${it.name} contains the token in plain text")
    }

    @Test fun plainTokenIsMigratedWipedAndStillTheSame() {
        val dir = tmp()
        try {
            val f = File(dir, "remote-mcp-token")
            val old = RemoteTokenStore(f, protector = null).get()          // pre-change format
            assertTrue(f.readText().contains(old))
            val fake = FakeDpapi()
            val s = RemoteTokenStore(f, fake); s.migrate()
            assertEquals(old, s.get(), "phones keep working: same token")
            assertFalse(f.exists(), "plain file deleted")
            assertTrue(File(dir, "remote-mcp-token.dpapi").exists())
            assertNowhere(dir, old)
            assertEquals(old, RemoteTokenStore(f, fake).get())
            assertNull(s.notice)
        } finally { dir.deleteRecursively() }
    }

    @Test fun newTokenFormatUnchangedAndRotationStaysEncrypted() {
        val dir = tmp()
        try {
            val f = File(dir, "remote-mcp-token"); val fake = FakeDpapi()
            val t1 = RemoteTokenStore(f, fake).get()
            assertTrue(RemoteTokenStore.valid(t1)); assertEquals(43, t1.length)
            assertFalse(f.exists())
            val t2 = RemoteTokenStore(f, fake).rotate()
            assertNotEquals(t1, t2)
            assertEquals(t2, RemoteTokenStore(f, fake).get())
            assertNowhere(dir, t1); assertNowhere(dir, t2)
            // migrate() with nothing to migrate does not create a token file
            val d2 = File(dir, "x"); d2.mkdirs(); RemoteTokenStore(File(d2, "remote-mcp-token"), fake).migrate()
            assertTrue(d2.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun otherWindowsAccountGetsANewTokenANoticeAndTheOldFileKept() {
        val dir = tmp()
        try {
            val f = File(dir, "remote-mcp-token")
            val t1 = RemoteTokenStore(f, FakeDpapi(user = 1)).get()
            val s = RemoteTokenStore(f, FakeDpapi(user = 2))
            val t2 = s.get()
            assertNotEquals(t1, t2)
            assertEquals(RemoteTokenStore.Notice.REGENERATED_UNREADABLE, s.notice)
            assertTrue(dir.listFiles()!!.any { it.name.startsWith("remote-mcp-token.dpapi.unreadable-") })
            assertEquals(t2, RemoteTokenStore(f, FakeDpapi(user = 2)).get())
            s.rotate(); assertNull(s.notice, "re-pairing clears the notice")
        } finally { dir.deleteRecursively() }
    }

    @Test fun encryptionFailureNeverWritesPlaintext() {
        val dir = tmp()
        try {
            // legacy file present: left as it was, token still served, notice set
            val f = File(dir, "remote-mcp-token")
            val old = RemoteTokenStore(f, protector = null).get()
            val s = RemoteTokenStore(f, FakeDpapi(fail = true))
            assertEquals(old, s.get())
            assertEquals(RemoteTokenStore.Notice.NOT_SAVED, s.notice)
            assertTrue(f.exists()); assertFalse(File(dir, "remote-mcp-token.dpapi").exists())
            // no legacy file: memory only, nothing on disk
            val d2 = File(dir, "y").apply { mkdirs() }
            val s2 = RemoteTokenStore(File(d2, "remote-mcp-token"), FakeDpapi(fail = true))
            val t = s2.get()
            assertTrue(RemoteTokenStore.valid(t)); assertEquals(t, s2.get())
            assertEquals(RemoteTokenStore.Notice.NOT_SAVED, s2.notice)
            assertTrue(d2.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun i18n() {
        for (l in listOf("en", "zh-CN", "zh-TW")) listOf("remote.token_regenerated", "remote.token_not_saved").forEach {
            assertTrue(com.openminis.desktop.i18n.I18n.table(l)[it].orEmpty().isNotBlank(), "$l:$it")
        }
    }

    @Test fun realDpapiOnWindows() {
        val p = SecretProtector.platform() ?: return
        val dir = tmp()
        try {
            val f = File(dir, "remote-mcp-token")
            val old = RemoteTokenStore(f, protector = null).get()
            assertEquals(old, RemoteTokenStore(f, p).get())
            assertFalse(f.exists()); assertNowhere(dir, old)
            assertEquals(old, RemoteTokenStore(f, p).get())
        } finally { dir.deleteRecursively() }
    }
}
