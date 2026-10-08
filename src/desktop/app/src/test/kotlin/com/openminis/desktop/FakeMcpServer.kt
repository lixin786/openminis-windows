// A tiny MCP stdio server for the P1-1 tests, launched as its own JVM (java -cp <test classpath>).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream

object FakeMcpServer {
    @JvmStatic
    fun main(args: Array<String>) {
        // args[0] = launch-counter file (appended once per start)
        args.getOrNull(0)?.let { File(it).appendText("x", Charsets.UTF_8) }
        val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
        System.err.println("fake mcp: started")
        val r = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        fun reply(id: Any, result: JSONObject) = out.println(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
        fun text(t: String, isError: Boolean = false) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", t))).put("isError", isError)
        fun tool(n: String, d: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
            JSONObject().put("name", n).put("description", d).put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req)))
        while (true) {
            val line = r.readLine() ?: return
            if (line.isBlank()) continue
            val m = JSONObject(line)
            val method = m.optString("method")
            if (!m.has("id")) continue // notification
            val id = m.get("id")
            when (method) {
                "initialize" -> reply(id, JSONObject().put("protocolVersion", m.getJSONObject("params").getString("protocolVersion"))
                    .put("capabilities", JSONObject().put("tools", JSONObject())).put("serverInfo", JSONObject().put("name", "fake").put("version", "1")))
                "tools/list" -> {
                    val cursor = m.optJSONObject("params")?.optString("cursor", "").orEmpty()
                    if (cursor.isEmpty()) reply(id, JSONObject().put("tools", JSONArray()
                        .put(tool("echo", "Echo text back", JSONObject().put("text", JSONObject().put("type", "string").put("description", "what to echo"))
                            .put("tags", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))), listOf("text")))
                        .put(tool("fail", "Always fails"))).put("nextCursor", "p2"))
                    else reply(id, JSONObject().put("tools", JSONArray().put(tool("slow", "Sleeps")).put(tool("crash", "Exits")).put(tool("env", "Reads FAKE_ENV"))
                        .put(tool("image", "Returns an image"))))
                }
                "tools/call" -> {
                    val p = m.getJSONObject("params"); val a = p.optJSONObject("arguments") ?: JSONObject()
                    when (p.getString("name")) {
                        "echo" -> reply(id, text("echo: " + a.optString("text")))
                        "fail" -> reply(id, text("boom", isError = true))
                        "slow" -> { Thread.sleep(5_000); reply(id, text("late")) }
                        "crash" -> { System.err.println("fake mcp: crashing"); out.flush(); Runtime.getRuntime().halt(3) }
                        "env" -> reply(id, text("FAKE_ENV=" + (System.getenv("FAKE_ENV") ?: "")))
                        "image" -> reply(id, JSONObject().put("content", JSONArray().put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", "iVBORw0KGgo="))
                            .put(JSONObject().put("type", "text").put("text", "caption"))))
                        else -> out.println(JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", -32602).put("message", "unknown tool")).toString())
                    }
                }
                else -> out.println(JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", -32601).put("message", "no $method")).toString())
            }
        }
    }
}
