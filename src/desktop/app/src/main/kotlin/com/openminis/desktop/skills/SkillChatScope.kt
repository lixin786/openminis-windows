package com.openminis.desktop.skills

/**
 * Per-chat skill switches (Conversation.skillsOff), the skill twin of McpChatScope. Pure, unit-tested.
 * A chat can only narrow the global set: a skill disabled in Settings stays off in every chat, and a chat with no
 * override (empty list) follows Settings exactly.
 */
object SkillChatScope {
    fun withSkill(off: List<String>, id: String, on: Boolean): List<String> =
        if (on) off.filterNot { it == id } else (off.filterNot { it == id } + id)

    data class Row(val skill: Skill, val globallyEnabled: Boolean, val onInChat: Boolean)

    fun rows(skills: List<Skill>, globallyDisabled: Collection<String>, off: Collection<String>): List<Row> =
        skills.map { val g = it.id !in globallyDisabled; Row(it, g, g && it.id !in off) }

    /** (on in this chat, enabled in Settings) for the toolbar badge. */
    fun counts(skills: List<Skill>, globallyDisabled: Collection<String>, off: Collection<String>): Pair<Int, Int> =
        rows(skills, globallyDisabled, off).let { r -> r.count { it.onInChat } to r.count { it.globallyEnabled } }

    /** The skills the system prompt lists for a chat. */
    fun active(skills: List<Skill>, globallyDisabled: Collection<String>, off: Collection<String>): List<Skill> =
        skills.filter { it.id !in globallyDisabled && it.id !in off }
}
