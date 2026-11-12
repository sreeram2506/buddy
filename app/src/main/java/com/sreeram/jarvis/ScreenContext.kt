package com.sreeram.jarvis

import android.graphics.Bitmap

// What was on screen when Buddy was summoned (Android's assist feature hands this to the default assistant).
object ScreenContext {
    @Volatile var text: String? = null
    @Volatile var screenshot: Bitmap? = null
    @Volatile var time = 0L
}
