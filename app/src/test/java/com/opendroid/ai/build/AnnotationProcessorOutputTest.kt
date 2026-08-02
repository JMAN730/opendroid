package com.opendroid.ai.build

import com.opendroid.ai.data.db.OpenDroidDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the annotation-processing pipeline itself.
 *
 * Room and Hilt moved from kapt to KSP. Both processors fail *quietly* in the
 * ways that matter here: a processor that never runs produces no generated
 * class, and the build still succeeds until something touches the missing type
 * at runtime. These assertions load the generated types by name so a processor
 * that silently stops running fails the unit-test gate instead of the app.
 */
class AnnotationProcessorOutputTest {

    /**
     * Resolves a path inside the `app` module regardless of whether the test's
     * working directory is the module or the repository root.
     */
    private fun moduleFile(relativePath: String): File =
        listOf(File(relativePath), File("app/$relativePath")).firstOrNull { it.exists() }
            ?: throw AssertionError(
                "Neither $relativePath nor app/$relativePath exists (working " +
                    "directory: ${File(".").absolutePath})"
            )

    private fun loadGenerated(name: String): Class<*> =
        try {
            Class.forName(name, false, javaClass.classLoader)
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "Generated class $name is missing. The annotation processor that " +
                    "produces it did not run - check the ksp dependencies in " +
                    "app/build.gradle.",
                e
            )
        }

    @Test
    fun `Room generates the database implementation`() {
        val impl = loadGenerated("com.opendroid.ai.data.db.OpenDroidDatabase_Impl")
        assertTrue(
            "OpenDroidDatabase_Impl must implement the abstract database class",
            OpenDroidDatabase::class.java.isAssignableFrom(impl)
        )
    }

    @Test
    fun `Room generates an implementation for every declared DAO`() {
        // Every abstract accessor on the database is a DAO Room must implement.
        // Enumerating them means a newly added DAO is covered without editing
        // this test.
        val daoTypes = OpenDroidDatabase::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            .map { it.returnType }
            .distinct()

        assertTrue("Expected the database to declare DAO accessors", daoTypes.isNotEmpty())

        daoTypes.forEach { dao ->
            val impl = loadGenerated("${dao.name}_Impl")
            assertTrue(
                "${impl.name} must implement ${dao.name}",
                dao.isAssignableFrom(impl)
            )
        }
    }

    @Test
    fun `Room exports a schema for the current database version`() {
        // @Database has BINARY retention, so the declared version is not readable
        // by reflection - take it from the source of truth instead.
        val source = moduleFile("src/main/java/com/opendroid/ai/data/db/OpenDroidDatabase.kt")
        val version = Regex("""version\s*=\s*(\d+)""").find(source.readText())
            ?.groupValues?.get(1)
            ?: throw AssertionError("Could not read the declared version from ${source.path}")

        val schema = moduleFile("schemas/com.opendroid.ai.data.db.OpenDroidDatabase/$version.json")
        assertTrue(
            "Expected an exported schema at ${schema.path}. Schema export is what " +
                "the Room migration tests validate against, so losing it silently " +
                "disables them.",
            schema.isFile
        )
    }

    @Test
    fun `Hilt generates the application injector and the singleton component`() {
        loadGenerated("com.opendroid.ai.OpenDroidApp_GeneratedInjector")
        // Produced by Hilt's aggregating step, which runs under javac rather than
        // KSP - it is the part of the pipeline the KSP migration is most likely
        // to break.
        loadGenerated("com.opendroid.ai.DaggerOpenDroidApp_HiltComponents_SingletonC")
    }

    @Test
    fun `Hilt generates member injectors for Android entry points`() {
        loadGenerated("com.opendroid.ai.Hilt_MainActivity")
        loadGenerated("com.opendroid.ai.accessibility.Hilt_OpenDroidAccessibilityService")
        loadGenerated("com.opendroid.ai.core.service.Hilt_OpenDroidService")
        loadGenerated("com.opendroid.ai.core.service.Hilt_OpenDroidNotificationListener")
    }

    @Test
    fun `Hilt generates view model bindings`() {
        val viewModels = listOf(
            "AutoReplyViewModel",
            "ChatViewModel",
            "CrashLogViewModel",
            "HistoryViewModel",
            "MacroViewModel",
            "MemoryViewModel",
            "NotificationHistoryViewModel",
            "PlanViewModel",
            "SettingsViewModel"
        )
        viewModels.forEach {
            loadGenerated("com.opendroid.ai.ui.viewmodel.${it}_HiltModules")
            loadGenerated("com.opendroid.ai.ui.viewmodel.${it}_Factory")
        }
    }
}
