package com.orange.echocards

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orange.echocards.data.EchoDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the exported schema for the first publishable database version.
 * When version 2 is introduced, add an explicit Migration and a test that
 * creates version 1, runs the migration, and calls validateMigration().
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSchemaTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EchoDatabase::class.java,
    )

    @Test
    fun currentSchemaCanBeCreatedAndHasAllTables() {
        helper.createDatabase("schema-validation.db", 1).use { database ->
            assertTablesExist(database, "decks", "cards", "deck_progress", "card_attempts", "user_settings")
        }
    }

    private fun assertTablesExist(database: SupportSQLiteDatabase, vararg expected: String) {
        val actual = buildSet {
            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            ).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        expected.forEach { table ->
            check(table in actual) { "Missing Room table: $table; actual=$actual" }
        }
    }
}
