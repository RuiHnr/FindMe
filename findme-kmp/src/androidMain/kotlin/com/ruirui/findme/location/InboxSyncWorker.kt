package com.ruirui.findme.location

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ruirui.findme.app.FindMeApp
import com.ruirui.findme.sync.SyncOrchestrator
import java.util.concurrent.TimeUnit

class InboxSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
    ) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val orchastrator: SyncOrchestrator = FindMeApp.instance.syncOrchestrator

        return orchastrator.syncAll().fold(
            onSuccess = {
                Log.d("FindMe", "Periodic sync succeeded")
                Result.success()
            },
            onFailure = { error ->
                Log.e("FindMe", "Periodic sync failed", error)
                Result.retry()
            }
        )
    }

    companion object {
        private const val WORK_NAME = "findme_inbox_sync"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<InboxSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}