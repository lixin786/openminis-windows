// Desktop subset of OpenMinis' VisionGroupResolver (vision groups are not ported; see PORTING.md).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.app.tools

object VisionGroupResolver {
    fun visionImagePathNote(path: String?): String? {
        val p = path?.takeIf { it.isNotBlank() } ?: return null
        return "[The image shown above is also saved at $p — this is that same image, " +
            "not an additional one. Use the path only if you need to work on the file " +
            "itself (re-read it at full resolution, crop, OCR, inspect metadata).]"
    }
}
