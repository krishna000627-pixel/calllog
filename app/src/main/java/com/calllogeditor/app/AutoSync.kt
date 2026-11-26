package com.calllogeditor.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.provider.CallLog

/**
 * No keep-alive service is needed: the system wakes this job only when the call log changes,
 * runs it for a moment, then the job re-arms itself. Nothing runs (and no notification shows) in between.
 */
object Auto {
    const val JOB = 7
    private fun sp(ctx: Context) = ctx.getSharedPreferences("auto", Context.MODE_PRIVATE)
    fun namesOn(ctx: Context) = sp(ctx).getBoolean("names", false)
    fun setNames(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("names", on).apply(); refresh(ctx) }

    fun callOk(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun refresh(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val wanted = callOk(ctx) && ((namesOn(ctx) && Contacts.has(ctx)) || Rules.load(ctx).any { it.enabled })
        if (!wanted) { js.cancel(JOB); return }
        val info = JobInfo.Builder(JOB, ComponentName(ctx, NameSyncJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(CallLog.Calls.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(1000)
            .setTriggerContentMaxDelay(5000)
            .build()
        js.schedule(info)
    }
}

class NameSyncJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        val ctx = applicationContext
        Thread {
            try {
                if (Auto.callOk(ctx)) {
                    var list = CallRepo.load(ctx)
                    val rules = Rules.load(ctx)
                    if (rules.any { it.enabled }) {
                        Rules.apply(ctx, rules, list, onlyNew = true)
                        list = CallRepo.load(ctx)
                    }
                    if (Auto.namesOn(ctx) && Contacts.has(ctx)) {
                        CallRepo.syncNames(ctx, list, Contacts.loadMap(ctx), onlyBlank = true)
                    }
                }
            } catch (_: Exception) {
            } finally {
                Auto.refresh(ctx)
                jobFinished(p, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters) = true
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Auto.refresh(context) }
}

/** Direct system pop-up "Allow app to always run in background?" - no trip to the settings page. */
object Battery {
    fun exempt(ctx: Context) =
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun intent(ctx: Context) = Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        android.net.Uri.parse("package:" + ctx.packageName)
    )

    fun askedBefore(ctx: Context) = ctx.getSharedPreferences("auto", Context.MODE_PRIVATE).getBoolean("battery_asked", false)
    fun markAsked(ctx: Context) { ctx.getSharedPreferences("auto", Context.MODE_PRIVATE).edit().putBoolean("battery_asked", true).apply() }
}
