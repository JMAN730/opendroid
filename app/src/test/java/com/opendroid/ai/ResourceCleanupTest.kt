package com.opendroid.ai

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.SystemActions
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.ContactResolver
import com.opendroid.ai.core.agent.VisionEngine
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ResourceCleanupTestApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ResourceCleanupTestApplication::class)
class ResourceCleanupTest {

    @After
    fun resetCursorProvider() {
        ThrowingCursorProvider.reset()
    }

    @Test
    fun `legacy contact resolution closes cursors when reading fails`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cursors = installThrowingCursorProvider()

        ContactResolver.resolve(PermissionGrantedContext(context), "Ada")

        assertEquals(2, cursors.size)
        assertTrue(cursors.all { it.closeCount == 1 })
    }

    @Test
    fun `contact verification closes its cursor when reading fails`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cursors = installThrowingCursorProvider()
        val action = SystemActions(unusedLazy(), unusedLazy())
            .getActions()
            .single { it.name == "VERIFY_CONTACT" }

        val result = action.execute(mapOf("contactName" to "Ada"), context)

        assertFalse(result.success)
        assertEquals(1, cursors.size)
        assertEquals(1, cursors.single().closeCount)
    }

    private class PermissionGrantedContext(base: Context) : ContextWrapper(base) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            PackageManager.PERMISSION_GRANTED

        override fun checkSelfPermission(permission: String): Int =
            PackageManager.PERMISSION_GRANTED
    }

    private fun installThrowingCursorProvider(): MutableList<ThrowingCursor> {
        val cursors = mutableListOf<ThrowingCursor>()
        ThrowingCursorProvider.install {
            ThrowingCursor().also(cursors::add)
        }
        Robolectric.setupContentProvider(
            ThrowingCursorProvider::class.java,
            ContactsContract.AUTHORITY
        )
        return cursors
    }

    private class ThrowingCursor : MatrixCursor(
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
    ) {
        init {
            addRow(arrayOf("Ada", "5550100"))
        }

        var closeCount = 0
            private set

        override fun onMove(oldPosition: Int, newPosition: Int): Boolean =
            throw IllegalStateException("Synthetic cursor read failure")

        override fun close() {
            closeCount += 1
            super.close()
        }
    }

    private fun <T> unusedLazy(): Lazy<T> = Lazy {
        error("This dependency is not used by the selected action")
    }
}

class ThrowingCursorProvider : ContentProvider() {
    companion object {
        private var cursorFactory: (() -> Cursor)? = null

        fun install(factory: () -> Cursor) {
            cursorFactory = factory
        }

        fun reset() {
            cursorFactory = null
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor = requireNotNull(cursorFactory) {
        "Install a cursor factory before querying the test provider"
    }.invoke()

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
