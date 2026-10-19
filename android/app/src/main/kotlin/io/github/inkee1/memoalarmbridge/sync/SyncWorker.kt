package io.github.inkee1.memoalarmbridge.sync

import android.content.Context
import androidx.work.*
import io.github.inkee1.memoalarmbridge.AppGraph
import java.util.concurrent.TimeUnit

class SyncWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    override fun doWork():Result {
        val graph=AppGraph.get(applicationContext)
        if(!graph.unlocked())return Result.retry()
        return try {
            if(!graph.content().read().linked)return Result.success()
            val report=graph.sync().run()
            if(report.more||(report.preparing>0&&runAttemptCount<3))Result.retry()else Result.success()
        }catch(_:Exception){if(runAttemptCount<5)Result.retry()else Result.failure()}
    }
}
object SyncJobs {
    private const val PERIODIC="memoalarm-periodic-sync"
    private const val REQUEST="memoalarm-request-sync"
    private fun constraints()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enable(context:Context){WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build());request(context)}
    fun request(context:Context){WorkManager.getInstance(context).enqueueUniqueWork(REQUEST,ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build())}
    fun disable(context:Context){WorkManager.getInstance(context).cancelUniqueWork(PERIODIC);WorkManager.getInstance(context).cancelUniqueWork(REQUEST)}
}
