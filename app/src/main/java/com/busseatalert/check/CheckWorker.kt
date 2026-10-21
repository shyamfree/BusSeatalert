package com.busseatalert.check

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.busseatalert.data.Store
import java.util.concurrent.TimeUnit

class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val checker = Checker(applicationContext)
        val watchId = inputData.getString(KEY_WATCH_ID)
        if (watchId == null) checker.runAll()
        else Store(applicationContext).watches.find { it.id == watchId }?.let { checker.runWatch(it) }
        return Result.success()
    }

    companion object {
        private const val NAME = "seat-check"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            if (!Store(context).appEnabled) {
                stopAll(context)
                return
            }
            val minutes = Store(context).settings.intervalMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<CheckWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private const val KEY_WATCH_ID = "watchId"

        /**
         * Checks one search (or all enabled ones) right away, in the background so it finishes even if
         * the user leaves the screen. A check already queued for the same search isn't duplicated.
         */
        /** Cancels the schedule and any queued checks, for the master OFF switch. */
        fun stopAll(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(NAME)
            wm.cancelAllWork()
            val store = Store(context)
            store.watches.forEach { store.setChecking(it.id, false) }
        }

        fun runNow(context: Context, watchId: String? = null) {
            if (!Store(context).appEnabled) return
            val request = OneTimeWorkRequestBuilder<CheckWorker>()
                .setConstraints(constraints)
                .apply { if (watchId != null) setInputData(workDataOf(KEY_WATCH_ID to watchId)) }
                .build()
            val store = Store(context)
            if (watchId != null) store.setChecking(watchId, true)
            else store.watches.filter { it.enabled }.forEach { store.setChecking(it.id, true) }
            WorkManager.getInstance(context)
                .enqueueUniqueWork("check-${watchId ?: "all"}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
