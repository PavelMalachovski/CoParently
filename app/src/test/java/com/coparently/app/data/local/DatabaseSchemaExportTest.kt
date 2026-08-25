package com.coparently.app.data.local

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The database's version and its exported schemas must not drift apart (CQ-1).
 *
 * `app/schemas/` stopped at `14.json` while the database climbed to 33, and nothing noticed for
 * nineteen versions. The cost was not theoretical: `MigrationTestHelper` builds a database at a
 * past version *from these files*, so every migration since v14 shipped with no fixture to test
 * it against — and `DatabaseModule` deliberately refuses destructive migration above v4, which
 * makes a broken one a crash on launch for somebody with real data rather than a wipe.
 *
 * This is the check that stops it happening again. It is a plain JVM test rather than a Gradle
 * task on purpose: it runs in the same `testDebugUnitTest` invocation as everything else, so it
 * gates every pull request without a new job, and it needs no Android SDK — which matters,
 * because the sessions that write most of this repository do not have one.
 *
 * It reads the source rather than the annotation. `androidx.room.Database` is retained at CLASS
 * level, so it is invisible to reflection, and loading `CoPlanlyDatabase` in a JVM test would
 * drag in the Android framework.
 *
 * **The versions between 15 and 32 are gone and are not coming back**: they were never committed,
 * and a build of today's code cannot produce them. That gap is accepted (see CQ-1 in
 * docs/ROADMAP.md); what this refuses is a *new* one.
 */
class DatabaseSchemaExportTest {

    @Test
    fun `the exported schema keeps up with the database version`() {
        val version = declaredDatabaseVersion()
        val exported = exportedSchemaVersions()

        assertTrue(
            exported.isNotEmpty(),
            "No exported schemas at all under $SCHEMA_DIR. Room writes them during kapt from " +
                "the room.schemaLocation argument in app/build.gradle.kts."
        )
        assertEquals(
            version,
            exported.max(),
            "The database is at version $version but the newest exported schema is " +
                "${exported.max()}. Run the Regenerate workflow " +
                "(.github/workflows/regenerate.yml) to export it — a bumped version with no " +
                "schema beside it is a migration nothing can ever test."
        )
    }

    @Test
    fun `every exported schema is named after the version it holds`() {
        // A file whose name and contents disagree is worse than a missing one: `MigrationTestHelper`
        // trusts the name, so it would build a database at the wrong version and validate against it.
        schemaFiles().forEach { file ->
            val fromName = file.nameWithoutExtension.toIntOrNull()
            val fromContents = VERSION_IN_JSON.find(file.readText())?.groupValues?.get(1)?.toIntOrNull()
            assertEquals(fromName, fromContents, "${file.name} declares version $fromContents")
        }
    }

    /** The `version = N` on `@Database`, read from the source. */
    private fun declaredDatabaseVersion(): Int {
        val source = resolve(DATABASE_SOURCE)
        assertTrue(source.isFile, "Cannot find ${source.path} to read the database version from")
        val match = VERSION_IN_SOURCE.find(source.readText())
        assertTrue(match != null, "No `version = N` in ${source.name}")
        return match!!.groupValues[1].toInt()
    }

    private fun exportedSchemaVersions(): List<Int> =
        schemaFiles().mapNotNull { it.nameWithoutExtension.toIntOrNull() }

    private fun schemaFiles(): List<File> =
        resolve(SCHEMA_DIR).listFiles()?.filter { it.extension == "json" }.orEmpty()

    /**
     * Resolves a repository path whether the test runs from the module directory or the root.
     *
     * Gradle sets the unit-test working directory to the module, but that is a default an IDE
     * or a future build change can move, and a test that silently passes because it looked in
     * the wrong place would be worse than no test.
     */
    private fun resolve(relative: String): File {
        val fromModule = File(relative)
        return if (fromModule.exists()) fromModule else File("app/$relative")
    }

    private companion object {
        const val SCHEMA_DIR = "schemas/com.coparently.app.data.local.CoPlanlyDatabase"
        const val DATABASE_SOURCE =
            "src/main/java/com/coparently/app/data/local/CoPlanlyDatabase.kt"
        val VERSION_IN_SOURCE = Regex("""version\s*=\s*(\d+)""")
        val VERSION_IN_JSON = Regex(""""version"\s*:\s*(\d+)""")
    }
}
