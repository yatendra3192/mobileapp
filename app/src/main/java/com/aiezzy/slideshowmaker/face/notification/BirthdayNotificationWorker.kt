package com.aiezzy.slideshowmaker.face.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.aiezzy.slideshowmaker.MainActivity
import com.aiezzy.slideshowmaker.R
import com.aiezzy.slideshowmaker.data.face.FaceDatabase
import com.aiezzy.slideshowmaker.data.face.entities.PersonEntity
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Worker that checks for birthdays daily and sends notifications.
 *
 * Features:
 * - Runs daily at a scheduled time
 * - Checks all persons with birthdays set
 * - Sends notification on their birthday
 * - Offers to create a birthday wish video
 */
class BirthdayNotificationWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BirthdayNotification"
        const val WORK_NAME = "birthday_notification_check"
        const val CHANNEL_ID = "birthday_notifications"
        const val CHANNEL_NAME = "Birthday Reminders"
        private const val NOTIFICATION_ID_BASE = 10000

        /**
         * Schedule daily birthday check.
         * Runs every day at 9:00 AM.
         */
        fun schedule(context: Context) {
            // Calculate delay until 9 AM tomorrow
            val currentTime = Calendar.getInstance()
            val targetTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)

                // If it's already past 9 AM, schedule for tomorrow
                if (before(currentTime)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BirthdayNotificationWorker>(
                24, TimeUnit.HOURS,
                15, TimeUnit.MINUTES // Flex interval
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.i(TAG, "Birthday notification check scheduled")
        }

        /**
         * Run an immediate check (for testing or manual trigger).
         */
        fun runImmediateCheck(context: Context) {
            val request = OneTimeWorkRequestBuilder<BirthdayNotificationWorker>()
                .build()

            WorkManager.getInstance(context)
                .enqueue(request)

            Log.i(TAG, "Immediate birthday check triggered")
        }

        /**
         * Cancel scheduled birthday checks.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Birthday notification check cancelled")
        }

        /**
         * Create the notification channel (required for Android 8+).
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for birthday reminders"
                    enableVibration(true)
                }

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Running birthday check...")

        try {
            // Get today's date in MM-DD format
            val calendar = Calendar.getInstance()
            val month = calendar.get(Calendar.MONTH) + 1
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val todayMMDD = String.format("%02d-%02d", month, day)

            Log.d(TAG, "Checking for birthdays on: $todayMMDD")

            // Get database
            val database = FaceDatabase.getInstance(context)
            val personDao = database.personDao()

            // Get persons with birthday today
            val birthdayPersons = personDao.getPersonsWithBirthdayToday(todayMMDD)

            Log.i(TAG, "Found ${birthdayPersons.size} persons with birthday today")

            // Send notification for each person
            birthdayPersons.forEach { person ->
                sendBirthdayNotification(person)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking birthdays", e)
            return Result.failure()
        }
    }

    private fun sendBirthdayNotification(person: PersonEntity) {
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Notification permission not granted, skipping birthday notification")
                return
            }
        }

        val displayName = person.name ?: "Person ${person.displayNumber ?: "?"}"

        // Create intent to open the app (optionally to person detail or video creator)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("personId", person.personId)
            putExtra("action", "create_birthday_video")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            person.personId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Birthday Today!")
            .setContentText("$displayName's birthday is today! Create a birthday wish video?")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$displayName's birthday is today! Tap to create a special birthday wish video with their photos."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Create Video",
                pendingIntent
            )
            .build()

        // Show notification
        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_BASE + person.personId.hashCode(), notification)
            Log.i(TAG, "Birthday notification sent for: $displayName")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sending notification", e)
        }
    }
}
