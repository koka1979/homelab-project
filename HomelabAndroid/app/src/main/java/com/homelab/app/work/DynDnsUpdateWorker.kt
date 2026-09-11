package com.homelab.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.homelab.app.domain.dyndns.DynDnsInstanceMissingException
import com.homelab.app.domain.dyndns.DynDnsUpdater
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Keeps a DynHost record pointing at the current address while the app is not open.
 *
 * The run does not go through the controlled-action pipeline: nobody is present to confirm
 * anything, and the confirmation for these runs is the switch the user turned on. The updater
 * itself still skips a family whose address has not changed.
 */
class DynDnsUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun dynDnsUpdater(): DynDnsUpdater
    }

    override suspend fun doWork(): Result {
        val instanceId = inputData.getString(KEY_INSTANCE_ID) ?: return Result.failure()
        val updater = EntryPointAccessors
            .fromApplication(applicationContext, WorkerEntryPoint::class.java)
            .dynDnsUpdater()

        return try {
            val result = updater.run(instanceId, force = false)
            // A refusal that a retry cannot fix (wrong password, unknown host) must not spin:
            // the next scheduled run picks it up again once the user fixed it.
            if (result.report.failures.any { it.code == "connection" }) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: DynDnsInstanceMissingException) {
            // The instance was deleted while the schedule lived on: stop instead of retrying
            // this run forever.
            cancel(applicationContext, instanceId)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_INSTANCE_ID = "instanceId"

        private fun workName(instanceId: String) = "dyndns-update-$instanceId"

        fun schedule(context: Context, instanceId: String, intervalMinutes: Int) {
            val request = PeriodicWorkRequestBuilder<DynDnsUpdateWorker>(
                intervalMinutes.toLong().coerceAtLeast(15L),
                TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(androidx.work.workDataOf(KEY_INSTANCE_ID to instanceId))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName(instanceId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context, instanceId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(instanceId))
        }
    }
}
