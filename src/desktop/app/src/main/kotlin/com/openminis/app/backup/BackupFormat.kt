// Extracted from OpenMinis Android backup/BackupFormat.kt (only the wire serializers the data model needs).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.app.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object Iso8601MillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601Millis", PrimitiveKind.STRING)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(fmt().format(value))
    }

    override fun deserialize(decoder: Decoder): Long {
        // Tolerate both a JSON string (ISO-8601) and a JSON number (legacy
        // epoch millis). Use JsonDecoder to peek at the actual element type so
        // an old numeric package doesn't throw.
        val jd = decoder as? JsonDecoder
            ?: return runCatching { fmt().parse(decoder.decodeString())?.time }.getOrNull() ?: 0L
        val el = jd.decodeJsonElement()
        val prim = el as? JsonPrimitive ?: return 0L
        prim.longOrNull?.let { return it } // legacy numeric epoch millis
        val s = prim.content
        return runCatching { fmt().parse(s)?.time }.getOrNull() ?: 0L
    }
}

object Iso8601MillisNullableSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601MillisNullable", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull()
        else Iso8601MillisSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val jd = decoder as? JsonDecoder ?: return Iso8601MillisSerializer.deserialize(decoder)
        val el = jd.decodeJsonElement()
        if (el is kotlinx.serialization.json.JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        prim.longOrNull?.let { return it }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(prim.content)?.time
        }.getOrNull()
    }
}
