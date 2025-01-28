package org.fossify.messages.extensions

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.PhoneLookup
import android.provider.OpenableColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.MmsSms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.provider.Telephony.ThreadsColumns
import android.telephony.SubscriptionManager
import android.text.TextUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import me.leolin.shortcutbadger.ShortcutBadger
import org.fossify.commons.extensions.areDigitsOnly
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.trimToComparableNumber
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.R
import org.fossify.messages.databases.MessagesDatabase
import org.fossify.messages.helpers.AttachmentUtils.parseAttachmentNames
import org.fossify.messages.helpers.Config
import org.fossify.messages.helpers.FILE_SIZE_NONE
import org.fossify.messages.helpers.MESSAGES_LIMIT
import org.fossify.messages.helpers.NotificationHelper
import org.fossify.messages.helpers.ShortcutHelper
import org.fossify.messages.helpers.generateRandomId
import org.fossify.messages.interfaces.AttachmentsDao
import org.fossify.messages.interfaces.ConversationsDao
import org.fossify.messages.interfaces.DraftsDao
import org.fossify.messages.interfaces.MessageAttachmentsDao
import org.fossify.messages.interfaces.MessagesDao
import org.fossify.messages.messaging.MessagingUtils
import org.fossify.messages.messaging.MessagingUtils.Companion.ADDRESS_SEPARATOR
import org.fossify.messages.messaging.SmsSender
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Draft
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessageAttachment
import org.fossify.messages.models.NamePhoto
import org.fossify.messages.models.RecycleBinMessage
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException

val Context.config: Config get() = Config.newInstance(applicationContext)

fun Context.getMessagesDB() = MessagesDatabase.getInstance(this)

val Context.conversationsDB: ConversationsDao get() = getMessagesDB().ConversationsDao()

val Context.attachmentsDB: AttachmentsDao get() = getMessagesDB().AttachmentsDao()

val Context.messageAttachmentsDB: MessageAttachmentsDao get() = getMessagesDB().MessageAttachmentsDao()

val Context.messagesDB: MessagesDao get() = getMessagesDB().MessagesDao()

val Context.draftsDB: DraftsDao get() = getMessagesDB().DraftsDao()

val Context.notificationHelper get() = NotificationHelper(this)

val Context.messagingUtils get() = MessagingUtils(this)

val Context.smsSender get() = SmsSender.getInstance(applicationContext as Application)

val Context.shortcutHelper get() = ShortcutHelper(this)

fun Context.getMessages(
    threadId: Long,
    getImageResolutions: Boolean,
    dateFrom: Int = -1,
    includeScheduledMessages: Boolean = true,
    limit: Int = MESSAGES_LIMIT
): ArrayList<Message> {
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms._ID,
        Sms.BODY,
        Sms.TYPE,
        Sms.ADDRESS,
        Sms.DATE,
        Sms.READ,
        Sms.THREAD_ID,
        Sms.SUBSCRIPTION_ID,
        Sms.STATUS
    )

    val rangeQuery = if (dateFrom == -1) "" else "AND ${Sms.DATE} < ${dateFrom.toLong() * 1000}"
    val selection = "${Sms.THREAD_ID} = ? $rangeQuery"
    val selectionArgs = arrayOf(threadId.toString())
    val sortOrder = "${Sms.DATE} DESC LIMIT $limit"

    val blockStatus = HashMap<String, Boolean>()
    val blockedNumbers = getBlockedNumbers()
    var messages = ArrayList<Message>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, showErrors = true) { cursor ->
        val senderNumber = cursor.getStringValue(Sms.ADDRESS) ?: return@queryCursor

        val isNumberBlocked = if (blockStatus.containsKey(senderNumber)) {
            blockStatus[senderNumber]!!
        } else {
            val isBlocked = isNumberBlocked(senderNumber, blockedNumbers)
            blockStatus[senderNumber] = isBlocked
            isBlocked
        }

        if (isNumberBlocked) {
            return@queryCursor
        }

        val id = cursor.getLongValue(Sms._ID)
        val body = cursor.getStringValue(Sms.BODY)
        val type = cursor.getIntValue(Sms.TYPE)
        val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
        val senderName = namePhoto.name
        val photoUri = namePhoto.photoUri ?: ""
        val date = (cursor.getLongValue(Sms.DATE) / 1000).toInt()
        val read = cursor.getIntValue(Sms.READ) == 1
        val thread = cursor.getLongValue(Sms.THREAD_ID)
        val subscriptionId = cursor.getIntValue(Sms.SUBSCRIPTION_ID)
        val status = cursor.getIntValue(Sms.STATUS)
        val participants = senderNumber.split(ADDRESS_SEPARATOR).map { number ->
            val phoneNumber = PhoneNumber(number, 0, "", number)
            val participantPhoto = getNameAndPhotoFromPhoneNumber(number)
            SimpleContact(
                rawId = 0,
                contactId = 0,
                name = participantPhoto.name,
                photoUri = photoUri,
                phoneNumbers = arrayListOf(phoneNumber),
                birthdays = ArrayList(),
                anniversaries = ArrayList()
            )
        }
        val isMMS = false
        val message =
            Message(
                id = id,
                body = body,
                type = type,
                status = status,
                participants = ArrayList(participants),
                date = date,
                read = read,
                threadId = thread,
                isMMS = isMMS,
                attachment = null,
                senderPhoneNumber = senderNumber,
                senderName = senderName,
                senderPhotoUri = photoUri,
                subscriptionId = subscriptionId
            )
        messages.add(message)
    }

    messages.addAll(getMMS(threadId, getImageResolutions, sortOrder, dateFrom))

    if (includeScheduledMessages) {
        try {
            val scheduledMessages = messagesDB.getScheduledThreadMessages(threadId)
            messages.addAll(scheduledMessages)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    messages = messages
        .filter { it.participants.isNotEmpty() }
        .filterNot { it.isScheduled && it.millis() < System.currentTimeMillis() }
        .sortedWith(compareBy<Message> { it.date }.thenBy { it.id })
        .takeLast(limit)
        .toMutableList() as ArrayList<Message>

    return messages
}

// as soon as a message contains multiple recipients it counts as an MMS instead of SMS
fun Context.getMMS(
    threadId: Long? = null,
    getImageResolutions: Boolean = false,
    sortOrder: String? = null,
    dateFrom: Int = -1
): ArrayList<Message> {
    val uri = Mms.CONTENT_URI
    val projection = arrayOf(
        Mms._ID,
        Mms.DATE,
        Mms.READ,
        Mms.MESSAGE_BOX,
        Mms.THREAD_ID,
        Mms.SUBSCRIPTION_ID,
        Mms.STATUS
    )

    var selection: String? = null
    var selectionArgs: Array<String>? = null

    if (threadId == null && dateFrom != -1) {
        selection =
            "${Sms.DATE} < ${dateFrom.toLong()}" //Should not multiply 1000 here, because date in mms's database is different from sms's.
    } else if (threadId != null && dateFrom == -1) {
        selection = "${Sms.THREAD_ID} = ?"
        selectionArgs = arrayOf(threadId.toString())
    } else if (threadId != null) {
        selection = "${Sms.THREAD_ID} = ? AND ${Sms.DATE} < ${dateFrom.toLong()}"
        selectionArgs = arrayOf(threadId.toString())
    }

    val messages = ArrayList<Message>()
    val contactsMap = HashMap<Int, SimpleContact>()
    val threadParticipants = HashMap<Long, ArrayList<SimpleContact>>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, showErrors = true) { cursor ->
        val mmsId = cursor.getLongValue(Mms._ID)
        val type = cursor.getIntValue(Mms.MESSAGE_BOX)
        val date = cursor.getLongValue(Mms.DATE).toInt()
        val read = cursor.getIntValue(Mms.READ) == 1
        val threadId = cursor.getLongValue(Mms.THREAD_ID)
        val subscriptionId = cursor.getIntValue(Mms.SUBSCRIPTION_ID)
        val status = cursor.getIntValue(Mms.STATUS)
        val participants = if (threadParticipants.containsKey(threadId)) {
            threadParticipants[threadId]!!
        } else {
            val parts = getThreadParticipants(threadId, contactsMap)
            threadParticipants[threadId] = parts
            parts
        }

        val isMMS = true
        val attachment = getMmsAttachment(mmsId, getImageResolutions)
        val body = attachment.text
        var senderNumber = ""
        var senderName = ""
        var senderPhotoUri = ""

        if (type != Mms.MESSAGE_BOX_SENT && type != Mms.MESSAGE_BOX_FAILED) {
            senderNumber = getMMSSender(mmsId)
            val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
            senderName = namePhoto.name
            senderPhotoUri = namePhoto.photoUri ?: ""
        }

        val message =
            Message(
                id = mmsId,
                body = body,
                type = type,
                status = status,
                participants = participants,
                date = date,
                read = read,
                threadId = threadId,
                isMMS = isMMS,
                attachment = attachment,
                senderPhoneNumber = senderNumber,
                senderName = senderName,
                senderPhotoUri = senderPhotoUri,
                subscriptionId = subscriptionId
            )
        messages.add(message)

        participants.forEach {
            contactsMap[it.rawId] = it
        }
    }

    return messages
}

fun Context.getMMSSender(msgId: Long): String {
    val uri = Uri.parse("${Mms.CONTENT_URI}/$msgId/addr")
    val projection = arrayOf(
        Mms.Addr.ADDRESS
    )

    try {
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Mms.Addr.ADDRESS)
            }
        }
    } catch (ignored: Exception) {
    }
    return ""
}

fun Context.getConversations(
    threadId: Long? = null,
    privateContacts: ArrayList<SimpleContact> = ArrayList()
): ArrayList<Conversation> {
    val archiveAvailable = config.isArchiveAvailable

    val uri = Uri.parse("${Threads.CONTENT_URI}?simple=true")
    val projection = mutableListOf(
        Threads._ID,
        Threads.SNIPPET,
        Threads.DATE,
        Threads.READ,
        Threads.RECIPIENT_IDS,
    )

    if (archiveAvailable) {
        projection += Threads.ARCHIVED
    }

    var selection = "${Threads.MESSAGE_COUNT} > 0"
    var selectionArgs = arrayOf<String>()
    if (threadId != null) {
        selection += " AND ${Threads._ID} = ?"
        selectionArgs += threadId.toString()
    }

    val sortOrder = "${Threads.DATE} DESC"

    val conversations = ArrayList<Conversation>()
    val simpleContactHelper = SimpleContactsHelper(this)
    val blockedNumbers = getBlockedNumbers()
    try {
        queryCursorUnsafe(
            uri,
            projection.toTypedArray(),
            selection,
            selectionArgs,
            sortOrder
        ) { cursor ->
            val id = cursor.getLongValue(Threads._ID)
            var snippet = cursor.getStringValue(Threads.SNIPPET) ?: ""
            if (snippet.isEmpty()) {
                snippet = getThreadSnippet(id)
            }

            var date = cursor.getLongValue(Threads.DATE)
            if (date.toString().length > 10) {
                date /= 1000
            }

            // drafts are stored locally they take priority over the original date
            val draft = draftsDB.getDraftById(id)
            if (draft != null) {
                date = draft.date / 1000
            }

            val rawIds = cursor.getStringValue(Threads.RECIPIENT_IDS)
            val recipientIds =
                rawIds.split(" ").filter { it.areDigitsOnly() }.map { it.toInt() }.toMutableList()
            val phoneNumbers = getThreadPhoneNumbers(recipientIds)
            if (phoneNumbers.isEmpty() || phoneNumbers.any {
                    isNumberBlocked(
                        it,
                        blockedNumbers
                    )
                }) {
                return@queryCursorUnsafe
            }

            val names = getThreadContactNames(phoneNumbers, privateContacts)
            val title = TextUtils.join(", ", names.toTypedArray())
            val photoUri =
                if (phoneNumbers.size == 1) simpleContactHelper.getPhotoUriFromPhoneNumber(
                    phoneNumbers.first()
                ) else ""
            val isGroupConversation = phoneNumbers.size > 1
            val read = cursor.getIntValue(Threads.READ) == 1
            val archived =
                if (archiveAvailable) cursor.getIntValue(Threads.ARCHIVED) == 1 else false
            val conversation = Conversation(
                threadId = id,
                snippet = snippet,
                date = date.toInt(),
                read = read,
                title = title,
                photoUri = photoUri,
                isGroupConversation = isGroupConversation,
                phoneNumber = phoneNumbers.first(),
                isArchived = archived
            )
            conversations.add(conversation)
        }
    } catch (sqliteException: SQLiteException) {
        if (sqliteException.message?.contains("no such column: archived") == true && archiveAvailable) {
            config.isArchiveAvailable = false
            return getConversations(threadId, privateContacts)
        } else {
            showErrorToast(sqliteException)
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }

    conversations.sortByDescending { it.date }
    return conversations
}

private fun Context.queryCursorUnsafe(
    uri: Uri,
    projection: Array<String>,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
    callback: (cursor: Cursor) -> Unit
) {
    val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
    cursor?.use {
        if (cursor.moveToFirst()) {
            do {
                callback(cursor)
            } while (cursor.moveToNext())
        }
    }
}

fun Context.getConversationIds(): List<Long> {
    val uri = Uri.parse("${Threads.CONTENT_URI}?simple=true")
    val projection = arrayOf(Threads._ID)
    val selection = "${Threads.MESSAGE_COUNT} > 0"
    val sortOrder = "${Threads.DATE} ASC"
    val conversationIds = mutableListOf<Long>()
    queryCursor(uri, projection, selection, null, sortOrder, true) { cursor ->
        val id = cursor.getLongValue(Threads._ID)
        conversationIds.add(id)
    }
    return conversationIds
}

// based on https://stackoverflow.com/a/6446831/1967672
@SuppressLint("NewApi")
fun Context.getMmsAttachment(id: Long, getImageResolutions: Boolean): MessageAttachment {
    val uri = if (isQPlus()) {
        Mms.Part.CONTENT_URI
    } else {
        Uri.parse("content://mms/part")
    }

    val projection = arrayOf(
        Mms._ID,
        Mms.Part.CONTENT_TYPE,
        Mms.Part.TEXT
    )
    val selection = "${Mms.Part.MSG_ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    val messageAttachment = MessageAttachment(id, "", arrayListOf())

    var attachmentNames: List<String>? = null
    var attachmentCount = 0
    queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
        val partId = cursor.getLongValue(Mms._ID)
        val mimetype = cursor.getStringValue(Mms.Part.CONTENT_TYPE)
        if (mimetype == "text/plain") {
            messageAttachment.text = cursor.getStringValue(Mms.Part.TEXT) ?: ""
        } else if (mimetype.startsWith("image/") || mimetype.startsWith("video/")) {
            val fileUri = Uri.withAppendedPath(uri, partId.toString())
            var width = 0
            var height = 0

            if (getImageResolutions) {
                try {
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeStream(
                        contentResolver.openInputStream(fileUri),
                        null,
                        options
                    )
                    width = options.outWidth
                    height = options.outHeight
                } catch (e: Exception) {
                }
            }

            val attachment = Attachment(partId, id, fileUri.toString(), mimetype, width, height, "")
            messageAttachment.attachments.add(attachment)
        } else if (mimetype != "application/smil") {
            val attachmentName = attachmentNames?.getOrNull(attachmentCount) ?: ""
            val attachment = Attachment(
                id = partId,
                messageId = id,
                uriString = Uri.withAppendedPath(uri, partId.toString()).toString(),
                mimetype = mimetype,
                width = 0,
                height = 0,
                filename = attachmentName
            )
            messageAttachment.attachments.add(attachment)
            attachmentCount++
        } else {
            val text = cursor.getStringValue(Mms.Part.TEXT)
            attachmentNames = try {
                parseAttachmentNames(text)
            } catch (e: XmlPullParserException) {
                e.printStackTrace()
                null
            }
        }
    }

    return messageAttachment
}

fun Context.getLatestMMS(): Message? {
    val sortOrder = "${Mms.DATE} DESC LIMIT 1"
    return getMMS(sortOrder = sortOrder).firstOrNull()
}

fun Context.getThreadSnippet(threadId: Long): String {
    val sortOrder = "${Mms.DATE} DESC LIMIT 1"
    val latestMms = getMMS(threadId, false, sortOrder).firstOrNull()
    var snippet = latestMms?.body ?: ""

    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.BODY
    )

    val selection = "${Sms.THREAD_ID} = ? AND ${Sms.DATE} > ?"
    val selectionArgs = arrayOf(
        threadId.toString(),
        latestMms?.date?.toString() ?: "0"
    )
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        cursor?.use {
            if (cursor.moveToFirst()) {
                snippet = cursor.getStringValue(Sms.BODY)
            }
        }
    } catch (ignored: Exception) {
    }
    return snippet
}

fun Context.getMessageRecipientAddress(messageId: Long): String {
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.ADDRESS
    )

    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(messageId.toString())

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Sms.ADDRESS)
            }
        }
    } catch (e: Exception) {
    }

    return ""
}

fun Context.getThreadParticipants(
    threadId: Long,
    contactsMap: HashMap<Int, SimpleContact>?
): ArrayList<SimpleContact> {
    val uri = Uri.parse("${MmsSms.CONTENT_CONVERSATIONS_URI}?simple=true")
    val projection = arrayOf(
        ThreadsColumns.RECIPIENT_IDS
    )
    val selection = "${Mms._ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    val participants = ArrayList<SimpleContact>()
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                val address = cursor.getStringValue(ThreadsColumns.RECIPIENT_IDS)
                address.split(" ").filter { it.areDigitsOnly() }.forEach {
                    val addressId = it.toInt()
                    if (contactsMap?.containsKey(addressId) == true) {
                        participants.add(contactsMap[addressId]!!)
                        return@forEach
                    }

                    val number = getPhoneNumberFromAddressId(addressId)
                    val namePhoto = getNameAndPhotoFromPhoneNumber(number)
                    val name = namePhoto.name
                    val photoUri = namePhoto.photoUri ?: ""
                    val phoneNumber = PhoneNumber(number, 0, "", number)
                    val contact = SimpleContact(
                        rawId = addressId,
                        contactId = addressId,
                        name = name,
                        photoUri = photoUri,
                        phoneNumbers = arrayListOf(phoneNumber),
                        birthdays = ArrayList(),
                        anniversaries = ArrayList()
                    )
                    participants.add(contact)
                }
            }
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }
    return participants
}

fun Context.getThreadPhoneNumbers(recipientIds: List<Int>): ArrayList<String> {
    val numbers = ArrayList<String>()
    recipientIds.forEach {
        numbers.add(getPhoneNumberFromAddressId(it))
    }
    return numbers
}

fun Context.getThreadContactNames(
    phoneNumbers: List<String>,
    privateContacts: ArrayList<SimpleContact>
): ArrayList<String> {
    val names = ArrayList<String>()
    phoneNumbers.forEach { number ->
        val name = SimpleContactsHelper(this).getNameFromPhoneNumber(number)
        if (name != number) {
            names.add(name)
        } else {
            val privateContact = privateContacts.firstOrNull { it.doesHavePhoneNumber(number) }
            if (privateContact == null) {
                names.add(name)
            } else {
                names.add(privateContact.name)
            }
        }
    }
    return names
}

fun Context.getPhoneNumberFromAddressId(canonicalAddressId: Int): String {
    val uri = Uri.withAppendedPath(MmsSms.CONTENT_URI, "canonical-addresses")
    val projection = arrayOf(
        Mms.Addr.ADDRESS
    )

    val selection = "${Mms._ID} = ?"
    val selectionArgs = arrayOf(canonicalAddressId.toString())
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Mms.Addr.ADDRESS)
            }
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }
    return ""
}

fun Context.getSuggestedContacts(privateContacts: ArrayList<SimpleContact>): ArrayList<SimpleContact> {
    val contacts = ArrayList<SimpleContact>()
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.ADDRESS
    )

    val sortOrder = "${Sms.DATE} DESC LIMIT 50"
    val blockedNumbers = getBlockedNumbers()

    queryCursor(uri, projection, null, null, sortOrder, showErrors = true) { cursor ->
        val senderNumber = cursor.getStringValue(Sms.ADDRESS) ?: return@queryCursor
        val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
        var senderName = namePhoto.name
        var photoUri = namePhoto.photoUri ?: ""
        if (isNumberBlocked(senderNumber, blockedNumbers)) {
            return@queryCursor
        } else if (namePhoto.name == senderNumber) {
            if (privateContacts.isNotEmpty()) {
                val privateContact =
                    privateContacts.firstOrNull { it.phoneNumbers.first().normalizedNumber == senderNumber }
                if (privateContact != null) {
                    senderName = privateContact.name
                    photoUri = privateContact.photoUri
                } else {
                    return@queryCursor
                }
            } else {
                return@queryCursor
            }
        }

        val phoneNumber = PhoneNumber(senderNumber, 0, "", senderNumber)
        val contact = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(phoneNumber),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )
        if (!contacts.map { it.phoneNumbers.first().normalizedNumber.trimToComparableNumber() }
                .contains(senderNumber.trimToComparableNumber())) {
            contacts.add(contact)
        }
    }

    return contacts
}

fun Context.getNameAndPhotoFromPhoneNumber(number: String): NamePhoto {
    if (!hasPermission(PERMISSION_READ_CONTACTS)) {
        return NamePhoto(number, null)
    }

    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    val projection = arrayOf(
        PhoneLookup.DISPLAY_NAME,
        PhoneLookup.PHOTO_URI
    )

    try {
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor.use {
            if (cursor?.moveToFirst() == true) {
                val name = cursor.getStringValue(PhoneLookup.DISPLAY_NAME)
                val photoUri = cursor.getStringValue(PhoneLookup.PHOTO_URI)
                return NamePhoto(name, photoUri)
            }
        }
    } catch (e: Exception) {
    }

    return NamePhoto(number, null)
}

fun Context.insertNewSMS(
    address: String,
    subject: String,
    body: String,
    date: Long,
    read: Int,
    threadId: Long,
    type: Int,
    subscriptionId: Int
): Long {
    val uri = Sms.CONTENT_URI
    val contentValues = ContentValues().apply {
        put(Sms.ADDRESS, address)
        put(Sms.SUBJECT, subject)
        put(Sms.BODY, body)
        put(Sms.DATE, date)
        put(Sms.READ, read)
        put(Sms.THREAD_ID, threadId)
        put(Sms.TYPE, type)
        put(Sms.SUBSCRIPTION_ID, subscriptionId)
    }

    return try {
        val newUri = contentResolver.insert(uri, contentValues)
        newUri?.lastPathSegment?.toLong() ?: 0L
    } catch (e: Exception) {
        0L
    }
}

fun Context.removeAllArchivedConversations(callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        try {
            for (conversation in conversationsDB.getAllArchived()) {
                deleteConversation(conversation.threadId)
            }
            toast(R.string.archive_emptied_successfully)
            callback?.invoke()
        } catch (e: Exception) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        }
    }
}

fun Context.deleteConversation(threadId: Long) {
    var uri = Sms.CONTENT_URI
    val selection = "${Sms.THREAD_ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    try {
        contentResolver.delete(uri, selection, selectionArgs)
    } catch (e: Exception) {
        showErrorToast(e)
    }

    uri = Mms.CONTENT_URI
    try {
        contentResolver.delete(uri, selection, selectionArgs)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    conversationsDB.deleteThreadId(threadId)
    messagesDB.deleteThreadMessages(threadId)

    if (config.customNotifications.contains(threadId.toString())) {
        config.removeCustomNotificationsByThreadId(threadId)
        notificationManager.deleteNotificationChannel(threadId.toString())
    }
    if(shortcutHelper.getShortcut(threadId) != null) {
        shortcutHelper.removeShortcutForThread(threadId)
    }
}

fun Context.checkAndDeleteOldRecycleBinMessages(callback: (() -> Unit)? = null) {
    if (config.useRecycleBin && config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                for (message in messagesDB.getOldRecycleBinMessages(System.currentTimeMillis() - MONTH_SECONDS * 1000L)) {
                    deleteMessage(message.id, message.isMMS)
                }
                callback?.invoke()
            } catch (e: Exception) {
            }
        }
    }
}

fun Context.emptyMessagesRecycleBin() {
    val messages = messagesDB.getAllRecycleBinMessages()
    for (message in messages) {
        deleteMessage(message.id, message.isMMS)
    }
}

fun Context.emptyMessagesRecycleBinForConversation(threadId: Long) {
    val messages = messagesDB.getThreadMessagesFromRecycleBin(threadId)
    for (message in messages) {
        deleteMessage(message.id, message.isMMS)
    }
}

fun Context.restoreAllMessagesFromRecycleBinForConversation(threadId: Long) {
    messagesDB.deleteThreadMessagesFromRecycleBin(threadId)
}

fun Context.moveMessageToRecycleBin(id: Long) {
    try {
        messagesDB.insertRecycleBinEntry(RecycleBinMessage(id, System.currentTimeMillis()))
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.restoreMessageFromRecycleBin(id: Long) {
    try {
        messagesDB.deleteFromRecycleBin(id)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.updateConversationArchivedStatus(threadId: Long, archived: Boolean) {
    val uri = Threads.CONTENT_URI
    val values = ContentValues().apply {
        put(Threads.ARCHIVED, archived)
    }
    val selection = "${Threads._ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    try {
        contentResolver.update(uri, values, selection, selectionArgs)
    } catch (sqliteException: SQLiteException) {
        if (sqliteException.message?.contains("no such column: archived") == true && config.isArchiveAvailable) {
            config.isArchiveAvailable = false
            return
        } else {
            throw sqliteException
        }
    }
    if (archived) {
        conversationsDB.moveToArchive(threadId)
    } else {
        conversationsDB.unarchive(threadId)
    }
}

fun Context.deleteMessage(id: Long, isMMS: Boolean) {
    val uri = if (isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    try {
        contentResolver.delete(uri, selection, selectionArgs)
        messagesDB.delete(id)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.deleteScheduledMessage(messageId: Long) {
    try {
        messagesDB.delete(messageId)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.markMessageRead(id: Long, isMMS: Boolean) {
    val uri = if (isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
    val contentValues = ContentValues().apply {
        put(Sms.READ, 1)
        put(Sms.SEEN, 1)
    }
    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    contentResolver.update(uri, contentValues, selection, selectionArgs)
    messagesDB.markRead(id)
}

fun Context.markThreadMessagesRead(threadId: Long) {
    arrayOf(Sms.CONTENT_URI, Mms.CONTENT_URI).forEach { uri ->
        val contentValues = ContentValues().apply {
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
        }
        val selection = "${Sms.THREAD_ID} = ?"
        val selectionArgs = arrayOf(threadId.toString())
        contentResolver.update(uri, contentValues, selection, selectionArgs)
    }
    messagesDB.markThreadRead(threadId)
}

fun Context.markThreadMessagesUnread(threadId: Long) {
    arrayOf(Sms.CONTENT_URI, Mms.CONTENT_URI).forEach { uri ->
        val contentValues = ContentValues().apply {
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
        }
        val selection = "${Sms.THREAD_ID} = ?"
        val selectionArgs = arrayOf(threadId.toString())
        contentResolver.update(uri, contentValues, selection, selectionArgs)
    }
}

fun Context.updateUnreadCountBadge(conversations: List<Conversation>) {
    val unreadCount = conversations.count { !it.read }
    if (unreadCount == 0) {
        ShortcutBadger.removeCount(this)
    } else {
        ShortcutBadger.applyCount(this, unreadCount)
    }
}

@SuppressLint("NewApi")
fun Context.getThreadId(address: String): Long {
    return try {
        Threads.getOrCreateThreadId(this, address)
    } catch (e: Exception) {
        0L
    }
}

@SuppressLint("NewApi")
fun Context.getThreadId(addresses: Set<String>): Long {
    return try {
        Threads.getOrCreateThreadId(this, addresses)
    } catch (e: Exception) {
        0L
    }
}

fun Context.showReceivedMessageNotification(
    messageId: Long,
    address: String,
    body: String,
    threadId: Long,
    bitmap: Bitmap?
) {
    val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
    ensureBackgroundThread {
        val senderName = getNameFromAddress(address, privateCursor)

        Handler(Looper.getMainLooper()).post {
            notificationHelper.showMessageNotification(
                messageId = messageId,
                address = address,
                body = body,
                threadId = threadId,
                bitmap = bitmap,
                sender = senderName
            )
        }
    }
}

fun Context.getNameFromAddress(address: String, privateCursor: Cursor?): String {
    var sender = getNameAndPhotoFromPhoneNumber(address).name
    if (address == sender) {
        val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
        sender = privateContacts.firstOrNull { it.doesHavePhoneNumber(address) }?.name ?: address
    }
    return sender
}

fun Context.getContactFromAddress(address: String, callback: ((contact: SimpleContact?) -> Unit)) {
    val privateCursor = getMyContactsCursor(false, true)
    SimpleContactsHelper(this).getAvailableContacts(false) {
        val contact = it.firstOrNull { it.doesHavePhoneNumber(address) }
        if (contact == null) {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val privateContact = privateContacts.firstOrNull { it.doesHavePhoneNumber(address) }
            callback(privateContact)
        } else {
            callback(contact)
        }
    }
}

fun Context.getNotificationBitmap(photoUri: String): Bitmap? {
    val size = resources.getDimension(R.dimen.notification_large_icon_size).toInt()
    if (photoUri.isEmpty()) {
        return null
    }

    val options = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .centerCrop()

    return try {
        Glide.with(this)
            .asBitmap()
            .load(photoUri)
            .apply(options)
            .apply(RequestOptions.circleCropTransform())
            .into(size, size)
            .get()
    } catch (e: Exception) {
        null
    }
}

fun Context.removeDiacriticsIfNeeded(text: String): String {
    return if (config.useSimpleCharacters) text.normalizeString() else text
}

fun Context.getSmsDraft(threadId: Long): String {
    val draft = try {
        draftsDB.getDraftById(threadId)
    } catch (e: Exception) {
        null
    }

    return draft?.body.orEmpty()
}

fun Context.getAllDrafts(): HashMap<Long, String> {
    val drafts = HashMap<Long, String>()
    try {
        draftsDB.getAll().forEach {
            drafts[it.threadId] = it.body
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return drafts
}

fun Context.saveSmsDraft(body: String, threadId: Long) {
    val draft = Draft(
        threadId = threadId,
        body = body,
        date = System.currentTimeMillis()
    )

    try {
        draftsDB.insertOrUpdate(draft)
    } catch (e: Exception) {
        e.printStackTrace()
        showErrorToast(e)
    }
}

fun Context.deleteSmsDraft(threadId: Long) {
    try {
        draftsDB.delete(threadId)
    } catch (e: Exception) {
        e.printStackTrace()
        showErrorToast(e)
    }
}

// Revert changes done by version 1.1.1 (https://github.com/FossifyOrg/Messages/issues/274)
fun Context.clearSystemDrafts() {
    ensureBackgroundThread {
        val uri = Sms.Draft.CONTENT_URI
        val projection = arrayOf(Sms._ID, Sms.BODY)
        queryCursor(uri = uri, projection = projection) { cursor ->
            val draftId = cursor.getLongValue(Sms._ID)
            val body = cursor.getStringValue(Sms.BODY) ?: return@queryCursor
            if (body.isEmpty() || body.isBlank()) {
                val draftUri = Uri.withAppendedPath(Sms.CONTENT_URI, "/${draftId}")
                contentResolver.delete(draftUri, null, null)
                return@queryCursor
            }
        }
    }
}

fun Context.updateLastConversationMessage(threadId: Long) {
    updateLastConversationMessage(setOf(threadId))
}

fun Context.updateLastConversationMessage(threadIds: Iterable<Long>) {
    // update the date and the snippet of the threads, by triggering the
    // following Android code (which runs even if no messages are deleted):
    // https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/android14-release/src/com/android/providers/telephony/MmsSmsProvider.java#1409
    val uri = Threads.CONTENT_URI
    val selection =
        "1 = 0" // always-false condition, because we don't actually want to delete any messages
    try {
        contentResolver.delete(uri, selection, null)
        for (threadId in threadIds) {
            val newConversation = getConversations(threadId)[0]
            insertOrUpdateConversation(newConversation)
        }
    } catch (e: Exception) {
    }
}

fun Context.getFileSizeFromUri(uri: Uri): Long {
    val assetFileDescriptor = try {
        contentResolver.openAssetFileDescriptor(uri, "r")
    } catch (e: FileNotFoundException) {
        null
    }

    // uses ParcelFileDescriptor#getStatSize underneath if failed
    val length = assetFileDescriptor?.use { it.length } ?: FILE_SIZE_NONE
    if (length != -1L) {
        return length
    }

    // if "content://" uri scheme, try contentResolver table
    if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT)) {
        return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                // maybe shouldn't trust ContentResolver for size:
                // https://stackoverflow.com/questions/48302972/content-resolver-returns-wrong-size
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex == -1) {
                    return@use FILE_SIZE_NONE
                }
                cursor.moveToFirst()
                return try {
                    cursor.getLong(sizeIndex)
                } catch (_: Throwable) {
                    FILE_SIZE_NONE
                }
            } ?: FILE_SIZE_NONE
    } else {
        return FILE_SIZE_NONE
    }
}

// fix a glitch at enabling Release version minifying from 5.12.3
// reset messages in 5.14.3 again, as PhoneNumber is no longer minified
// reset messages in 5.19.1 again, as SimpleContact is no longer minified
fun Context.clearAllMessagesIfNeeded(callback: () -> Unit) {
    if (!config.wasDbCleared) {
        ensureBackgroundThread {
            messagesDB.deleteAll()
            config.wasDbCleared = true
            Handler(Looper.getMainLooper()).post(callback)
        }
    } else {
        callback()
    }
}

fun Context.subscriptionManagerCompat(): SubscriptionManager {
    return getSystemService(SubscriptionManager::class.java)
}

fun Context.insertOrUpdateConversation(
    conversation: Conversation,
    cachedConv: Conversation? = conversationsDB.getConversationWithThreadId(conversation.threadId)
) {
    var updatedConv = conversation
    if (cachedConv != null && cachedConv.usesCustomTitle) {
        updatedConv =
            updatedConv.copy(title = cachedConv.title, usesCustomTitle = cachedConv.usesCustomTitle)
    }
    conversationsDB.insertOrUpdate(updatedConv)
}

fun Context.renameConversation(conversation: Conversation, newTitle: String): Conversation {
    val updatedConv = conversation.copy(title = newTitle, usesCustomTitle = true)
    try {
        conversationsDB.insertOrUpdate(updatedConv)
        ensureBackgroundThread {
            shortcutHelper.createOrUpdateShortcut(updatedConv)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return updatedConv
}

fun Context.createTemporaryThread(
    message: Message,
    threadId: Long = generateRandomId(),
    cachedConv: Conversation?
) {
    val simpleContactHelper = SimpleContactsHelper(this)
    val addresses = message.participants.getAddresses()
    val photoUri =
        if (addresses.size == 1) simpleContactHelper.getPhotoUriFromPhoneNumber(addresses.first()) else ""
    val title = if (cachedConv != null && cachedConv.usesCustomTitle) {
        cachedConv.title
    } else {
        message.participants.getThreadTitle()
    }

    val conversation = Conversation(
        threadId = threadId,
        snippet = message.body,
        date = message.date,
        read = true,
        title = title,
        photoUri = photoUri,
        isGroupConversation = addresses.size > 1,
        phoneNumber = addresses.first(),
        isScheduled = true,
        usesCustomTitle = cachedConv?.usesCustomTitle == true,
        isArchived = false
    )
    try {
        conversationsDB.insertOrUpdate(conversation)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun Context.updateScheduledMessagesThreadId(messages: List<Message>, newThreadId: Long) {
    val scheduledMessages = messages.map { it.copy(threadId = newThreadId) }.toTypedArray()
    messagesDB.insertMessages(*scheduledMessages)
}

fun Context.clearExpiredScheduledMessages(threadId: Long, messagesToDelete: List<Message>? = null) {
    val messages = messagesToDelete ?: messagesDB.getScheduledThreadMessages(threadId)
    val now = System.currentTimeMillis() + 500L

    try {
        messages.filter { it.isScheduled && it.millis() < now }.forEach { msg ->
            messagesDB.delete(msg.id)
        }
        if (messages.filterNot { it.isScheduled && it.millis() < now }.isEmpty()) {
            // delete empty temporary thread
            val conversation = conversationsDB.getConversationWithThreadId(threadId)
            if (conversation != null && conversation.isScheduled) {
                conversationsDB.deleteThreadId(threadId)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return
    }
}

fun Context.getDefaultKeyboardHeight() =
    resources.getDimensionPixelSize(R.dimen.default_keyboard_height)
