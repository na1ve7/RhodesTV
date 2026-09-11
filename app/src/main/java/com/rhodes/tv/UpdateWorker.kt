package com.rhodes.tv

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val c = applicationContext
        return try {
            if (Prefs.list(c, Prefs.KEY_SUBS).isNotEmpty()) {
                val d = ChannelStore.fetchAll(c)
                if (d.isNotEmpty()) ChannelStore.save(c, d)
            }
            if (Prefs.list(c, Prefs.KEY_EPG).isNotEmpty()) {
                try {
                    EpgStore.load(c)
                } catch (e: Exception) {
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "rhodes-refresh"

        fun schedule(c: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(c)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (e: Exception) {
            }
        }
    }
}
