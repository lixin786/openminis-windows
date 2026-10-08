// OpenMinis Desktop — attachment storage + model-side shaping. Desktop counterpart of upstream
// ui/chat/InputAttachment.kt + ChatViewModel.prepareUserAttachments / addModelImage /
// uniqueUploadFileName: originals are kept on disk (here %APPDATA%\OpenMinis\attachments\<chat>\),
// the model gets an ImageBudget-fitted copy, a "[attached image: <path>]" caption and — when
// downscaled — upstream's downscale note; non-image files are listed in upstream's
// <user-attached-files> block. Desktop addition: text files are inlined as text.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.provider.ImageBudget
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import javax.imageio.ImageIO

class AttachmentStore(private val root: File) {
    private val baseDir: File get() = File(root, "attachments").apply { mkdirs() }

    fun dirFor(conversationId: String): File = File(baseDir, safe(conversationId)).apply { mkdirs() }

    fun fileOf(a: Attachment): File = File(root, a.relativePath)

    /** Copy a dropped / chosen / pasted file into the conversation's attachment folder. */
    fun importFile(conversationId: String, src: File): Attachment {
        require(src.isFile) { "Not a file: ${src.absolutePath}" }
        require(src.length() <= MAX_FILE_BYTES) { "${src.name} is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB" }
        val dir = dirFor(conversationId)
        val dest = File(dir, uniqueName(dir, src.name))
        src.copyTo(dest, overwrite = false)
        return describe(dest, src.name, sourcePath = src.absolutePath)
    }

    /** Store raw bytes (clipboard image, pasted file content). */
    fun importBytes(conversationId: String, bytes: ByteArray, fileName: String): Attachment {
        require(bytes.size <= MAX_FILE_BYTES) { "$fileName is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB" }
        val dir = dirFor(conversationId)
        val dest = File(dir, uniqueName(dir, fileName))
        dest.writeBytes(bytes)
        return describe(dest, fileName, sourcePath = null)
    }

    /** Clipboard / drag image (java.awt.Image) → PNG attachment. */
    fun importImage(conversationId: String, image: java.awt.Image, baseName: String = "pasted-image"): Attachment {
        val bi = if (image is BufferedImage) image else {
            val w = image.getWidth(null).coerceAtLeast(1); val h = image.getHeight(null).coerceAtLeast(1)
            BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(bi, "png", out)
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return importBytes(conversationId, out.toByteArray(), "$baseName-$stamp.png")
    }

    fun delete(a: Attachment) { fileOf(a).delete() }

    fun deleteConversation(conversationId: String) { File(baseDir, safe(conversationId)).deleteRecursively() }

    private fun describe(dest: File, displayName: String, sourcePath: String?): Attachment {
        val kind = classify(dest)
        val mime = when (kind) {
            AttachmentKind.IMAGE -> ImageBudget.sniffFormat(dest.inputStream().use { it.readNBytes(64) }).mimeType ?: guessMime(dest.name)
            AttachmentKind.TEXT -> "text/plain"
            AttachmentKind.FILE -> guessMime(dest.name)
        }
        return Attachment(
            fileName = displayName, mimeType = mime, kind = kind,
            relativePath = dest.relativeTo(root).path.replace('\\', '/'),
            sizeBytes = dest.length(), sourcePath = sourcePath,
        )
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    companion object {
        const val MAX_FILE_BYTES = 50L * 1024 * 1024

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "log", "html", "htm", "css", "scss", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "java",
            "kt", "kts", "gradle", "c", "h", "cpp", "hpp", "cc", "cs", "swift", "m", "php", "sh", "bash", "zsh", "ps1",
            "psm1", "bat", "cmd", "sql", "r", "lua", "pl", "dart", "vue", "svelte", "tex", "rst", "properties", "env",
            "gitignore", "dockerfile", "makefile", "srt", "vtt",
        )

        /** Upstream uniqueUploadFileName: sanitise, keep extension, append _N on collision. */
        fun uniqueName(dir: File, original: String): String {
            val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
            // Keep Unicode letters (Chinese file names) but drop characters Windows forbids.
            val sanitized = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').ifBlank { "file" }
            if (!File(dir, sanitized).exists()) return sanitized
            val dot = sanitized.lastIndexOf('.')
            val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
            val ext = if (dot > 0) sanitized.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "${base}_$n$ext"
                if (!File(dir, candidate).exists()) return candidate
                n++
            }
        }

        fun classify(f: File): AttachmentKind {
            val head = f.inputStream().use { it.readNBytes(8192) }
            val fmt = ImageBudget.sniffFormat(head)
            if (fmt != ImageBudget.ImageFormat.UNKNOWN) return AttachmentKind.IMAGE
            val ext = f.name.substringAfterLast('.', "").lowercase()
            if (ext in TEXT_EXTENSIONS || f.name.lowercase() in TEXT_EXTENSIONS) return AttachmentKind.TEXT
            return if (looksLikeText(head)) AttachmentKind.TEXT else AttachmentKind.FILE
        }

        /** No NUL bytes and valid UTF-8 (allowing a cut multi-byte char at the end of the sample). */
        fun looksLikeText(sample: ByteArray): Boolean {
            if (sample.isEmpty()) return true
            if (sample.any { it == 0.toByte() }) return false
            val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            for (cut in 0..3) {
                if (sample.size - cut <= 0) break
                try { dec.reset(); dec.decode(java.nio.ByteBuffer.wrap(sample, 0, sample.size - cut)); return true } catch (_: CharacterCodingException) {}
            }
            return false
        }

        fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "bmp" -> "image/bmp"; "heic", "heif" -> "image/heic"; "tif", "tiff" -> "image/tiff"
            "pdf" -> "application/pdf"; "zip" -> "application/zip"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }

        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024))
        }
    }
}

/**
 * Turns a user message's attachments into what the model sees. Images go through the upstream
 * ImageBudget (fit within MAX_EDGE_PX for the model — the original stays on disk — then the
 * per-message byte budget), exactly like ChatViewModel.prepareUserAttachments.
 */
class AttachmentShaper(private val store: AttachmentStore, private val maxInlineTextChars: () -> Int) {

    class Shaped(
        /** Model parts for images, in attachment order (caption, pixels, optional downscale note). */
        val imageParts: List<AgentContentPart>,
        /** Text appended to the user's message: inlined text files + upstream <user-attached-files>. */
        val textSuffix: String,
    )

    fun shape(attachments: List<Attachment>): Shaped {
        if (attachments.isEmpty()) return Shaped(emptyList(), "")
        data class Img(val bytes: ByteArray, val mime: String, val path: String, val note: String?)
        val images = mutableListOf<Img>()
        val inline = StringBuilder()
        val metas = mutableListOf<Pair<Attachment, File>>()
        for (a in attachments) {
            val f = store.fileOf(a)
            if (!f.isFile) {
                inline.append("\n\n[attachment ${a.fileName} is missing on disk: ${f.absolutePath}]")
                continue
            }
            when (a.kind) {
                AttachmentKind.IMAGE -> {
                    val raw = f.readBytes()
                    val down = ImageBudget.downscaleForModel(f, raw)
                    val bytes = down?.bytes ?: raw
                    val mime = ImageBudget.sniffFormat(bytes).mimeType ?: a.mimeType
                    images += Img(bytes, mime, f.absolutePath, down?.let { ImageBudget.downscaleNote(it, f.absolutePath) })
                }
                AttachmentKind.TEXT -> {
                    val text = runCatching { f.readText(Charsets.UTF_8) }.getOrElse { "" }
                    val cap = maxInlineTextChars().coerceAtLeast(1000)
                    val body = if (text.length > cap) text.take(cap) else text
                    inline.append("\n\n<attached-file name=\"").append(xml(a.fileName)).append("\" path=\"").append(xml(f.absolutePath))
                        .append("\" chars=\"").append(text.length).append("\">\n").append(body)
                    if (text.length > cap) inline.append("\n[… truncated: showing the first $cap of ${text.length} characters. Read the rest with file_read on the path above.]")
                    inline.append("\n</attached-file>")
                    metas += a to f
                }
                AttachmentKind.FILE -> metas += a to f
            }
        }
        val budget = ImageBudget.applyMessageBudget(images.map { it.bytes })
        val parts = mutableListOf<AgentContentPart>()
        budget.keptBytes.forEachIndexed { i, kept ->
            val img = images[i]
            val mime = if (kept === img.bytes) img.mime else (ImageBudget.sniffFormat(kept).mimeType ?: "image/jpeg")
            parts += AgentContentPart.Text("[attached image: ${img.path}]")
            parts += AgentContentPart.ImageData(kept, mime, linuxPath = img.path)
            img.note?.let { parts += AgentContentPart.Text(it) }
        }
        images.drop(budget.keptBytes.size).forEach {
            parts += AgentContentPart.Text("[image not sent: over the ${ImageBudget.MAX_TOTAL_BYTES / (1024 * 1024)} MB per-message budget. Original at ${it.path}]")
        }
        val files = metas.filter { it.first.kind == AttachmentKind.FILE || it.first.kind == AttachmentKind.TEXT }
        val xmlBlock = if (files.isEmpty()) "" else buildString {
            append("\n\n<user-attached-files>\n")
            for ((a, f) in files) {
                append("  <file path=\"").append(xml(f.absolutePath)).append("\" name=\"").append(xml(a.fileName))
                    .append("\" size=\"").append(f.length()).append("\" modified=\"").append(Instant.ofEpochMilli(f.lastModified()))
                    .append("\" />\n")
            }
            append("</user-attached-files>")
        }
        return Shaped(parts, inline.toString() + xmlBlock)
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
