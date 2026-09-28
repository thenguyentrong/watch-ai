package com.vinhnguyen.watchai.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone's contacts, read only when the user names someone to text or call, and only with their OK. */
class Contacts(
    context: Context,
) {
    private val appContext = context.applicationContext

    data class Person(
        val name: String,
        /** Best first: the one the user marked as default, then mobile. */
        val numbers: List<Number>,
    )

    data class Number(
        val number: String,
        val label: String,
    )

    fun allowed(): Boolean = appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The people best matching [name] ([NameMatch]); several if the name fits more than one. */
    suspend fun find(name: String): List<Person> = withContext(Dispatchers.IO) {
        class Row(
            val name: String,
            val number: String,
            val label: String,
            val rank: Int,
        )
        val byContact = LinkedHashMap<Long, MutableList<Row>>()
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY)
        appContext.contentResolver.query(Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val shown = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val type = c.getInt(3)
                val label = Phone.getTypeLabel(appContext.resources, type, c.getString(4)).toString()
                val rank = (if (c.getInt(5) == 1) 2 else 0) + (if (type == Phone.TYPE_MOBILE) 1 else 0)
                byContact.getOrPut(c.getLong(0)) { mutableListOf() } += Row(shown, number, label, rank)
            }
        }
        val people =
            byContact.values.map { rows ->
                val numbers =
                    rows
                        .sortedByDescending { it.rank }
                        .distinctBy { it.number.filter(Char::isDigit) }
                        .map { Number(it.number, it.label) }
                Person(rows.first().name, numbers)
            }
        NameMatch.best(name, people) { it.name }
    }
}
