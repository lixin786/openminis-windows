package com.openminis.desktop.mcp

/** Per-chat MCP switches (Conversation.mcpOff). Pure, unit-tested. */
object McpChatScope {
    fun withServer(off: List<String>, server: String, on: Boolean): List<String> =
        if (on) off.filterNot { it == server } else (off.filterNot { it == server } + server)

    data class Row(val server: McpServerConfig, val globallyEnabled: Boolean, val onInChat: Boolean)

    /** Settings servers in their own order: on in this chat = enabled in Settings and not switched off here. */
    fun rows(servers: List<McpServerConfig>, off: List<String>): List<Row> =
        servers.map { Row(it, it.enabled, it.enabled && it.name !in off) }

    /** (on in this chat, enabled in Settings) for the toolbar badge. */
    fun counts(servers: List<McpServerConfig>, off: List<String>): Pair<Int, Int> =
        servers.count { it.enabled && it.name !in off } to servers.count { it.enabled }
}
