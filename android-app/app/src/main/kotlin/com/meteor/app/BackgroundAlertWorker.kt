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
 * The periodic background alert check. A thin adapter: [BackgroundAlertRunner] holds the logic. It always reports
 * success, so WorkManager never retries a run (and never repeats a login); the next period is the retry.
 * Not expedited, never a foreground service.
 */
class BackgroundAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = BackgroundAlertDataStore(context)
        val report = try {
            BackgroundAlertRunner(ProfileVault(context), XapiGateway(), store, SystemAlertNotifier(context)).run()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        // Nothing opted in any more (for example every opted-in profile was forgotten): stop the schedule.
        if (report != null && report.periodMinutes == null && !report.vaultUnavailable) BackgroundAlertScheduler.cancel(context)
        return Result.success()
    }
}

object BackgroundAlertScheduler {
    const val WORK_NAME = "meteor-background-alerts"

    /** Network required, not when the battery is low. WorkManager's minimum period is 15 minutes. */
    fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun request(periodMinutes: Int): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<BackgroundAlertWorker>(periodMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints()).addTag(WORK_NAME).build()

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
    private val id = Regex("[A-Za-z0-9._:-]{1,120}")

    fun open(context: Context, profileId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_ALERTS).putExtra(EXTRA_PROFILE, profileId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, "open|$profileId".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The profile an "open alerts" intent targets, or null for any other or malformed intent. */
    fun profileId(intent: Intent?): String? = intent?.takeIf { it.action == ACTION_OPEN_ALERTS }
        ?.getStringExtra(EXTRA_PROFILE)?.takeIf { it.matches(id) }
}

/** Posts the content built by [AlertNotificationContent]. Lock screens show only a generic public version. */
class SystemAlertNotifier(private val context: Context) : AlertNotifier {
    override val canPost: Boolean get() = NotificationAccess.allowed(context)

    private fun group(profileId: String) = "meteor.alerts.$profileId"

    private fun publicVersion(channel: AlertChannel) = NotificationCompat.Builder(context, channel.id)
        .setSmallIcon(R.drawable.ic_stat_meteor).setContentTitle("Meteor").setContentText("New XCP-ng alert").build()

    private fun notify(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    override fun post(batch: AlertNotificationBatch) {
        AlertChannels.ensure(context)
        val tap = NotificationIntents.open(context, batch.profileId)
        batch.items.forEach { item ->
            val channel = AlertChannels.of(item.severity)
            notify("alert|${batch.profileId}|${item.ref}".hashCode(), NotificationCompat.Builder(context, channel.id)
                .setSmallIcon(R.drawable.ic_stat_meteor).setContentTitle(item.title).setContentText(item.text)
                .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(channel)).setGroup(group(batch.profileId))
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY.takeIf { batch.summary != null } ?: NotificationCompat.GROUP_ALERT_ALL)
                .setContentIntent(tap).setAutoCancel(true).build())
        }
        batch.summary?.let { summary ->
            val channel = AlertChannels.of(summary.severity)
            val style = NotificationCompat.InboxStyle().setSummaryText(summary.summary)
            summary.lines.forEach(style::addLine)
            notify("summary|${batch.profileId}".hashCode(), NotificationCompat.Builder(context, channel.id)
                .setSmallIcon(R.drawable.ic_stat_meteor).setContentTitle(summary.title).setContentText(summary.lines.firstOrNull().orEmpty())
                .setStyle(style).setNumber(summary.count).setGroup(group(batch.profileId)).setGroupSummary(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(channel)).setContentIntent(tap).setAutoCancel(true).build())
        }
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
