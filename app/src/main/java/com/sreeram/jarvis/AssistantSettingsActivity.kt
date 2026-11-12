package com.sreeram.jarvis

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class AssistantSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Buddy is your assistant. Hold the power button or use the assistant gesture to talk."
            setPadding(48, 48, 48, 48)
        })
    }
}
