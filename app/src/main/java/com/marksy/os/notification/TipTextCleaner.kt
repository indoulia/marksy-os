package com.marksy.os.notification

import java.util.Locale

/** The medium of a captured message, spelled as `POST /tips/ingest-text` expects (tip-ledger spec §4). */
enum class CaptureMedium {
    APP_NOTIFICATION, SMS, WHATSAPP, TELEGRAM;

    val isChat: Boolean get() = this == WHATSAPP || this == TELEGRAM

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val TELEGRAM_PACKAGES = setOf("org.telegram.messenger", "org.telegram.messenger.web")
        private val SMS_PACKAGES = setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging")

        fun of(packageName: String): CaptureMedium = when (packageName.trim().lowercase(Locale.ROOT)) {
            in WHATSAPP_PACKAGES -> WHATSAPP
            in TELEGRAM_PACKAGES -> TELEGRAM
            in SMS_PACKAGES -> SMS
            else -> APP_NOTIFICATION
        }
    }
}

/** Mirrors marksy-api `app/tip_text_cleaning.py` rule for rule (tip-ledger spec §5.1); the server re-applies it. */
object TipTextCleaner {
    const val MASK_EMAIL = "[EMAIL]"
    const val MASK_PHONE = "[PHONE]"
    const val MASK_PAN = "[PAN]"
    const val MASK_NUMBER = "[NUMBER]"
    const val MASK_USER = "[USER]"
    const val LABEL_MAX_LENGTH = 128

    // Python's \w, \d and \s are Unicode-aware; spelled out so the JVM and Android ICU agree with them.
    private const val WORD = "\\p{L}\\p{N}_"
    private const val SPACE_OR_DASH = "[\\s\\p{Z}-]"
    private const val GROUP_SENDER = " @ "
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?<![$WORD+])(?:(?:\\+91|0091|91|0)$SPACE_OR_DASH?)?[6-9]\\p{Nd}{4}$SPACE_OR_DASH?\\p{Nd}{5}(?![$WORD])")
    private val PAN = Regex("(?<![$WORD])[A-Z]{5}[0-9]{4}[A-Z](?![$WORD])")
    private val DIGIT_RUN = Regex("\\p{Nd}{8,}")
    private val MASK_TOKEN = Regex("\\[(?:EMAIL|PHONE|PAN|NUMBER|USER)\\]", RegexOption.IGNORE_CASE)
    private val LETTER = Regex("[\\p{L}\\p{Nl}\\p{No}]")

    fun clean(text: String, username: String?): String {
        var cleaned = EMAIL.replace(text, MASK_EMAIL)
        cleaned = PHONE.replace(cleaned, MASK_PHONE)
        cleaned = PAN.replace(cleaned, MASK_PAN)
        cleaned = DIGIT_RUN.replace(cleaned, MASK_NUMBER)
        val name = username?.trim().orEmpty()
        if (name.length >= 3) {
            cleaned = Regex("(?<![$WORD])${Regex.escape(name)}(?![$WORD])", RegexOption.IGNORE_CASE).replace(cleaned, MASK_USER)
        }
        return cleaned
    }

    /** The channel a message came through, never the person who sent it inside a group. */
    fun channelLabel(medium: CaptureMedium, label: String?, username: String?): String? {
        if (label.isNullOrBlank()) return null
        var value = label.trim()
        if (medium.isChat && GROUP_SENDER in value) value = value.substringAfterLast(GROUP_SENDER).trim()
        return clean(value, username).take(LABEL_MAX_LENGTH).ifEmpty { null }
    }

    fun isMaskOnly(label: String): Boolean =
        MASK_TOKEN.containsMatchIn(label) && !LETTER.containsMatchIn(MASK_TOKEN.replace(label, ""))
}
