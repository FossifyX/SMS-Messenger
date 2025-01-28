package org.fossify.messages.extensions

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.text.TextUtils
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.SimpleContact

fun ArrayList<SimpleContact>.getThreadTitle(): String = TextUtils.join(", ", map { it.name }.toTypedArray()).orEmpty()

fun ArrayList<SimpleContact>.getAddresses() = flatMap { it.phoneNumbers }.map { it.normalizedNumber }

fun SimpleContact.toPerson(context: Context? = null): Person {
    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, contactId.toString())
    val iconCompat = if (context != null) {
        loadIcon(context)
    } else {
        IconCompat.createWithContentUri(photoUri)
    }

    return Person.Builder()
        .setName(name)
        .setUri(uri.toString())
        .setIcon(iconCompat)
        .setKey(uri.toString())
        .build()
}

fun Person.getPhotoUri(context: Context): String {
    val contactUri = Uri.parse(uri)
    val projection = arrayOf(ContactsContract.Contacts.PHOTO_URI)
    context.contentResolver.query(contactUri, projection, null, null, null).use { cursor ->
        if (cursor != null && cursor.moveToFirst()) {
            val photoUriString = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI))
            return photoUriString
        }
    }
    return ""
}

fun SimpleContact.loadIcon(context: Context): IconCompat {
    try {
        val stream = context.contentResolver.openInputStream(Uri.parse(photoUri))
        val bitmap = BitmapFactory.decodeStream(stream)
        stream?.close()
        val iconCompat = IconCompat.createWithAdaptiveBitmap(bitmap)
        return iconCompat
    } catch (e: Exception) {
        return IconCompat.createWithAdaptiveBitmap(SimpleContactsHelper(context).getContactLetterIcon(name))
    }
}

