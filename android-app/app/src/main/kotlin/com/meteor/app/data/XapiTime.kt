package com.meteor.app.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Display-only handling of the datetimes XAPI reports as text: VM `start_time` (last boot), `snapshot_time`, host
 * `other_config:boot_time`. XAPI's compact form is `yyyyMMdd'T'HH:mm:ss[.fff]Z` (UTC); some servers and plugins send
 * ISO-8601.
 *
 * Nothing here is stored or compared. Reboot proof keeps comparing the raw text through [PowerRules] and
 * [TargetRules.hostBootChanged], so a change in how a value is shown can never change whether a reboot is proven.
 * An unparseable value is shown as the server sent it, never hidden and never guessed.
 */
object XapiTime {
    private val compact = DateTimeFormatterBuilder().appendPattern("uuuuMMdd'T'HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .toFormatter().withResolverStyle(ResolverStyle.STRICT)
    /** Compact datetimes may also carry a numeric offset instead of `Z`, e.g. `20261107T18:24:58+0100`. */
    private val compactOffset = DateTimeFormatterBuilder().appendPattern("uuuuMMdd'T'HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .appendOffset("+HHmm", "Z").toFormatter().withResolverStyle(ResolverStyle.STRICT)

    /** Smallest and largest epoch-seconds value treated as a plausible host boot marker (2001 .. 2100). */
    private const val EPOCH_MIN = 1_000_000_000.0
    private const val EPOCH_MAX = 4_102_444_800.0

    /** The instant a datetime text denotes, or null when it is not one of the supported forms. Pre-1970 values are rejected. */
    fun parse(value: String?): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text.removeSuffix("Z"), compact).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text, compactOffset).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC) }.getOrNull()
        return parsed?.takeIf { it.epochSecond > 0 }
    }

    /** A host boot marker is `other_config:boot_time`: Unix seconds, possibly with a fraction. Not a datetime string. */
    fun parseEpochSeconds(value: String?): Instant? {
        val seconds = value?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in EPOCH_MIN..EPOCH_MAX } ?: return null
        return Instant.ofEpochMilli(Math.round(seconds * 1000.0))
    }

    /** Localized date and time, e.g. "Nov 7, 2026, 6:24 PM" for English (US). */
    fun localized(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)

    /** [value] as a localized date and time, or the raw text when it cannot be parsed, or [missing] when it is blank. */
    fun display(value: String?, missing: String = "Not reported", zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return missing
        return parse(text)?.let { localized(it, zone, locale) } ?: text
    }

    /** Same as [display] for a host boot marker, which is epoch seconds as often as it is a datetime text. */
    fun displayBootMarker(value: String?, missing: String = "unknown", zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return missing
        val instant = parse(text) ?: parseEpochSeconds(text)
        return instant?.let { localized(it, zone, locale) } ?: text
    }
}
