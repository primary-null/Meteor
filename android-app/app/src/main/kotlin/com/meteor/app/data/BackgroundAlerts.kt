package com.meteor.app.data

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
 * was last turned on; the first check only notifies alerts from then on, so enabling never floods old alerts.
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
enum class AttentionKind { CERTIFICATE_CHANGED, AUTHENTICATION_FAILED, POOL_MEMBER }

/** One bounded background read: pool identity, alerts since a server timestamp, and names for a few objects. */
data class BackgroundAlertFetch(
    val authority: String, val poolName: String, val messages: List<AlertMessage>,
    /** `cls:obj_uuid` -> name_label, only for the few objects that will be notified. */
    val objectNames: Map<String, String> = emptyMap(),
) {
    override fun toString() = "BackgroundAlertFetch(messages=${messages.size})"
}

data class BackgroundDecision(val notify: List<AlertMessage>, val watermark: AlertWatermark, val handled: Int)

object BackgroundAlerts {
    /** Same overlap as the A4 inbox: re-read this much before the watermark so out-of-order timestamps are not lost. */
    const val OVERLAP_MILLIS = AlertMerge.OVERLAP_MILLIS
    const val MAX_RECENT = 200
    /** A due profile may be checked this much early, so a 30-minute setting fits a 15-minute WorkManager period. */
    const val FLEX_MILLIS = 5 * 60_000L
    const val MAX_PROFILES_PER_RUN = 10
    /** Individual notifications per profile per run; anything beyond is counted in the group summary. */
    const val MAX_INDIVIDUAL = 5
    /** Distinct objects whose names are read for one run. */
    const val MAX_NAMED_OBJECTS = 8

    fun objectKey(message: AlertMessage) = "${message.objectClass.lowercase()}:${message.objectUuid}"

    /** WorkManager period: the shortest interval among enabled profiles, or null when nothing is enabled. */
    fun periodMinutes(settings: Collection<AlertNotifySettings>): Int? =
        settings.filter { it.enabled }.minOfOrNull { it.intervalMinutes }

    /**
     * Profiles to check now: enabled, with a usable pin, and due (never checked, interval elapsed minus [FLEX_MILLIS],
     * or a last-checked time in the future because the clock moved back). Least recently checked first, bounded.
     */
    fun due(profiles: List<Profile>, settings: Map<String, AlertNotifySettings>, lastChecked: Map<String, Long>, now: Long): List<Profile> =
        profiles.filter { profile ->
            val setting = settings[profile.id] ?: return@filter false
            if (!setting.enabled || !profile.certificateSha256.matches(Regex("[0-9a-f]{64}"))) return@filter false
            val last = lastChecked[profile.id] ?: return@filter true
            last > now || now - last >= setting.intervalMinutes * 60_000L - FLEX_MILLIS
        }.sortedBy { lastChecked[it.id] ?: Long.MIN_VALUE }.take(MAX_PROFILES_PER_RUN)

    /**
     * `message.get_since` argument: the watermark minus the overlap, or the moment notifications were enabled. The pool
     * is only known after the read, so [decide] checks that the watermark belongs to it.
     */
    fun since(profile: Profile, settings: AlertNotifySettings, watermark: AlertWatermark?, now: Long): Long {
        val base = watermark?.newest ?: settings.enabledAt ?: now
        return (base - OVERLAP_MILLIS).coerceAtLeast(1)
    }

    /**
     * Picks what to notify and the next watermark. A message is a candidate when it has a server timestamp, is newer
     * than the floor (watermark minus overlap, or the enable time on the first check), and its reference was not
     * handled before. Every fetched message is recorded as handled, notified or not, so lowering the threshold later
     * never replays old alerts. The watermark never moves backwards. If the profile now reaches a different pool, that
     * pool starts fresh from about [now] rather than replaying its history.
     */
    fun decide(profile: Profile, settings: AlertNotifySettings, previous: AlertWatermark?, fetch: BackgroundAlertFetch, now: Long): BackgroundDecision {
        val usable = previous?.takeIf { it.authority == fetch.authority }
        val floor = usable?.let { it.newest - OVERLAP_MILLIS }
            ?: if (previous != null) now - OVERLAP_MILLIS else settings.enabledAt ?: now
        val strictlyNewer = usable != null
        val handledBefore = usable?.recent.orEmpty()
        val placed = fetch.messages.filter { it.timestamp != null }
        val fresh = placed.filter { message ->
            val at = message.timestamp!!
            (if (strictlyNewer) at > floor else at >= floor) && message.ref !in handledBefore
        }
        val notify = fresh.filter { settings.threshold.includes(it.severity) }.sortedWith(AlertMapper.NEWEST_FIRST)
        val newest = listOfNotNull(usable?.newest, placed.mapNotNull { it.timestamp }.maxOrNull()).maxOrNull() ?: floor
        val window = newest - OVERLAP_MILLIS
        val recent = (handledBefore + fresh.associate { it.ref to it.timestamp!! })
            .filter { it.value > window }.entries.sortedByDescending { it.value }.take(MAX_RECENT).associate { it.key to it.value }
        return BackgroundDecision(notify, AlertWatermark(profile.origin(), fetch.authority, newest, recent), fresh.size)
    }

    /** Messages whose object names are worth one read each: the ones about to be notified individually. */
    fun wanted(settings: AlertNotifySettings): (AlertMessage) -> Boolean = { settings.threshold.includes(it.severity) }

    fun attentionMarker(kind: AttentionKind, profile: Profile): String = "${kind.name}:${profile.origin()}"
}

/** Text only. Never contains the endpoint, username, password, session or the alert body. */
data class AlertNotificationText(val title: String, val text: String, val severity: AlertSeverity, val ref: String)
data class AlertSummaryText(val title: String, val lines: List<String>, val summary: String, val severity: AlertSeverity, val count: Int)
data class AlertNotificationBatch(val profileId: String, val items: List<AlertNotificationText>, val summary: AlertSummaryText?)
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

    /** Up to [BackgroundAlerts.MAX_INDIVIDUAL] notifications plus a group summary when there is more than one alert. */
    fun batch(profile: Profile, fetch: BackgroundAlertFetch, notify: List<AlertMessage>): AlertNotificationBatch? {
        if (notify.isEmpty()) return null
        val ordered = notify.sortedWith(compareBy<AlertMessage> { it.severity.priority }.thenComparing(AlertMapper.NEWEST_FIRST))
        val items = ordered.take(BackgroundAlerts.MAX_INDIVIDUAL).map { item(profile, fetch, it) }
        val pool = poolLabel(profile, fetch.poolName)
        val summary = if (ordered.size < 2) null else AlertSummaryText(
            "${ordered.size} new alerts · $pool",
            items.map { "${it.title} · ${it.text.substringAfter(" · ")}" } +
                listOfNotNull((ordered.size - items.size).takeIf { it > 0 }?.let { "+$it more in Meteor" }),
            pool, ordered.first().severity, ordered.size)
        return AlertNotificationBatch(profile.id, items, summary)
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
        }
    }
}
// --- end B3 ---
