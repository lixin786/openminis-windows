// Desktop shim for an Android framework API so ported OpenMinis code compiles unchanged on the JVM.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package android.content

import java.io.InputStream

/**
 * Minimal stand-in for [android.content.Context]: just enough for upstream singletons
 * that take a Context to cache (TextSegmenter, BPETokenizer). Assets resolve against
 * the JVM classpath root (`src/main/resources`).
 */
open class Context {
    open val applicationContext: Context get() = this
    open val assets: AssetManager = AssetManager()

    class AssetManager {
        fun open(fileName: String): InputStream =
            Context::class.java.classLoader.getResourceAsStream(fileName.removePrefix("/"))
                ?: throw java.io.FileNotFoundException("asset not on classpath: $fileName")
    }

    companion object {
        /** The single process-wide context the desktop hands to upstream code. */
        val APP: Context = Context()
    }
}
