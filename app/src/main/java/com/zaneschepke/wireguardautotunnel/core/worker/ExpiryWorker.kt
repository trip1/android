package com.zaneschepke.wireguardautotunnel.core.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService.NotificationChannels
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll
import java.util.concurrent.TimeUnit

/** Daily: a mesh tunnel whose `# Transwarp-Expires` is 3 days or less away gets a renew reminder. */
class ExpiryWorker(
    context: Context,
    params: WorkerParameters,
    private val tunnelRepository: TunnelRepository,
    private val notificationService: NotificationService,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        tunnelRepository.getAll().forEach { t ->
            val days = MeshEnroll.expiresInDays(t.quickConfig) ?: return@forEach
            if (days !in 0..3) return@forEach
            notificationService.show(
                NOTIFICATION_BASE_ID + t.id,
                notificationService.createNotification(
                    NotificationChannels.Events,
                    title = applicationContext.getString(R.string.key_expiry_title),
                    description = applicationContext.getString(R.string.key_expiry_desc, t.name, days),
                ),
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "mesh_key_expiry"
        private const val NOTIFICATION_BASE_ID = 7453_0000

        fun start(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ExpiryWorker>(24, TimeUnit.HOURS).build(),
                )
        }
    }
}
