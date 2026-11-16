package com.meteor.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meteor.app.data.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

// --- B3 background/summary ---

/**
 * The periodic background alert check. A thin adapter: [BackgroundAlertRunner] holds the logic. It reports success, except
 * when a transport failure or timeout asks for a quick retry: then WorkManager runs it again after a short backoff, at most
 * [BackgroundAlerts.QUICK_RETRIES] times in a row (the runner's own gate also keeps a profile from being contacted early).
 * Credential, certificate and server errors never ask for a retry; the next period is theirs.
 * Not expedited, never a foreground service.
 */
class BackgroundAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = BackgroundAlertDataStore(context)
        val report = try {
            BackgroundAlertRunner(ProfileVault(context), XapiGateway(), store, SystemAlertNotifier(context), AlertSeenDataStore(context)).run()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            runCatching { store.setRunNote(RunNote(System.currentTimeMillis(), RunProblem.STOPPED)) }
            null
        }
        // Nothing opted in any more (for example every opted-in profile was forgotten): stop the schedule.
        if (report != null && report.periodMinutes == null && !report.vaultUnavailable) BackgroundAlertScheduler.cancel(context)
        return if (report?.retryWanted == true && runAttemptCount < BackgroundAlerts.QUICK_RETRIES) Result.retry() else Result.success()
    }
}

object BackgroundAlertScheduler {
    const val WORK_NAME = "meteor-background-alerts"

    /**
     * Network required. There is deliberately no battery constraint: a check is one short read, and "battery not low" would
     * hold back a critical alert exactly when the phone is least attended. Android's own Doze and App Standby limits still
     * apply. WorkManager's minimum period is 15 minutes.
     */
    fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun request(periodMinutes: Int): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<BackgroundAlertWorker>(periodMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.LINEAR, BackgroundAlerts.RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(WORK_NAME).build()

    /** One unique periodic job at the shortest opted-in interval; cancelled when nothing is opted in. */
    fun sync(context: Context, settings: Collection<AlertNotifySettings>) {
        val period = BackgroundAlerts.periodMinutes(settings)
        val work = WorkManager.getInstance(context.applicationContext)
        if (period == null) work.cancelUniqueWork(WORK_NAME)
        else work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request(period))
    }

    fun cancel(context: Context) { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME) }
}

/** Notification permission and system-level state, for Settings and the notifier. */
object NotificationAccess {
    /** Android 13+ needs the runtime permission; Android 12 grants posting by default. */
    fun needsPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun allowed(context: Context): Boolean = !needsPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Severity channels (created on first use) that the user turned off in system settings. */
    fun blockedChannels(context: Context): List<String> {
        val manager = NotificationManagerCompat.from(context)
        return AlertChannels.all.mapNotNull { channel ->
            manager.getNotificationChannel(channel.id)?.takeIf { it.importance == NotificationManager.IMPORTANCE_NONE }?.let { channel.label }
        }
    }

    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal data class AlertChannel(val id: String, val label: String, val importance: Int, val description: String)

internal object AlertChannels {
    val critical = AlertChannel("meteor.alerts.critical", "Critical alerts", NotificationManager.IMPORTANCE_HIGH, "XCP-ng alerts with priority 1.")
    val high = AlertChannel("meteor.alerts.high", "High alerts", NotificationManager.IMPORTANCE_DEFAULT, "XCP-ng alerts with priority 2.")
    val warning = AlertChannel("meteor.alerts.warning", "Warning alerts", NotificationManager.IMPORTANCE_LOW, "XCP-ng alerts with priority 3.")
    val attention = AlertChannel("meteor.alerts.attention", "Connection attention", NotificationManager.IMPORTANCE_DEFAULT,
        "A saved connection needs review before background alert checks can continue.")
    val all = listOf(critical, high, warning, attention)

    fun of(severity: AlertSeverity): AlertChannel = when (severity) {
        AlertSeverity.CRITICAL -> critical
        AlertSeverity.HIGH -> high
        else -> warning
    }

    /** Idempotent; called before the first post and when the user turns a switch on. */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        all.forEach { channel ->
            manager.createNotificationChannel(NotificationChannel(channel.id, channel.label, channel.importance).apply { description = channel.description })
        }
    }
}

/** A tap opens Meteor on the Alerts destination for that profile; switching profile still needs the user's choice. */
object NotificationIntents {
    const val ACTION_OPEN_ALERTS = "com.meteor.app.action.OPEN_ALERTS"
    const val EXTRA_PROFILE = "com.meteor.app.extra.PROFILE"
    /** The time the notification was posted. The activity uses it to apply each tap once (see [NotificationTaps]). */
    const val EXTRA_STAMP = "com.meteor.app.extra.STAMP"
    private val id = Regex("[A-Za-z0-9._:-]{1,120}")

    fun open(context: Context, profileId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_ALERTS).putExtra(EXTRA_PROFILE, profileId)
            .putExtra(EXTRA_STAMP, System.currentTimeMillis()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, "open|$profileId".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The post time of the notification an "open alerts" intent came from, or null for an intent without one (posted by an earlier release). */
    fun stamp(intent: Intent?): Long? = intent?.takeIf { it.action == ACTION_OPEN_ALERTS }?.getLongExtra(EXTRA_STAMP, 0L)?.takeIf { it > 0 }

    /** The profile an "open alerts" intent targets, or null for any other or malformed intent. */
    fun profileId(intent: Intent?): String? = intent?.takeIf { it.action == ACTION_OPEN_ALERTS }
        ?.getStringExtra(EXTRA_PROFILE)?.takeIf { it.matches(id) }
}

/**
 * Posts the content built by [AlertNotificationContent]. Lock screens show only a generic public version.
 *
 * The group summary always describes what is actually in the shade: after posting, the notifier reads its own active
 * notifications of that profile (title, text, severity and reference are kept in each one's extras), merges the new ones in
 * (replacing a notification for the same alert, keeping at most [BackgroundAlerts.MAX_POSTED]), and builds the summary from
 * that. Alerts that did not get a notification of their own are carried as an overflow count in the summary's extras.
 */
class SystemAlertNotifier(private val context: Context) : AlertNotifier, AlertNotificationCleaner {
    override val canPost: Boolean get() = NotificationAccess.allowed(context)

    private fun group(profileId: String) = "meteor.alerts.$profileId"
    private fun childId(profileId: String, ref: String) = "alert|$profileId|$ref".hashCode()
    private fun summaryId(profileId: String) = "summary|$profileId".hashCode()

    private companion object {
        const val X_REF = "com.meteor.app.extra.ALERT_REF"
        const val X_SEVERITY = "com.meteor.app.extra.ALERT_SEVERITY"
        const val X_OVERFLOW = "com.meteor.app.extra.ALERT_OVERFLOW"
        const val X_POOL = "com.meteor.app.extra.ALERT_POOL"
    }

    private fun publicVersion(channel: AlertChannel) = NotificationCompat.Builder(context, channel.id)
        .setSmallIcon(R.drawable.ic_stat_meteor).setContentTitle("Meteor").setContentText("New XCP-ng alert").build()

    private fun notify(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** This profile's alert notifications as the system reports them: (id, text) of each child, and the summary's overflow and pool. */
    private class Shade(val children: List<Pair<Int, AlertNotificationText>>, val overflow: Int, val pool: String)

    private fun shade(profileId: String): Shade {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = runCatching { manager?.activeNotifications?.toList() }.getOrNull().orEmpty().filter { it.notification.group == group(profileId) }
        val children = active.filter { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }.mapNotNull { sbn ->
            val extras = sbn.notification.extras
            val ref = extras.getString(X_REF) ?: return@mapNotNull null
            val severity = AlertSeverity.entries.firstOrNull { it.priority == extras.getInt(X_SEVERITY, -1) } ?: return@mapNotNull null
            sbn.id to AlertNotificationText(extras.getString(android.app.Notification.EXTRA_TITLE).orEmpty(),
                extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty(), severity, ref)
        }
        val summary = active.firstOrNull { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 }?.notification?.extras
        return Shade(children, summary?.getInt(X_OVERFLOW, 0) ?: 0, summary?.getString(X_POOL).orEmpty())
    }

    private fun postChild(batchProfile: String, item: AlertNotificationText, tap: PendingIntent, summarised: Boolean) {
        val channel = AlertChannels.of(item.severity)
        notify(childId(batchProfile, item.ref), NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_meteor).setContentTitle(item.title).setContentText(item.text)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(channel)).setGroup(group(batchProfile))
            .setGroupAlertBehavior(if (summarised) NotificationCompat.GROUP_ALERT_SUMMARY else NotificationCompat.GROUP_ALERT_ALL)
            .addExtras(android.os.Bundle().apply { putString(X_REF, item.ref); putInt(X_SEVERITY, item.severity.priority) })
            .setContentIntent(tap).setAutoCancel(true).build())
    }

    /** Posts the group summary for [children] and [overflow], or removes it when one alert or none is left. */
    private fun postSummary(profileId: String, pool: String, children: List<AlertNotificationText>, overflow: Int, tap: PendingIntent) {
        val summary = AlertNotificationContent.summary(pool, children, overflow)
        if (summary == null) { NotificationManagerCompat.from(context).cancel(summaryId(profileId)); return }
        val channel = AlertChannels.of(summary.severity)
        val style = NotificationCompat.InboxStyle().setSummaryText(summary.summary)
        summary.lines.forEach(style::addLine)
        notify(summaryId(profileId), NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_meteor).setContentTitle(summary.title).setContentText(summary.lines.firstOrNull().orEmpty())
            .setStyle(style).setNumber(summary.count).setGroup(group(profileId)).setGroupSummary(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(channel))
            .addExtras(android.os.Bundle().apply { putInt(X_OVERFLOW, overflow); putString(X_POOL, pool) })
            .setContentIntent(tap).setAutoCancel(true).build())
    }

    override fun post(batch: AlertNotificationBatch) {
        AlertChannels.ensure(context)
        val tap = NotificationIntents.open(context, batch.profileId)
        val before = shade(batch.profileId)
        val merged = AlertNotificationContent.merge(before.children.map { it.second }, batch.items)
        val overflow = before.overflow + batch.overflow + merged.dropped.size
        // With two or more alerts the children only alert through the summary.
        val summarised = merged.kept.size + overflow >= 2
        batch.items.filter { item -> merged.kept.any { it.ref == item.ref } }.forEach { postChild(batch.profileId, it, tap, summarised) }
        val manager = NotificationManagerCompat.from(context)
        merged.dropped.forEach { dropped -> before.children.firstOrNull { it.second.ref == dropped.ref }?.let { manager.cancel(it.first) } }
        postSummary(batch.profileId, batch.pool.ifBlank { before.pool }, merged.kept, overflow, tap)
    }

    override fun clearProfile(profileId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.activeNotifications.filter { it.notification.group == group(profileId) }.forEach { manager.cancel(it.id) } }
    }

    override fun clearAlerts(profileId: String, refs: Collection<String>) {
        if (refs.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        val before = shade(profileId)
        val gone = before.children.filter { it.second.ref in refs }
        if (gone.isEmpty()) return
        gone.forEach { manager.cancel(it.first) }
        postSummary(profileId, before.pool, before.children.map { it.second }.filter { it.ref !in refs }, before.overflow, NotificationIntents.open(context, profileId))
    }

    override fun attention(profileId: String, text: AttentionText) {
        AlertChannels.ensure(context)
        val channel = AlertChannels.attention
        notify("attention|$profileId".hashCode(), NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_meteor).setContentTitle(text.title).setContentText(text.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.text)).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(channel))
            .setContentIntent(NotificationIntents.open(context, profileId)).setAutoCancel(true).build())
    }

    override fun clearAttention(profileId: String) { NotificationManagerCompat.from(context).cancel("attention|$profileId".hashCode()) }
}
// --- end B3 ---
