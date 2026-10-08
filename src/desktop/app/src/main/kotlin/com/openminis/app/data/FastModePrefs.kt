// Desktop replacement for the Android SharedPreferences-backed FastModePrefs (in-memory, set from settings).
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.app.data

object FastModePrefs {
    @Volatile private var cachedEnabled = false
    fun isEnabled(): Boolean = cachedEnabled
    fun setEnabled(enabled: Boolean) { cachedEnabled = enabled }
}
