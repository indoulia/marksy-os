package com.marksy.os.plan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Birthdays saved on phone contacts become yearly plan items. Read-only; runs only after Contacts access is granted. */
object BirthdaySync {
    private val date = Regex("""^(?:\d{4}|-)-(\d{2})-(\d{2})""")

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contacts store "1990-03-14", or "--03-14" when the year is unknown. */
    internal fun monthDay(raw: String): Pair<Int, Int>? {
        val m = date.find(raw.trim()) ?: return null
        val month = m.groupValues[1].toInt()
        val day = m.groupValues[2].toInt()
        return (month to day).takeIf { month in 1..12 && day in 1..31 }
    }

    suspend fun sync(context: Context, repository: PlanRepository): Int {
        if (!hasPermission(context)) return 0
        val birthdays = mutableListOf<Triple<String, String, Pair<Int, Int>>>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.LOOKUP_KEY, ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val md = c.getString(2)?.let(::monthDay) ?: continue
                birthdays += Triple(key, name, md)
            }
        }
        birthdays.forEach { (key, name, md) -> repository.upsertBirthday(key, name, md.first, md.second) }
        return birthdays.size
    }
}
