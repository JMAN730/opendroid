package com.opendroid.ai.social.core.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.core.SocialManager
import com.opendroid.ai.social.domain.model.AutomationLevel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class SocialScheduleWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SocialScheduleWorkerEntryPoint {
        fun socialRepository(): SocialRepository
        fun socialManager(): SocialManager
    }

    private val entryPoint = EntryPointAccessors.fromApplication(
        applicationContext,
        SocialScheduleWorkerEntryPoint::class.java
    )

    private val socialRepository = entryPoint.socialRepository()
    private val socialManager = entryPoint.socialManager()

    companion object {
        private const val TAG = "SocialScheduleWorker"
        const val WORK_NAME = "opendroid_social_schedule_worker"

        fun enqueuePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SocialScheduleWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            val duePosts = socialRepository.getDueScheduledPosts(now)
            val automationLevel = socialManager.automationLevel.value

            for (post in duePosts) {
                // If the post requires approval and automation level is not AUTONOMOUS, skip silent publishing
                if (post.requiresApproval && automationLevel != AutomationLevel.AUTONOMOUS) {
                    Log.d(TAG, "Post ${post.id} requires approval before publishing; skipping.")
                    continue
                }

                val publishResult = socialManager.publishPost(post.id)
                if (publishResult.isFailure) {
                    Log.e(TAG, "Failed scheduled publishing for post ${post.id}: ${publishResult.exceptionOrNull()?.message}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing SocialScheduleWorker", e)
            Result.retry()
        }
    }
}
