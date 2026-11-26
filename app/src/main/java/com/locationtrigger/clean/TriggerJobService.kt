package com.locationtrigger.clean

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TriggerJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, Pair<Job, AtomicBoolean>>()

    override fun onStartJob(params: JobParameters): Boolean {
        val sender = params.extras.getString(EXTRA_SENDER).orEmpty()
        val subId = params.extras.getInt(EXTRA_SUB_ID, -1)
        if (sender.isBlank()) return false

        val completed = AtomicBoolean(false)
        val job = scope.launch {
            try {
                LocationTriggerService.executeTrigger(applicationContext, sender, subId)
                completed.set(true)
            } finally {
                runningJobs.remove(params.jobId)
                jobFinished(params, false)
            }
        }
        runningJobs[params.jobId] = job to completed
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val entry = runningJobs.remove(params.jobId)
        entry?.first?.cancel()
        // Reschedule only if the system pre-empted the job before the SMS reply completed.
        return entry?.second?.get() != true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_SUB_ID = "sub_id"

        fun enqueue(context: Context, sender: String, subId: Int) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val extras = PersistableBundle().apply {
                putString(EXTRA_SENDER, sender)
                putInt(EXTRA_SUB_ID, subId)
            }
            val jobId = 2000 + ((SystemClock.elapsedRealtime().toInt() and 0x0FFF))
            val component = ComponentName(context, TriggerJobService::class.java)

            val primary = JobInfo.Builder(jobId, component)
                .setExtras(extras)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(true)
                    } else {
                        setOverrideDeadline(0L)
                    }
                }
                .build()

            val result = runCatching { scheduler.schedule(primary) }.getOrDefault(JobScheduler.RESULT_FAILURE)
            if (result != JobScheduler.RESULT_SUCCESS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val fallback = JobInfo.Builder(jobId, component)
                    .setExtras(extras)
                    .setOverrideDeadline(0L)
                    .build()
                runCatching { scheduler.schedule(fallback) }
            }
        }
    }
}
