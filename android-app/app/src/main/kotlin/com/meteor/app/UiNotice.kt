package com.meteor.app

enum class NoticeKind(val title: String) { INFO("Notice"), WARNING("Attention needed"), ERROR("Could not complete") }

data class UiNotice(val text: String, val kind: NoticeKind) {
    companion object {
        fun info(text: String) = UiNotice(text, NoticeKind.INFO)
        fun warning(text: String) = UiNotice(text, NoticeKind.WARNING)
        fun error(text: String) = UiNotice(text, NoticeKind.ERROR)
    }
}
