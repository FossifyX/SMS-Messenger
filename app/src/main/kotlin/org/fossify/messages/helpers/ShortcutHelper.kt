package org.fossify.messages.helpers

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.text.isDigitsOnly
import java.util.HashMap
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.isOnMainThread
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getThreadParticipants
import org.fossify.messages.extensions.toPerson
import org.fossify.messages.models.Conversation


class ShortcutHelper(private val context: Context) {
    val contactsHelper = SimpleContactsHelper(context)

    fun getShortcuts(): List<ShortcutInfoCompat> {
        return ShortcutManagerCompat.getDynamicShortcuts(context)
    }

    fun getShortcut(threadId: Long): ShortcutInfoCompat? {
        return getShortcuts().find { it.id == threadId.toString() }
    }

    fun buildShortcut(conv: Conversation, capabilities: List<String> = emptyList()): ShortcutInfoCompat {
        val contactsMap: HashMap<Int, SimpleContact>? = if (!isOnMainThread()) {
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            val contacts = MyContactsContentProvider.getSimpleContacts(context, privateCursor)
            HashMap(contacts.associateBy { it.rawId })
        } else {
            null
        }

        val participants = context.getThreadParticipants(conv.threadId, contactsMap)
        val persons: Array<Person> = participants.map { it.toPerson(context) }.toTypedArray()
        val intent = Intent(context, ThreadActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(THREAD_ID, conv.threadId)
            putExtra(THREAD_TITLE, conv.title)
            putExtra(IS_RECYCLE_BIN, false) // TODO: verify that thread isn't in recycle bin
            putExtra(IS_LAUNCHED_FROM_SHORTCUT, true)
            putExtra(THREAD_NUMBER, conv.phoneNumber.ifEmpty { "unknown_phone_number" })
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val shortcut = ShortcutInfoCompat.Builder(context, conv.threadId.toString()).apply {
            setShortLabel(conv.title)
            setLongLabel(conv.title)
            setIsConversation()
            setLongLived(true)
            setPersons(persons)
            setIntent(intent)
            setRank(1)
            if (!conv.isGroupConversation && !conv.usesCustomTitle) {
                setIcon(persons[0].icon)
            } else {
                val icon = if(conv.isGroupConversation) {
                    IconCompat.createWithAdaptiveBitmap(contactsHelper.getColoredGroupIcon(conv.title).toBitmap())
                }
                else {
                    IconCompat.createWithAdaptiveBitmap(contactsHelper.getContactLetterIcon(conv.title))
                }
                setIcon(icon)
            }
            capabilities.forEach { c ->
                addCapabilityBinding(c)
            }
            if (!shouldPresentShortcut(conv)) {
                setRank(99)
            }
        }.build()

        return shortcut
    }

    fun buildShortcut(threadId: Long, capabilities: List<String> = emptyList()): ShortcutInfoCompat {
        val conv = if (!isOnMainThread()) {
            context.conversationsDB.getConversationWithThreadId(threadId)
        } else {
            null
        }

        if (conv == null) {
            val conv = Conversation(
                threadId = threadId,
                snippet = "",
                date = 0,
                read = false,
                title = threadId.toString(),
                photoUri = "",
                isGroupConversation = false,
                phoneNumber = "",
                isScheduled = false,
                usesCustomTitle = false,
                isArchived = false,
            )
            return buildShortcut(conv, capabilities)
        }

        return buildShortcut(conv, capabilities)
    }

    fun createOrUpdateShortcut(conv: Conversation): ShortcutInfoCompat {
        val shortcut = buildShortcut(conv)
        createOrUpdateShortcut(shortcut)
        return shortcut
    }

    fun createOrUpdateShortcut(threadId: Long): ShortcutInfoCompat {
        val shortcut = buildShortcut(threadId)
        createOrUpdateShortcut(shortcut)
        return shortcut
    }

    private fun createOrUpdateShortcut(shortcut: ShortcutInfoCompat) {
        if (getShortcut(shortcut.id.toLong()) != null) {
            ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
            return
        }
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /**
     * Report the usage of a thread. create shortcut if it doesn't exist
     */
    fun reportSendMessageUsage(threadId: Long) {
        val shortcut = buildShortcut(threadId, listOf("actions.intent.SEND_MESSAGE"))
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun reportReceiveMessageUsage(threadId: Long) {
        val shortcut = buildShortcut(threadId, listOf("actions.intent.RECEIVE_MESSAGE"))
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun removeShortcutForThread(threadId: Long) {
        val shortcut = getShortcut(threadId) ?: return
        ShortcutManagerCompat.removeDynamicShortcuts(context, mutableListOf(shortcut.id))
    }

    fun removeAllShortcuts() {
        val scs = getShortcuts()
        if (scs.isEmpty())
            return
        ShortcutManagerCompat.removeLongLivedShortcuts(context, scs.map { it.id })
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    fun shouldPresentShortcut(conv: Conversation): Boolean {
        if (conv.isGroupConversation) {
            return true
        }
        if (conv.isArchived || !conv.phoneNumber.isDigitsOnly()) {
            return false
        }
        return true
    }
}

fun ShortcutInfoCompat.toFormattedString(): String {
    return "$this \n\t" +
        "id : $id\n\t" +
        "$shortLabel : $longLabel\n\t" +
        "intent : $intent"
}
