// Desktop stub of OpenMinis' Android NetworkMonitor: only the shared OkHttp pool is needed.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
package com.openminis.app.network

class NetworkMonitor private constructor() {
    companion object {
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(5, 5, java.util.concurrent.TimeUnit.MINUTES)
    }
}
