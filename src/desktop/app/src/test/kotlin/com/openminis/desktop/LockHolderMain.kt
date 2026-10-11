// Test helper: a separate process that takes the scheduler lock and then hangs until it is killed.
package com.openminis.desktop

import com.openminis.desktop.scheduled.SchedulerLock
import java.io.File

object LockHolderMain {
    @JvmStatic fun main(args: Array<String>) {
        val r = SchedulerLock(File(args[0]), "headless").tryAcquire()
        println(if (r is SchedulerLock.Result.Acquired) "LOCKED" else "BUSY $r"); System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
