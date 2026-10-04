package com.parkingblues.car.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.parkingblues.shared.model.ParkedSpot
import java.util.concurrent.TimeUnit

/**
 * "I parked here" notifications: an immediate confirmation, and (blue zone
 * only, since that's the only zone type with a hard legal deadline in this
 * data) a reminder a few minutes before the parking-disc limit is up. Both
 * notifications' tap targets open MainActivity in :app -- resolved by
 * package+class name string, not a typed reference, since :car can't
 * depend on :app (:app depends on :car, not the other way around). That
 * also means this degrades gracefully, not usefully, on :automotive: same
 * applicationId, but no MainActivity class in that APK at all (it's a
 * genuinely separate device from the phone, see MapSearchScreen's "Parked
 * here" action doc), so the PendingIntent there just doesn't resolve to
 * anything when tapped. The notification itself still posts to the car's
 * own notification shade either way, which isn't nothing, but it isn't
 * the "walk back to your car" flow this was actually built for.
 */
private const val CHANNEL_ID = "parking_reminders"
private const val NOTIFICATION_ID_SAVED = 1001
private const val NOTIFICATION_ID_EXPIRY = 1002
private const val EXPIRY_WORK_NAME = "parking_expiry_reminder"

/** How long before the legal limit actually lapses to warn the driver --
 *  enough time to walk back and move the car, not so early it's ignorable. */
private const val REMINDER_LEAD_MINUTES = 10L

const val EXTRA_SHOW_PARKED = "com.parkingblues.app.EXTRA_SHOW_PARKED"

private fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            "Parking reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Where you parked, and when your time limit is up" }
    )
}

private fun parkedViewIntent(context: Context): PendingIntent {
    val intent = Intent().apply {
        setClassName(context.packageName, "${context.packageName}.MainActivity")
        putExtra(EXTRA_SHOW_PARKED, true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

fun notifyParkingSaved(context: Context, spot: ParkedSpot) {
    ensureNotificationChannel(context)
    val text = spot.addressLabel ?: "Tap to see where you parked"
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(com.parkingblues.car.R.drawable.ic_destination)
        .setContentTitle("Parking spot saved")
        .setContentText(text)
        .setContentIntent(parkedViewIntent(context))
        .setAutoCancel(true)
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SAVED, notification) }
}

private fun notifyParkingExpiring(context: Context, spot: ParkedSpot) {
    ensureNotificationChannel(context)
    val where = spot.addressLabel?.let { " at $it" } ?: ""
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(com.parkingblues.car.R.drawable.ic_destination)
        .setContentTitle("Parking expiring soon")
        .setContentText("Your parking disc limit$where is up in about $REMINDER_LEAD_MINUTES min")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(parkedViewIntent(context))
        .setAutoCancel(true)
        .build()
    runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_EXPIRY, notification) }
}

/** No-op if the spot has no expiresAtEpochMillis (white zone / unknown). */
fun scheduleExpiryReminder(context: Context, spot: ParkedSpot) {
    val expiresAt = spot.expiresAtEpochMillis ?: return
    val fireAt = expiresAt - TimeUnit.MINUTES.toMillis(REMINDER_LEAD_MINUTES)
    val delayMs = (fireAt - System.currentTimeMillis()).coerceAtLeast(0)
    val request = OneTimeWorkRequestBuilder<ParkingExpiryWorker>()
        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        EXPIRY_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        request,
    )
}

fun cancelExpiryReminder(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(EXPIRY_WORK_NAME)
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_EXPIRY)
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_SAVED)
}

class ParkingExpiryWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val spot = loadParkedSpot(applicationContext) ?: return Result.success()
        notifyParkingExpiring(applicationContext, spot)
        return Result.success()
    }
}
