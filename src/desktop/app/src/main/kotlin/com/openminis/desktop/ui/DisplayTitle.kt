package com.openminis.desktop.ui

import com.openminis.desktop.core.Conversation
import com.openminis.desktop.core.DEFAULT_CONVERSATION_TITLE
import com.openminis.desktop.i18n.tr

/** Title as shown in the UI: the untitled default is localized, user/auto titles are shown as is. */
fun Conversation.displayTitle(): String = displayTitle(title)

fun displayTitle(title: String): String = if (title == DEFAULT_CONVERSATION_TITLE) tr("chat.untitled") else title
