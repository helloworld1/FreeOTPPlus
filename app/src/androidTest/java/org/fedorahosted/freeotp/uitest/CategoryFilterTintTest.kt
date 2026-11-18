package org.fedorahosted.freeotp.uitest

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.android.material.color.MaterialColors
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.ui.EditActivity
import org.fedorahosted.freeotp.ui.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class CategoryFilterTintTest {

    @Test
    fun testFilterIconTintedAndMultiCategoryFiltering() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(
                            id = 0L,
                            ordinal = 0L,
                            categories = listOf("Work", "Cloud")
                        )
                    )
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_TOTP_TOKEN_2.copy(
                            id = 0L,
                            ordinal = 1L,
                            categories = emptyList()
                        )
                    )
                }
            }
            // Multi-category token card shows joined categories
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
                .check(matches(hasDescendant(withText("Work • Cloud"))))

            // Initially "All categories" is selected -> default control normal tint
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val filterItem = toolbar.menu.findItem(R.id.action_filter)
                val expectedNormal = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorControlNormal)
                assertThat(MenuItemCompat.getIconTintList(filterItem)?.defaultColor).isEqualTo(expectedNormal)
            }

            // Select "Uncategorized" -> highlighted with colorPrimary and shows Token 2
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText(R.string.uncategorized)).perform(click())

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val filterItem = toolbar.menu.findItem(R.id.action_filter)
                val expectedActive = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorPrimary)
                assertThat(MenuItemCompat.getIconTintList(filterItem)?.defaultColor).isEqualTo(expectedActive)
            }
            onView(withId(R.id.token_list))
                .check(matches(hasDescendant(withText("microsoft.com"))))

            // Select "Work" -> Token 1 matches
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText("Work")).perform(click())
            onView(withId(R.id.token_list))
                .check(matches(hasDescendant(withText("github.com"))))

            // Select "Cloud" -> Token 1 also matches its second category
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText("Cloud")).perform(click())
            onView(withId(R.id.token_list))
                .check(matches(hasDescendant(withText("github.com"))))

            // Select "All categories" -> restored to colorControlNormal
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText(R.string.all_categories)).perform(click())

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val filterItem = toolbar.menu.findItem(R.id.action_filter)
                val expectedNormal = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorControlNormal)
                assertThat(MenuItemCompat.getIconTintList(filterItem)?.defaultColor).isEqualTo(expectedNormal)
            }
        }
    }

    @Test
    fun testEditMultiCategoryChipsAndCreateNewCategory() {
        lateinit var db: OtpTokenDatabase
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                db = activity.otpTokenDatabase
                runBlocking(Dispatchers.IO) {
                    db.clearAllTables()
                    db.otpTokenDao().insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(
                            id = 1L,
                            ordinal = 0L,
                            categories = listOf("Work")
                        )
                    )
                    db.otpTokenDao().insert(
                        TestData.OTP_TOTP_TOKEN_2.copy(
                            id = 2L,
                            ordinal = 1L,
                            categories = listOf("Personal")
                        )
                    )
                }
            }
        }

        val editIntent = Intent(getInstrumentation().targetContext, EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_TOKEN_ID, 1L)

        ActivityScenario.launch<EditActivity>(editIntent).use {
            // Existing "Work" chip is checked; toggle "Personal" on as well
            onView(withText("Work")).check(matches(isChecked()))
            onView(withText("Personal")).perform(scrollTo(), click())

            // Add a brand new category "Cloud" via [+ New category] chip
            onView(withId(R.id.add_category_chip)).perform(scrollTo(), click())
            onView(withId(R.id.category_edit_text)).inRoot(isDialog())
                .perform(replaceText("Cloud"), closeSoftKeyboard())
            onView(withText(R.string.add)).inRoot(isDialog()).perform(click())

            onView(withText("Cloud")).check(matches(isChecked()))
            onView(withId(R.id.save)).perform(click())
        }

        val updated = runBlocking(Dispatchers.IO) {
            db.otpTokenDao().get(1L).first()
        }
        assertThat(updated).isNotNull()
        assertThat(updated!!.categories).containsExactly("Work", "Personal", "Cloud").inOrder()
    }

    @Test
    fun testRoomMigrationV2ToV3PreservesExistingSingleCategories() {
        val context = getInstrumentation().targetContext
        val dbName = "migration-v2-to-v3-test.db"
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Create a real v2 SQLite database matching the old v2 schema
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sqlDb ->
            sqlDb.execSQL(
                """
                CREATE TABLE `otp_tokens` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `issuer` TEXT,
                    `label` TEXT NOT NULL,
                    `imagePath` TEXT,
                    `tokenType` TEXT NOT NULL,
                    `algorithm` TEXT NOT NULL,
                    `secret` TEXT NOT NULL,
                    `digits` INTEGER NOT NULL,
                    `counter` INTEGER NOT NULL,
                    `period` INTEGER NOT NULL,
                    `encryptionType` TEXT NOT NULL,
                    `category` TEXT
                )
                """.trimIndent()
            )
            sqlDb.execSQL(
                """
                INSERT INTO `otp_tokens` VALUES
                (1, 1, 'GitHub', 'alice', NULL, 'TOTP', 'SHA1', 'JBSWY3DPEHPK3PXP', 6, 0, 30, 'PLAIN_TEXT', 'Work'),
                (2, 2, 'AWS', 'prod', NULL, 'TOTP', 'SHA1', 'JBSWY3DPEHPK3PXP', 6, 0, 30, 'PLAIN_TEXT', 'Cloud, "Infra"'),
                (3, 3, 'PersonalApp', 'bob', NULL, 'TOTP', 'SHA1', 'JBSWY3DPEHPK3PXP', 6, 0, 30, 'PLAIN_TEXT', NULL)
                """.trimIndent()
            )
            sqlDb.version = 2
        }

        val migratedDb = Room.databaseBuilder(context, OtpTokenDatabase::class.java, dbName)
            .addMigrations(OtpTokenDatabase.MIGRATION_1_2, OtpTokenDatabase.MIGRATION_2_3)
            .build()

        try {
            val tokens = runBlocking(Dispatchers.IO) { migratedDb.otpTokenDao().getAll().first() }
            val allCategories = runBlocking(Dispatchers.IO) { migratedDb.otpTokenDao().getAllCategories().first() }

            assertThat(tokens).hasSize(3)
            assertThat(tokens[0].categories).containsExactly("Work")
            assertThat(tokens[1].categories).containsExactly("Cloud, \"Infra\"")
            assertThat(tokens[2].categories).isEmpty()
            assertThat(allCategories).containsExactly("Cloud, \"Infra\"", "Work").inOrder()
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
