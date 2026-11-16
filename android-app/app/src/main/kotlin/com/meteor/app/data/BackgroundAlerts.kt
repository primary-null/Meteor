package com.meteor.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// --- B3 background/summary ---
// Pure logic for the opt-in background alert check: per-profile preferences, which profiles are due, the
// last-notified watermark with duplicate suppression, and notification text. No Android, no network.
// See METEOR-B3-BACKGROUND-SUMMARY.md.

/** The lowest alert priority that is notified. XAPI priority 1 is the most urgent. */
enum class NotifyThreshold(val maxPriority: Int, val label: String, val storageCode: Int) {
    CRITICAL(1, "Critical only", 1), HIGH(2, "Critical and High", 2), WARNING(3, "Up to Warning", 3);

    fun includes(severity: AlertSeverity): Boolean = severity != AlertSeverity.UNRATED && severity.priority <= maxPriority

    companion object {
        fun of(code: Int?): NotifyThreshold = entries.firstOrNull { it.storageCode == code } ?: HIGH
    }
}

/**
 * Per-profile, stored locally (never in the encrypted profile document). [enabledAt] is the phone time the switch
 * was last turned on; the first check moves it to server time with the skew it measures, and only notifies alerts from
 * then on, so enabling never floods old alerts.
 */
data class AlertNotifySettings(
    val enabled: Boolean = false,
    val threshold: NotifyThreshold = NotifyThreshold.HIGH,
    val intervalMinutes: Int = DEFAULT_INTERVAL,
    val enabledAt: Long? = null,
) {
    init { require(intervalMinutes in INTERVALS) { "Unsupported interval." } }

    companion object {
        val INTERVALS = listOf(15, 30, 60)
        const val DEFAULT_INTERVAL = 30

        /**
         * Turning the switch on stamps a new [enabledAt] (and the caller resets the watermark), so only alerts from
         * then on are notified. Turning it off clears the stamp. Changing priority or interval keeps both.
         */
        fun change(previous: AlertNotifySettings, requested: AlertNotifySettings, now: Long): AlertNotifySettings = when {
            !requested.enabled -> requested.copy(enabledAt = null)
            !previous.enabled -> requested.copy(enabledAt = now)
            else -> requested.copy(enabledAt = previous.enabledAt ?: now)
        }

        /** True when the change starts a new notification period, so stored progress must be forgotten. */
        fun restarts(previous: AlertNotifySettings, next: AlertNotifySettings) = next.enabled != previous.enabled

        fun encode(value: AlertNotifySettings): String =
            "v1;${if (value.enabled) 1 else 0};${value.threshold.storageCode};${value.intervalMinutes};${value.enabledAt ?: ""}"

        /** Anything unreadable decodes as the default (off). */
        fun decode(text: String?): AlertNotifySettings {
            val parts = text?.split(';') ?: return AlertNotifySettings()
            if (parts.size != 5 || parts[0] != "v1") return AlertNotifySettings()
            val interval = parts[3].toIntOrNull()?.takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL
            return AlertNotifySettings(parts[1] == "1", NotifyThreshold.of(parts[2].toIntOrNull()), interval,
                parts[4].toLongOrNull()?.takeIf { it > 0 })
        }
    }
}

/**
 * What has already been handled for one profile: the newest server timestamp seen ([newest]) and the references of
 * handled messages inside the overlap window ([recent], ref -> server timestamp). It belongs to one pool ([authority],
 * the pool UUID that owns the message store), so it survives a re-trusted certificate or a followed pool master; a
 * watermark of another pool is never applied. [origin] records the connection it was taken with.
 */
data class AlertWatermark(val origin: String, val authority: String, val newest: Long, val recent: Map<String, Long> = emptyMap()) {
    override fun toString() = "AlertWatermark(newest=$newest, recent=${recent.size})"

    companion object {
        private val refPattern = Regex("[A-Za-z0-9:._-]{1,200}")
        private val idPattern = Regex("[A-Za-z0-9:._-]{1,200}")

        fun encode(value: AlertWatermark): String = buildString {
            append("v1\n").append(value.origin).append('\n').append(value.authority).append('\n').append(value.newest)
            value.recent.forEach { (ref, at) -> append('\n').append(at).append(' ').append(ref) }
        }

        fun decode(text: String?): AlertWatermark? = runCatching {
            val lines = requireNotNull(text).split('\n')
            require(lines.size >= 4 && lines[0] == "v1")
            require(lines[1].matches(Regex("[0-9a-f]{64}")) && lines[2].matches(idPattern))
            val recent = lines.drop(4).take(BackgroundAlerts.MAX_RECENT).associate { line ->
                val at = line.substringBefore(' ').toLong()
                val ref = line.substringAfter(' ')
                require(ref.matches(refPattern))
                ref to at
            }
            AlertWatermark(lines[1], lines[2], lines[3].toLong(), recent)
        }.getOrNull()
    }
}

/** Why a profile needs the user. Posted once per [marker]; a successful check clears it. */
enum class AttentionKind { CERTIFICATE_CHANGED, AUTHENTICATION_FAILED, POOL_MEMBER, CHECKS_FAILING }

/**
 * The server's clock as read inside the background session, with the phone's clock at the same moment. Every value that is
 * compared with a message timestamp (which is server time) comes from [serverMillis] or from a phone time moved over by
 * [skewMillis]; the phone's clock is never compared with a message directly.
 */
data class ServerClock(val serverMillis: Long, val phoneMillis: Long) {
    val skewMillis: Long get() = serverMillis - phoneMillis
    fun fromPhone(phone: Long): Long = phone + skewMillis
}

/** Thrown when a check needs the server's clock (first check, another pool) and the server did not report it. */
class ServerTimeUnavailable : IllegalStateException("The server did not report its time.")

/** Chooses `message.get_since` once the pool and the server's clock are known; null when that cannot be decided. */
fun interface BackgroundSince { fun since(authority: String, clock: ServerClock?): Long? }

/** How one profile's last check ended. Persisted by name, so only add values. */
enum class CheckOutcome(val success: Boolean = false, val needsUser: Boolean = false, val quickRetry: Boolean = false) {
    NOTIFIED(success = true), NOTHING_NEW(success = true),
    /** Pin, credentials or pool role need the user: never retried quickly. */
    CERTIFICATE_CHANGED(needsUser = true), AUTHENTICATION_FAILED(needsUser = true), POOL_MEMBER(needsUser = true),
    /** Transport failures and timeouts are the only ones worth a quick, bounded retry. */
    FAILED(quickRetry = true), TIMED_OUT(quickRetry = true),
    SERVER_ERROR, SERVER_TIME_UNAVAILABLE, NOTIFY_FAILED,
    /** The run ran out of time before this profile; nothing is recorded for it. */
    SKIPPED_BUDGET
}

/**
 * What the last checks of one profile came to. [attemptAt] is the phone time the last attempt started (only used to decide
 * when to try again, never compared with a message), [lastSuccessAt] the start of the last attempt that worked, and [failures]
 * the checks that failed in a row. [outcome] is null for a time recorded by an earlier release.
 */
data class CheckStatus(val attemptAt: Long, val outcome: CheckOutcome?, val lastSuccessAt: Long?, val failures: Int = 0) {
    companion object {
        fun encode(value: CheckStatus) = "v1;${value.attemptAt};${value.outcome?.name ?: ""};${value.lastSuccessAt ?: ""};${value.failures}"

        fun decode(text: String?): CheckStatus? = runCatching {
            val parts = requireNotNull(text).split(';')
            require(parts.size == 5 && parts[0] == "v1")
            CheckStatus(parts[1].toLong(), CheckOutcome.entries.firstOrNull { it.name == parts[2] }, parts[3].toLongOrNull(),
                parts[4].toInt().coerceAtLeast(0))
        }.getOrNull()

        /** The status after an attempt that started at [at] and ended with [outcome]. */
        fun after(previous: CheckStatus?, at: Long, outcome: CheckOutcome) = CheckStatus(at, outcome,
            if (outcome.success) at else previous?.lastSuccessAt, if (outcome.success) 0 else (previous?.failures ?: 0) + 1)
    }
}

/** Why a whole run did nothing for every profile. */
enum class RunProblem { OK, VAULT_UNAVAILABLE, NOTIFICATIONS_BLOCKED, STOPPED }
data class RunNote(val at: Long, val problem: RunProblem) {
    companion object {
        fun encode(value: RunNote) = "v1;${value.at};${value.problem.name}"
        fun decode(text: String?): RunNote? = runCatching {
            val parts = requireNotNull(text).split(';')
            require(parts.size == 3 && parts[0] == "v1")
            RunNote(parts[1].toLong(), RunProblem.valueOf(parts[2]))
        }.getOrNull()
    }
}

/** Everything Settings shows about the background checks. */
data class BackgroundChecks(val statuses: Map<String, CheckStatus> = emptyMap(), val runNote: RunNote? = null)

/** One bounded background read: pool identity, alerts since a server timestamp, and names for a few objects. */
data class BackgroundAlertFetch(
    val authority: String, val poolName: String, val messages: List<AlertMessage>,
    /** `cls:obj_uuid` -> name_label, only for the few objects that will be notified. */
    val objectNames: Map<String, String> = emptyMap(),
    /** The server's clock read in the same session, or null when the server did not report it. */
    val serverClock: ServerClock? = null,
) {
    override fun toString() = "BackgroundAlertFetch(messages=${messages.size})"
}

/** [rewound] is true when the saved watermark was ahead of the server's clock and the new one is deliberately earlier. */
data class BackgroundDecision(val notify: List<AlertMessage>, val watermark: AlertWatermark, val handled: Int, val rewound: Boolean = false)

object BackgroundAlerts {
    /** Same overlap as the A4 inbox: re-read this much before the watermark so out-of-order timestamps are not lost. */
    const val OVERLAP_MILLIS = AlertMerge.OVERLAP_MILLIS
    const val MAX_RECENT = 200
    /** A due profile may be checked this much early, so a 30-minute setting fits a 15-minute WorkManager period. */
    const val FLEX_MILLIS = 5 * 60_000L
    const val MAX_PROFILES_PER_RUN = 10
    /** Individual notifications per profile per run; anything beyond is counted in the group summary. */
    const val MAX_INDIVIDUAL = 5
    /** Individual alert notifications of one profile that may sit in the shade at once, across runs. */
    const val MAX_POSTED = 10
    /** Distinct objects whose names are read for one run. */
    const val MAX_NAMED_OBJECTS = 8

    fun objectKey(message: AlertMessage) = "${message.objectClass.lowercase()}:${message.objectUuid}"

    /** WorkManager period: the shortest interval among enabled profiles, or null when nothing is enabled. */
    fun periodMinutes(settings: Collection<AlertNotifySettings>): Int? =
        settings.filter { it.enabled }.minOfOrNull { it.intervalMinutes }

    /** Quick retries after a transport failure or timeout: after the 1st failure wait 4 minutes, after the 2nd 8; then the normal interval. */
    const val QUICK_RETRIES = 2
    val RETRY_DELAYS_MILLIS = listOf(4 * 60_000L, 8 * 60_000L)
    /** WorkManager's own backoff for those retries is a little longer (5 and 10 minutes), so the gate in [due] never holds a retry back. */
    const val RETRY_BACKOFF_MINUTES = 5L
    /** The "checks are failing" notification is posted at this many failed checks in a row (a first try, two quick retries, one more). */
    const val FAILING_AFTER = 4

    /** True when this status asks for a quick retry that has not been used up. */
    fun retryPending(status: CheckStatus?): Boolean =
        status?.outcome?.quickRetry == true && status.failures in 1..QUICK_RETRIES

    /**
     * Profiles to check now: enabled, with a usable pin, and due. A profile is due when it was never checked, when its interval
     * (minus [FLEX_MILLIS]) has passed since the last attempt, when a transport failure is waiting for its quick retry
     * ([RETRY_DELAYS_MILLIS]), or when the last attempt is in the future because the clock moved back. Failures that need the
     * user (certificate, credentials, pool role) and server-side errors always wait the full interval. Least recently
     * attempted first, bounded.
     */
    fun due(profiles: List<Profile>, settings: Map<String, AlertNotifySettings>, statuses: Map<String, CheckStatus>, now: Long): List<Profile> =
        profiles.filter { profile ->
            val setting = settings[profile.id] ?: return@filter false
            if (!setting.enabled || !profile.certificateSha256.matches(Regex("[0-9a-f]{64}"))) return@filter false
            val status = statuses[profile.id] ?: return@filter true
            val normal = setting.intervalMinutes * 60_000L - FLEX_MILLIS
            val wait = if (retryPending(status)) minOf(RETRY_DELAYS_MILLIS[status.failures - 1], normal) else normal
            status.attemptAt > now || now - status.attemptAt >= wait
        }.sortedBy { statuses[it.id]?.attemptAt ?: Long.MIN_VALUE }.take(MAX_PROFILES_PER_RUN)

    /**
     * The server time from which a check looks (before the overlap), or null when that needs the server's clock and it is not
     * known. Everything here is server time:
     * - a watermark of this pool: its newest message time, never later than the server's own clock (an earlier release could
     *   save the phone's clock when the pool had no messages, which would hide alerts if the phone ran ahead);
     * - a watermark of another pool: the server's clock now, minus the overlap;
     * - no watermark (first check): the moment the switch was turned on, moved from phone time to server time by the skew
     *   measured in this session, never later than the server's clock now.
     */
    fun start(settings: AlertNotifySettings, previous: AlertWatermark?, authority: String, clock: ServerClock?): Long? {
        val usable = previous?.takeIf { it.authority == authority }
        if (usable != null) return clock?.let { minOf(usable.newest, it.serverMillis) } ?: usable.newest
        if (clock == null) return null
        // Another pool: about the time of this check, as far back as the overlap. First check: the moment the switch was turned on.
        if (previous != null) return clock.serverMillis - OVERLAP_MILLIS
        return minOf(clock.fromPhone(settings.enabledAt ?: clock.phoneMillis), clock.serverMillis)
    }

    /** `message.get_since` argument: [start] minus the overlap. */
    fun since(settings: AlertNotifySettings, previous: AlertWatermark?, authority: String, clock: ServerClock?): Long? =
        start(settings, previous, authority, clock)?.let { (it - OVERLAP_MILLIS).coerceAtLeast(1) }

    /**
     * Picks what to notify and the next watermark. A message is a candidate when it has a server timestamp, is newer than the
     * floor (watermark minus overlap, or the enable time on the first check), and its reference was not handled before.
     * Every fetched message inside the window is recorded as handled, notified or not, so lowering the threshold later never
     * replays old alerts and a message from just before the switch was turned on is not found again by the next overlap.
     * The watermark never moves backwards, except to the server's own clock when the saved one was ahead of it ([BackgroundDecision.rewound]).
     * If the profile now reaches a different pool, that pool starts fresh from about the time of the check.
     * Alerts at or before [seen] (the in-app Alerts value) are handled but not notified: the user has seen them.
     * Throws [ServerTimeUnavailable] when the start cannot be known without the server's clock.
     */
    fun decide(profile: Profile, settings: AlertNotifySettings, previous: AlertWatermark?, fetch: BackgroundAlertFetch, seen: Long? = null): BackgroundDecision {
        val usable = previous?.takeIf { it.authority == fetch.authority }
        val start = start(settings, previous, fetch.authority, fetch.serverClock) ?: throw ServerTimeUnavailable()
        val floor = if (usable != null) start - OVERLAP_MILLIS else start
        val handledBefore = usable?.recent.orEmpty()
        val placed = fetch.messages.filter { it.timestamp != null }
        val fresh = placed.filter { message ->
            val at = message.timestamp!!
            (if (usable != null) at > floor else at >= floor) && message.ref !in handledBefore
        }
        val notify = fresh.filter { settings.threshold.includes(it.severity) && (seen == null || it.timestamp!! > seen) }.sortedWith(AlertMapper.NEWEST_FIRST)
        val newest = maxOf(start, placed.maxOfOrNull { it.timestamp!! } ?: start)
        val window = newest - OVERLAP_MILLIS
        val recent = (handledBefore + placed.associate { it.ref to it.timestamp!! })
            .filter { it.value > window }.entries.sortedByDescending { it.value }.take(MAX_RECENT).associate { it.key to it.value }
        return BackgroundDecision(notify, AlertWatermark(profile.origin(), fetch.authority, newest, recent), fresh.size,
            rewound = usable != null && newest < usable.newest)
    }

    /** Messages whose object names are worth one read each: the ones about to be notified individually. */
    fun wanted(settings: AlertNotifySettings): (AlertMessage) -> Boolean = { settings.threshold.includes(it.severity) }

    fun attentionMarker(kind: AttentionKind, profile: Profile): String = "${kind.name}:${profile.origin()}"
}

/** Text only. Never contains the endpoint, username, password, session or the alert body. */
data class AlertNotificationText(val title: String, val text: String, val severity: AlertSeverity, val ref: String)
data class AlertSummaryText(val title: String, val lines: List<String>, val summary: String, val severity: AlertSeverity, val count: Int)
/**
 * What one run posts for one profile: up to [BackgroundAlerts.MAX_INDIVIDUAL] individual notifications and the [overflow] that
 * did not fit. The group summary is built by the notifier from what is actually posted afterwards (see [AlertNotificationContent.summary]);
 * [summary] is only this batch's own view of it.
 */
data class AlertNotificationBatch(val profileId: String, val items: List<AlertNotificationText>, val overflow: Int = 0, val pool: String = "") {
    val summary: AlertSummaryText? get() = AlertNotificationContent.summary(pool, items, overflow)
}
data class AttentionText(val title: String, val text: String)

object AlertNotificationContent {
    private fun clean(value: String, max: Int = 80) = value.filter { it >= ' ' }.trim().take(max)

    fun poolLabel(profile: Profile, poolName: String?) = clean(poolName.orEmpty()).ifBlank { clean(profile.label).ifBlank { "XCP-ng pool" } }

    fun objectLabel(message: AlertMessage, names: Map<String, String>, poolName: String): String {
        val kind = message.objectKind
        val name = when (kind) {
            AlertObjectKind.POOL -> poolName
            else -> names[BackgroundAlerts.objectKey(message)]
        }?.let { clean(it) }?.takeIf { it.isNotBlank() }
        return if (name != null) "${kind.label} $name" else kind.label
    }

    fun item(profile: Profile, fetch: BackgroundAlertFetch, message: AlertMessage): AlertNotificationText {
        val pool = poolLabel(profile, fetch.poolName)
        return AlertNotificationText("${message.severity.label}: ${AlertNames.humanize(message.name)}",
            "$pool · ${objectLabel(message, fetch.objectNames, pool)}", message.severity, message.ref)
    }

    /** Up to [BackgroundAlerts.MAX_INDIVIDUAL] notifications; the rest is counted as overflow in the group summary. */
    fun batch(profile: Profile, fetch: BackgroundAlertFetch, notify: List<AlertMessage>): AlertNotificationBatch? {
        if (notify.isEmpty()) return null
        val ordered = notify.sortedWith(compareBy<AlertMessage> { it.severity.priority }.thenComparing(AlertMapper.NEWEST_FIRST))
        val items = ordered.take(BackgroundAlerts.MAX_INDIVIDUAL).map { item(profile, fetch, it) }
        return AlertNotificationBatch(profile.id, items, ordered.size - items.size, poolLabel(profile, fetch.poolName))
    }

    /**
     * The group summary for what is posted: [children] are the individual notifications actually in the shade (old and new),
     * [overflow] the alerts counted but not shown individually. Null when there is only one alert, which needs no summary.
     */
    fun summary(pool: String, children: List<AlertNotificationText>, overflow: Int): AlertSummaryText? {
        val ordered = children.sortedBy { it.severity.priority }
        val count = ordered.size + overflow
        if (count < 2) return null
        return AlertSummaryText("$count new alerts · $pool",
            ordered.map { "${it.title} · ${it.text.substringAfter(" · ")}" } + listOfNotNull(overflow.takeIf { it > 0 }?.let { "+$it more in Meteor" }),
            pool, ordered.firstOrNull()?.severity ?: AlertSeverity.WARNING, count)
    }

    /** Result of adding a batch to what is already posted: [kept] stay (most urgent first), [dropped] are cancelled and counted as overflow. */
    data class Merged(val kept: List<AlertNotificationText>, val dropped: List<AlertNotificationText>)

    /**
     * The shade holds at most [max] individual notifications per profile. A new notification replaces an old one with the same
     * reference; when there are more than [max], the least urgent and oldest are dropped. [posted] is newest-first.
     */
    fun merge(posted: List<AlertNotificationText>, incoming: List<AlertNotificationText>, max: Int = BackgroundAlerts.MAX_POSTED): Merged {
        val all = incoming + posted.filter { old -> incoming.none { it.ref == old.ref } }
        val ranked = all.withIndex().sortedWith(compareBy({ it.value.severity.priority }, { it.index })).map { it.value }
        return Merged(ranked.take(max), ranked.drop(max))
    }

    fun attention(kind: AttentionKind, profile: Profile): AttentionText {
        val name = clean(profile.label).ifBlank { "A saved connection" }
        return when (kind) {
            AttentionKind.CERTIFICATE_CHANGED -> AttentionText("$name needs attention",
                "The server certificate no longer matches the trusted fingerprint. Background alert checks for this connection are skipped until you review it in Meteor.")
            AttentionKind.AUTHENTICATION_FAILED -> AttentionText("$name needs attention",
                "Sign-in failed during a background alert check. Check this connection's username and password in Meteor.")
            AttentionKind.POOL_MEMBER -> AttentionText("$name needs attention",
                "This address is now a pool member, not the pool master. Open Meteor and connect to review the pool master.")
            AttentionKind.CHECKS_FAILING -> AttentionText("$name checks are failing",
                "Background alert checks for this connection have failed several times in a row, so new alerts may be missing. Open Meteor to check the connection.")
        }
    }
}

/** Plain-words status lines for Settings. [zone] and [locale] are injected so the text is testable. */
object BackgroundCheckText {
    const val NOT_ALLOWED = "Notifications are turned off for Meteor in Android settings. Meteor will not check in the background until they are allowed."

    private fun clock(at: Long, now: Long, zone: ZoneId, locale: Locale): String {
        val instant = Instant.ofEpochMilli(at)
        val sameDay = instant.atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (if (sameDay) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
            .withLocale(locale).withZone(zone).format(instant)
    }

    fun reason(outcome: CheckOutcome): String = when (outcome) {
        CheckOutcome.CERTIFICATE_CHANGED -> "the server's certificate no longer matches the trusted fingerprint"
        CheckOutcome.AUTHENTICATION_FAILED -> "the server refused the saved username or password"
        CheckOutcome.POOL_MEMBER -> "this address is now a pool member, not the pool master"
        CheckOutcome.FAILED -> "could not reach the server"
        CheckOutcome.TIMED_OUT -> "the server took too long to answer"
        CheckOutcome.SERVER_ERROR -> "the server answered with an error"
        CheckOutcome.SERVER_TIME_UNAVAILABLE -> "the server did not report its time"
        CheckOutcome.NOTIFY_FAILED -> "Android did not accept the notification"
        else -> "no reason recorded"
    }

    /** The lines under one opted-in profile: the notification block when Android stops the checks, and its last check. */
    fun lines(status: CheckStatus?, notificationsAllowed: Boolean, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<String> {
        val lines = mutableListOf<String>()
        if (!notificationsAllowed) lines += NOT_ALLOWED
        fun at(value: Long) = clock(value, now, zone, locale)
        val outcome = status?.outcome
        lines += when {
            status == null -> "Not checked yet. Android runs the first check when the network is up."
            outcome == null -> "Last checked ${at(status.attemptAt)}."
            outcome == CheckOutcome.NOTHING_NEW -> "Last checked ${at(status.attemptAt)}: nothing new."
            outcome == CheckOutcome.NOTIFIED -> "Last checked ${at(status.attemptAt)}: new alerts were shown as notifications."
            else -> "Last check failed at ${at(status.attemptAt)}: ${reason(outcome)}." +
                (status.lastSuccessAt?.let { " Last successful check ${at(it)}." } ?: " No check has worked yet.") +
                (if (status.failures >= 2) " Failed ${status.failures} times in a row." else "")
        }
        return lines
    }

    /** One line about the last whole run when it could not check anything, or null when it was fine. */
    fun runLine(note: RunNote?, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        note ?: return null
        val at = clock(note.at, now, zone, locale)
        return when (note.problem) {
            RunProblem.OK -> null
            RunProblem.VAULT_UNAVAILABLE -> "The last background run ($at) could not open the saved connections, so nothing was checked."
            RunProblem.NOTIFICATIONS_BLOCKED -> "The last background run ($at) checked nothing because notifications are turned off."
            RunProblem.STOPPED -> "The last background run ($at) stopped on an unexpected error."
        }
    }
}

/**
 * A notification tap is applied once. Every notification Meteor posts carries the time it was posted (the stamp); the activity
 * remembers the stamp it last applied across recreation. A new launch applies its intent; a recreated activity applies it only
 * when the stamp differs from the one already applied (a new tap), so a rotation never replays a spent tap and a tap that
 * arrives with a restored state is not lost. A delivered new intent is always a new tap.
 */
object NotificationTaps {
    fun applyOnCreate(stamp: Long?, restored: Boolean, lastApplied: Long?): Boolean = !restored || stamp != null && stamp != lastApplied
}
// --- end B3 ---
