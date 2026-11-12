package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class XapiTimeTest {
    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")
    // 2026-11-07 18:24:58 UTC
    private val instant = Instant.parse("2026-11-07T18:24:58Z")

    @Test fun compactXapiFormIsParsedAsUtc() {
        assertEquals(instant, XapiTime.parse("20261107T18:24:58Z"))
        assertEquals(instant, XapiTime.parse("20261107T18:24:58"))
        assertEquals(Instant.parse("2026-11-07T18:24:58.250Z"), XapiTime.parse("20261107T18:24:58.250Z"))
        assertEquals(instant, XapiTime.parse("  20261107T18:24:58Z "))
        // A numeric offset is honoured rather than read as UTC.
        assertEquals(instant, XapiTime.parse("20261107T19:24:58+0100"))
    }
    @Test fun isoForms() {
        assertEquals(instant, XapiTime.parse("2026-11-07T18:24:58Z"))
        assertEquals(instant, XapiTime.parse("2026-11-07T19:24:58+01:00"))
        assertEquals(Instant.parse("2026-11-07T18:24:58.123456Z"), XapiTime.parse("2026-11-07T18:24:58.123456Z"))
        assertEquals(instant, XapiTime.parse("2026-11-07T18:24:58")) // no zone: XAPI means UTC
    }
    @Test fun unparseableAndImplausibleValuesAreNotInvented() {
        for (text in listOf("", "   ", "not a date", "20261107", "20261107T25:00:00Z", "20261340T10:00:00Z", "yesterday", "1700000000"))
            assertNull(text, XapiTime.parse(text))
        assertNull(XapiTime.parse(null))
        assertNull("the epoch itself is XAPI's 'never'", XapiTime.parse("19700101T00:00:00Z"))
        assertNull(XapiTime.parse("19691231T23:59:59Z"))
    }
    @Test fun displayIsLocalizedDateAndTimeInTheGivenZone() {
        val text = XapiTime.display("20261107T18:24:58Z", zone = utc, locale = Locale.US)
        assertTrue(text, "2026" in text && "Nov" in text && "6:24" in text)
        assertFalse("the raw compact form never reaches the screen", "20261107T18" in text || "T18:24:58Z" in text)
        // The same instant reads as local time elsewhere.
        val local = XapiTime.display("20261107T18:24:58Z", zone = berlin, locale = Locale.US)
        assertTrue(local, "7:24" in local)
        // A different locale changes the wording, not the instant.
        val german = XapiTime.display("20261107T18:24:58Z", zone = berlin, locale = Locale.GERMANY)
        assertTrue(german, "07.11.2026" in german && "19:24" in german)
    }
    @Test fun displayAcceptsTheSameFormsAsParse() {
        val expected = XapiTime.display("20261107T18:24:58Z", zone = utc, locale = Locale.US)
        assertEquals(expected, XapiTime.display("2026-11-07T18:24:58Z", zone = utc, locale = Locale.US))
        assertEquals(expected, XapiTime.display("2026-11-07T19:24:58+01:00", zone = utc, locale = Locale.US))
    }
    @Test fun unparseableTextIsShownAsReceivedAndMissingValuesSaySo() {
        assertEquals("sometime last week", XapiTime.display("sometime last week", zone = utc, locale = Locale.US))
        assertEquals("sometime last week", XapiTime.display("  sometime last week ", zone = utc, locale = Locale.US))
        assertEquals("Not reported", XapiTime.display(null))
        assertEquals("Not reported", XapiTime.display("  "))
        assertEquals("Time not reported", XapiTime.display(null, missing = "Time not reported"))
    }
    @Test fun hostBootMarkersAreEpochSecondsAsOftenAsDatetimes() {
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), XapiTime.parseEpochSeconds("1700000000"))
        assertEquals(Instant.ofEpochMilli(1_700_000_000_250L), XapiTime.parseEpochSeconds("1700000000.25"))
        assertNull(XapiTime.parseEpochSeconds("12345"))
        assertNull(XapiTime.parseEpochSeconds("-5"))
        assertNull(XapiTime.parseEpochSeconds("NaN"))
        assertNull(XapiTime.parseEpochSeconds("abc"))
        val epoch = XapiTime.displayBootMarker("1700000000", zone = utc, locale = Locale.US)
        assertTrue(epoch, "2023" in epoch && "Nov" in epoch)
        assertEquals(XapiTime.display("20261107T18:24:58Z", zone = utc, locale = Locale.US),
            XapiTime.displayBootMarker("20261107T18:24:58Z", zone = utc, locale = Locale.US))
        assertEquals("unknown", XapiTime.displayBootMarker(null))
        assertEquals("garbage", XapiTime.displayBootMarker("garbage"))
        // A small number is not guessed to be a date.
        assertEquals("12345", XapiTime.displayBootMarker("12345"))
    }
    @Test fun displayDoesNotChangeWhatRebootProofSees() {
        // Reboot proof keeps working on the raw text; formatting is display only.
        val before = "20261107T18:24:58Z"
        val after = "20261107T18:30:00Z"
        assertTrue(PowerRules.validBoot(before))
        assertTrue(PowerRules.validBoot(after))
        assertNotEquals(XapiTime.display(before, zone = utc, locale = Locale.US), before)
        assertTrue(TargetRules.hostBootChanged("1700000000.5", "1700000100.5"))
        assertFalse(TargetRules.hostBootChanged("1700000000.5", "1700000000.5"))
        assertNotEquals(XapiTime.displayBootMarker("1700000000.5", zone = utc, locale = Locale.US), "1700000000.5")
    }
}
