package com.marksy.os.notification

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * Keeps each captured notification's own tap target and action buttons so Marksy can
 * redirect to the source app after it removes the original from the shade.
 * PendingIntents cannot be persisted, so this lives for the process lifetime only;
 * after that [open] falls back to launching the source app.
 */
object OriginalAppLauncher {
    data class Action(val title: String, val intent: PendingIntent)
    private data class Entry(val contentIntent: PendingIntent?, val actions: List<Action>)

    private const val MAX_ENTRIES = 500
    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }

    fun remember(sbn: StatusBarNotification) {
        val notification = sbn.notification
        // Reply-style actions need typed input Marksy can't supply, so they're left to the source app.
        val actions = notification.actions.orEmpty()
            .filter { it.actionIntent != null && it.title?.isNotBlank() == true && it.remoteInputs.isNullOrEmpty() }
            .map { Action(it.title.toString().trim(), it.actionIntent) }
        synchronized(entries) { entries[key(sbn.packageName, sbn.key)] = Entry(notification.contentIntent, actions) }
    }

    fun actionsFor(sourcePackage: String, sourceKey: String): List<Action> =
        synchronized(entries) { entries[key(sourcePackage, sourceKey)]?.actions.orEmpty() }

    /** Opens the exact screen the notification pointed to, else the app's launcher screen. */
    fun open(context: Context, sourcePackage: String, sourceKey: String): Boolean {
        val contentIntent = synchronized(entries) { entries[key(sourcePackage, sourceKey)]?.contentIntent }
        if (contentIntent != null && send(context, contentIntent)) return true
        val launch = context.packageManager.getLaunchIntentForPackage(sourcePackage) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    fun send(context: Context, intent: PendingIntent): Boolean = runCatching {
        intent.send(context, 0, null, null, null, null, backgroundStartOptions())
    }.isSuccess

    // Android 14+: the sender must opt in before a PendingIntent may start an activity.
    @Suppress("DEPRECATION")
    private fun backgroundStartOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null

    private fun key(sourcePackage: String, sourceKey: String) = "$sourcePackage|$sourceKey"
}
