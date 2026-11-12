package com.sreeram.jarvis.msg

// Waits for something to become true instead of sleeping a fixed time and hoping.
object Waiter {
    fun until(timeoutMs: Long, stepMs: Long = 250, now: () -> Long = System::currentTimeMillis, sleep: (Long) -> Unit = Thread::sleep, check: () -> Boolean): Boolean {
        val end = now() + timeoutMs
        while (true) {
            if (check()) return true
            if (now() >= end) return false
            sleep(stepMs)
        }
    }
}
