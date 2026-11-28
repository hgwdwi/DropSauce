package org.koitharu.kotatsu.local.data.input

import java.nio.charset.Charset

/**
 * Explicit encoding choices for TXT import.
 *
 * AUTO preserves the existing decoder behavior: UTF-8 first, then GB18030.
 * A Unicode BOM always takes precedence over an explicit choice.
 */
internal enum class TxtNovelEncoding(
    val intentValue: String?,
    private val charsetName: String?,
) {
    AUTO(null, null),
    UTF_8("UTF-8", "UTF-8"),
    UTF_16("UTF-16", "UTF-16"),
    GB18030("GB18030", "GB18030"),
    SHIFT_JIS("Shift_JIS", "Shift_JIS");

    fun charsetOrNull(): Charset? = charsetName?.let(Charset::forName)

    companion object {
        fun fromIntentValue(value: String?): TxtNovelEncoding =
            values().firstOrNull { it.intentValue == value }
                ?: throw IllegalArgumentException("Unsupported TXT encoding: $value")
    }
}
