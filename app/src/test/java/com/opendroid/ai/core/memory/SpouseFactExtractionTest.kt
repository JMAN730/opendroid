package com.opendroid.ai.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The extractor re-runs over the last few messages on every query, so an ordinary sentence
 * that mentions "my wife" or "my husband" must not rewrite the stored spouse fact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class SpouseFactExtractionTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var extractor: MemoryExtractor

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        extractor = MemoryExtractor(
            MemoryRepository(database.memoryDao(), database.taskHistoryDao(), database.macroDao())
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `asking for the wife's name keeps the stored name`() = runBlocking {
        val statement = user("My wife's name is Maria")
        extractor.extractFacts(listOf(statement))
        extractor.extractFacts(listOf(statement, user("What is my wife's name?")))

        assertEquals("Maria", stored("wife_name"))
    }

    @Test
    fun `sentences about other people or questions save no spouse`() = runBlocking {
        listOf(
            "Anna is my wife's sister, add her birthday",
            "Remind me tomorrow is my wife's birthday",
            "Who is my husband?",
            "Text Tom, he is my husband's boss",
            "What is my wife's name?"
        ).forEach { extractor.extractFacts(listOf(user(it))) }

        assertNull(stored("wife_name"))
        assertNull(stored("husband_name"))
    }

    @Test
    fun `explicit name statement wins over a later clause in the same message`() = runBlocking {
        extractor.extractFacts(listOf(user("My wife's name is Maria, what is my wife's birthday?")))

        assertEquals("Maria", stored("wife_name"))
    }

    @Test
    fun `plain spouse statement still saves the name`() = runBlocking {
        extractor.extractFacts(listOf(user("Maria is my wife")))
        extractor.extractFacts(listOf(user("By the way, Tom is my husband.")))

        assertEquals("Maria", stored("wife_name"))
        assertEquals("Tom", stored("husband_name"))
    }

    @Test
    fun `plain spouse statement does not replace a stored name`() = runBlocking {
        extractor.extractFacts(listOf(user("My wife's name is Maria")))
        extractor.extractFacts(listOf(user("Anna is my wife")))

        assertEquals("Maria", stored("wife_name"))
    }

    @Test
    fun `explicit name statement can still correct a stored name`() = runBlocking {
        extractor.extractFacts(listOf(user("Maria is my wife")))
        extractor.extractFacts(listOf(user("My wife's name is Anna")))

        assertEquals("Anna", stored("wife_name"))
    }

    private suspend fun stored(key: String): String? = database.memoryDao().getMemoryByKey(key)?.value

    private fun user(text: String) = ChatMessage(id = text, text = text, sender = ChatMessage.Sender.USER)
}
