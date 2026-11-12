package com.sreeram.jarvis.msg

// A plain copy of one on-screen element. Everything that decides things (who sent a message, whether it was delivered) works on these,
// so it can be tested without a phone: the accessibility service converts real nodes into these and nothing else touches Android.
data class ScreenNode(
    val id: String,          // resource id without the package prefix, e.g. "message_text"; empty if none
    val text: String?,
    val desc: String?,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val editable: Boolean = false,
    val clickable: Boolean = false
)
