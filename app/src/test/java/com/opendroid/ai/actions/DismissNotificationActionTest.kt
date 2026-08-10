package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.data.db.dao.AppNotificationCount
import com.opendroid.ai.data.db.dao.ContactNotificationCount
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DismissNotificationActionTest {

    private val context: Context = ContextWrapper(null)

    @Test
    fun `dismiss by app removes only that app's history`() = runBlocking {
        val dao = InMemoryNotificationDao().apply {
            add(notification("com.whatsapp", "WhatsApp"))
            add(notification("com.google.android.gm", "Gmail"))
        }

        val result = actionFor(dao).execute(mapOf("app" to "WhatsApp"), context)

        assertTrue(result.success)
        assertEquals("Dismissed 1 notification.", result.data)
        assertEquals(listOf("com.google.android.gm"), dao.notifications.map { it.packageName })
    }

    @Test
    fun `dismiss without a filter removes all history`() = runBlocking {
        val dao = InMemoryNotificationDao().apply {
            add(notification("com.whatsapp", "WhatsApp"))
            add(notification("com.google.android.gm", "Gmail"))
        }

        val result = actionFor(dao).execute(emptyMap(), context)

        assertTrue(result.success)
        assertEquals("Dismissed 2 notifications.", result.data)
        assertTrue(dao.notifications.isEmpty())
    }

    @Test
    fun `dismiss with no matching app is a successful no-op`() = runBlocking {
        val dao = InMemoryNotificationDao().apply {
            add(notification("com.whatsapp", "WhatsApp"))
        }

        val result = actionFor(dao).execute(mapOf("app" to "Telegram"), context)

        assertTrue(result.success)
        assertEquals("No matching notifications found.", result.data)
        assertEquals(listOf("com.whatsapp"), dao.notifications.map { it.packageName })
    }

    @Test
    fun `dismiss by id removes only the requested record`() = runBlocking {
        val dao = InMemoryNotificationDao().apply {
            add(notification("com.whatsapp", "WhatsApp"))
            add(notification("com.google.android.gm", "Gmail"))
        }
        val id = dao.notifications.first().id

        val result = actionFor(dao).execute(mapOf("notificationId" to id.toString()), context)

        assertTrue(result.success)
        assertEquals("Dismissed 1 notification.", result.data)
        assertEquals(listOf("com.google.android.gm"), dao.notifications.map { it.packageName })
    }

    private fun actionFor(dao: NotificationDao) = DismissNotificationAction(dao)

    private fun notification(packageName: String, appName: String) = NotificationEntity(
        packageName = packageName,
        appName = appName,
        title = "Test notification",
        text = "Test body"
    )

    private class InMemoryNotificationDao : NotificationDao {
        val notifications = mutableListOf<NotificationEntity>()
        private val flow = MutableStateFlow<List<NotificationEntity>>(emptyList())
        private var nextId = 1L

        fun add(notification: NotificationEntity) {
            val id = if (notification.id == 0L) nextId++ else notification.id
            notifications += notification.copy(id = id)
        }

        override suspend fun insertNotification(notification: NotificationEntity): Long {
            add(notification)
            return notifications.last().id
        }

        override fun getAllNotificationsFlow(): Flow<List<NotificationEntity>> = flow
        override suspend fun getRecentNotifications(limit: Int): List<NotificationEntity> =
            notifications.take(limit)
        override suspend fun getNotificationsByApp(packageName: String, limit: Int): List<NotificationEntity> =
            notifications.filter { it.packageName == packageName }.take(limit)
        override suspend fun getNotificationsForContact(contactName: String, limit: Int): List<NotificationEntity> =
            notifications.filter { it.contactName == contactName }.take(limit)
        override suspend fun getNotificationsSince(since: Long, limit: Int): List<NotificationEntity> =
            notifications.filter { it.timestamp > since }.take(limit)
        override suspend fun getMessageNotificationsSince(since: Long): List<NotificationEntity> =
            notifications.filter { it.timestamp > since && it.category == "MESSAGE" }
        override suspend fun markAsRead(id: Long) = Unit
        override suspend fun markAsAutoReplied(id: Long, replyText: String) = Unit
        override suspend fun getAutoReplyCountForContact(contactName: String, since: Long): Int = 0
        override suspend fun getNotificationCountByApp(since: Long): List<AppNotificationCount> = emptyList()
        override suspend fun getMostActiveContacts(since: Long, limit: Int): List<ContactNotificationCount> = emptyList()
        override suspend fun deleteOldNotifications(olderThan: Long) = Unit
        override suspend fun getTotalCount(): Int = notifications.size
        override suspend fun getAutoRepliedCount(): Int = notifications.count { it.isAutoReplied }
        override suspend fun deleteNotification(notification: NotificationEntity) {
            notifications.removeIf { it.id == notification.id }
        }
        override suspend fun clearAll(): Int = notifications.clearAndCount()
        override suspend fun deleteNotificationsByApp(packageName: String): Int =
            notifications.removeIfCount { it.packageName == packageName }
        override suspend fun deleteNotificationById(id: Long): Int =
            notifications.removeIfCount { it.id == id }

        private fun MutableList<NotificationEntity>.clearAndCount(): Int {
            val count = size
            clear()
            return count
        }

        private fun MutableList<NotificationEntity>.removeIfCount(
            predicate: (NotificationEntity) -> Boolean
        ): Int {
            val count = count(predicate)
            removeIf(predicate)
            return count
        }
    }
}
