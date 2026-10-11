// OpenMinis Desktop — P2-6 "Check for updates": one request on click, through the proxy settings; no download.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object UpdateCheck {
    const val LATEST = "https://api.github.com/repos/lixin786/openminis-windows/releases/latest"
    const val RELEASES = "https://github.com/lixin786/openminis-windows/releases"

    sealed interface Outcome {
        data class Newer(val tag: String, val url: String) : Outcome
        data class UpToDate(val tag: String) : Outcome
        data object NoRelease : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun userAgent(version: String) = "OpenMinis-desktop/$version"

    /** Numeric parts only: "v1.15.0" → [1, 15, 0], "1.14-desktop" → [1, 14]; missing parts count as 0. */
    fun compare(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a).map { it.value.toBigInteger() }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toBigInteger() }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(y.getOrNull(i) ?: java.math.BigInteger.ZERO)
            if (c != 0) return c
        }
        return 0
    }

    fun parse(body: String, current: String): Outcome {
        val o = try { JSONObject(body) } catch (e: Exception) { return Outcome.Failed("unexpected response") }
        val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return Outcome.Failed("unexpected response")
        if (Regex("\\d").containsMatchIn(tag).not()) return Outcome.Failed("unexpected tag $tag")
        val url = o.optString("html_url").takeIf { it.startsWith("https://github.com/") } ?: RELEASES
        return if (compare(tag, current) > 0) Outcome.Newer(tag, url) else Outcome.UpToDate(tag)
    }

    /** Blocking; call off the UI thread. [client] defaults to one built through the proxy settings. */
    fun check(current: String, client: OkHttpClient = com.openminis.desktop.net.DesktopHttp.newClient(15), url: String = LATEST): Outcome = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent(current))
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> Outcome.NoRelease
                !r.isSuccessful -> Outcome.Failed("HTTP ${r.code}")
                else -> parse(r.body?.string().orEmpty(), current)
            }
        }
    } catch (e: Exception) {
        Outcome.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }
}
