// Desktop shim for android.os.Build / android.os.Debug used by ported code.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package android.os

object Build {
    object VERSION {
        @JvmField val RELEASE: String? = System.getProperty("os.version")
        @JvmField val SDK_INT: Int = 0
    }
    @JvmField val MODEL: String? = System.getProperty("os.name")
    @JvmField val MANUFACTURER: String? = "desktop"
}

object Debug {
    @JvmStatic fun getNativeHeapAllocatedSize(): Long = 0L
    @JvmStatic fun getPss(): Long = 0L
}
