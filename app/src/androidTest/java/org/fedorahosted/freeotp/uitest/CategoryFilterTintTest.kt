package org.fedorahosted.freeotp.uitest

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
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
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.ui.EditTokenFragment
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.ScanTokenFragment
import org.hamcrest.Matcher
import org.hamcrest.Matchers.not
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class CategoryFilterTintTest {

    private fun awaitViewDisplayed(matcher: Matcher<View>, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e
            } catch (e: AssertionError) {
                lastError = e
            }
            Thread.sleep(50L)
        }
        if (lastError != null) throw lastError
        onView(matcher).check(matches(isDisplayed()))
    }

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
            // Category labels are not displayed on the main token list card
            TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 2)
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
                .check(matches(not(hasDescendant(withText("Work • Cloud")))))

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
            TestWaitUtil.awaitViewMatching(withId(R.id.token_list), hasDescendant(withText("microsoft.com")))

            // Select "Work" -> Token 1 matches
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText("Work")).perform(click())
            TestWaitUtil.awaitViewMatching(withId(R.id.token_list), hasDescendant(withText("github.com")))

            // Select "Cloud" -> Token 1 also matches its second category
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText("Cloud")).perform(click())
            TestWaitUtil.awaitViewMatching(withId(R.id.token_list), hasDescendant(withText("github.com")))

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
                activity.navigateTo(
                    R.id.action_tokenList_to_editToken,
                    EditTokenFragment.args(1L)
                )
            }

            // Existing "Work" chip is checked; toggle "Personal" on as well
            TestWaitUtil.awaitViewMatching(withText("Work"), isChecked())
            onView(withText("Personal")).perform(scrollTo(), click())

            // Add a brand new category "Cloud" via [+ New category] chip
            onView(withId(R.id.add_category_chip)).perform(scrollTo(), click())
            onView(withId(R.id.category_edit_text)).inRoot(isDialog())
                .perform(replaceText("Cloud"), closeSoftKeyboard())
            onView(withText(R.string.add)).inRoot(isDialog()).perform(click())

            onView(withText("Cloud")).check(matches(isChecked()))
            onView(withId(R.id.save)).perform(click())
            awaitViewDisplayed(withId(R.id.token_list))
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
                (3, 3, 'PersonalApp', 'bob', NULL, 'TOTP', 'SHA1', 'JBSWY3DPEHPK3PXP', 6, 0, 30, 'PLAIN_TEXT', NULL),
                (4, 4, 'SyncedHotp', 'carol', NULL, 'HOTP', 'SHA1', 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 6, 42, 30, 'PLAIN_TEXT', NULL),
                (5, 5, 'UnusedLegacyHotp', 'dave', NULL, 'HOTP', 'SHA1', 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 6, -1, 30, 'PLAIN_TEXT', NULL)
                """.trimIndent()
            )
            sqlDb.version = 2
        }

        val migratedDb = Room.databaseBuilder(context, OtpTokenDatabase::class.java, dbName)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(
                OtpTokenDatabase.MIGRATION_1_2,
                OtpTokenDatabase.MIGRATION_2_3,
                OtpTokenDatabase.MIGRATION_3_4
            )
            .build()

        try {
            val tokens = runBlocking(Dispatchers.IO) { migratedDb.otpTokenDao().getAll().first() }
            val allCategories = runBlocking(Dispatchers.IO) { migratedDb.otpTokenDao().getAllCategories().first() }

            assertThat(tokens).hasSize(5)
            assertThat(tokens[0].categories).containsExactly("Work")
            assertThat(tokens[1].categories).containsExactly("Cloud, \"Infra\"")
            assertThat(tokens[2].categories).isEmpty()
            // Active HOTP token preserves exact synced counter (42); unused legacy -1 normalizes to 0
            assertThat(tokens[3].counter).isEqualTo(42L)
            assertThat(tokens[4].counter).isEqualTo(0L)
            assertThat(allCategories).containsExactly("Cloud, \"Infra\"", "Work").inOrder()
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun testInvalidBase32UriRejectedAndCorruptedDbTokenShowsErrorWithoutCrash() {
        assertThrows(IllegalArgumentException::class.java) {
            OtpTokenFactory.createFromUri(
                Uri.parse("otpauth://totp/Example:alice?secret=0189INVALID!&issuer=Example")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OtpTokenFactory.createFromUri(
                Uri.parse("otpauth://totp/Example:alice?secret=========&issuer=Example")
            )
        }

        // Valid secret with embedded newline (%0A) is accepted cleanly
        val parsedWithNewline = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/Example:alice?secret=JBSW%0AY3DP%09EHPK3PXP&issuer=Example")
        )
        assertThat(parsedWithNewline.label).isEqualTo("alice")

        // Pre-existing corrupted token in DB shows invalid_token snackbar instead of crashing when tapped
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(
                            id = 0L,
                            ordinal = 0L,
                            secret = "0189_CORRUPTED!"
                        )
                    )
                }
            }

            awaitViewDisplayed(withId(R.id.token_list))
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
            awaitViewDisplayed(withText(R.string.invalid_token))
        }
    }

    @Test
    fun testCameraSelectorFallbackOrderWhenBackCameraMissing() {
        assertThat(
            ScanTokenFragment.chooseCameraChoice(
                hasBackCamera = true,
                hasFrontCamera = true,
                hasAnyCamera = true
            )
        ).isEqualTo(ScanTokenFragment.CameraChoice.BACK)

        assertThat(
            ScanTokenFragment.chooseCameraChoice(
                hasBackCamera = false,
                hasFrontCamera = true,
                hasAnyCamera = true
            )
        ).isEqualTo(ScanTokenFragment.CameraChoice.FRONT)

        assertThat(
            ScanTokenFragment.chooseCameraChoice(
                hasBackCamera = false,
                hasFrontCamera = false,
                hasAnyCamera = true
            )
        ).isEqualTo(ScanTokenFragment.CameraChoice.ANY)

        assertThat(
            ScanTokenFragment.chooseCameraChoice(
                hasBackCamera = false,
                hasFrontCamera = false,
                hasAnyCamera = false
            )
        ).isEqualTo(ScanTokenFragment.CameraChoice.NONE)
    }

    @Test
    fun testEmptyViewDistinguishesNoKeysFromZeroFilterAndSearchMatches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.otpTokenDao().deleteAll()
                }
            }

            // 1. Truly empty database with no active search/filter -> shows "No OTP keys installed."
            awaitViewDisplayed(withId(R.id.empty_view))
            onView(withId(R.id.empty_view))
                .check(matches(withText(R.string.no_keys)))

            // 2. Insert a categorized token -> list appears
            scenario.onActivity { activity ->
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(
                            id = 0L,
                            ordinal = 0L,
                            categories = listOf("Work")
                        )
                    )
                }
            }
            awaitViewDisplayed(withId(R.id.token_list))
            onView(withId(R.id.token_list))
                .check(matches(hasDescendant(withText("github.com"))))

            // 3. Filter by "Uncategorized" (0 matches while tokens exist) -> shows "No matching tokens found."
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText(R.string.uncategorized)).perform(click())
            awaitViewDisplayed(withId(R.id.empty_view))
            onView(withId(R.id.empty_view))
                .check(matches(withText(R.string.no_matching_tokens)))

            // 4. Reset filter to "All categories", then search for a non-matching query -> shows "No matching tokens found."
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText(R.string.all_categories)).perform(click())
            awaitViewDisplayed(withId(R.id.token_list))
            onView(withId(R.id.search_view)).perform(click())
            onView(withId(R.id.search_view)).perform(SearchViewAction.typeSearchViewText("zzz_no_match"))
            awaitViewDisplayed(withId(R.id.empty_view))
            onView(withId(R.id.empty_view))
                .check(matches(withText(R.string.no_matching_tokens)))

            // 5. Clear search query -> token list reappears; wipe DB -> reverts to "No OTP keys installed."
            onView(withId(R.id.search_view)).perform(SearchViewAction.typeSearchViewText(""), closeSoftKeyboard())
            awaitViewDisplayed(withId(R.id.token_list))
            onView(withId(R.id.token_list))
                .check(matches(hasDescendant(withText("github.com"))))

            scenario.onActivity { activity ->
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.otpTokenDao().deleteAll()
                }
            }
            awaitViewDisplayed(withId(R.id.empty_view))
            onView(withId(R.id.empty_view))
                .check(matches(withText(R.string.no_keys)))
        }
    }

    @Test
    fun testSelectedCategoryPersistedOnExitAndRestoredOnNextLaunch() {
        try {
            // Launch #1: seed tokens in "Work" and uncategorized, select "Work", and exit
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.settings.selectedCategory = null
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
                TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 2)

                onView(withId(R.id.action_filter)).perform(click())
                onView(withText("Work")).perform(click())
                TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 1)
                TestWaitUtil.awaitViewMatching(withId(R.id.token_list), hasDescendant(withText("github.com")))
            }

            // Launch #2: verify "Work" category filter, icon tint, and checked menu item are restored on launch
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 1)
                TestWaitUtil.awaitViewMatching(withId(R.id.token_list), hasDescendant(withText("github.com")))

                scenario.onActivity { activity ->
                    assertThat(activity.settings.selectedCategory).isEqualTo("Work")
                    val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                    val filterItem = toolbar.menu.findItem(R.id.action_filter)
                    val expectedActive = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorPrimary)
                    assertThat(MenuItemCompat.getIconTintList(filterItem)?.defaultColor).isEqualTo(expectedActive)
                }

                // Switch to "Uncategorized" and exit
                onView(withId(R.id.action_filter)).perform(click())
                onView(withText(R.string.uncategorized)).perform(click())
                TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 1)
                TestWaitUtil.awaitViewMatching(withId(R.id.token_list), hasDescendant(withText("microsoft.com")))
            }

            // Launch #3: verify "Uncategorized" is restored on launch, then test fallback when a saved category disappears
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 1)
                TestWaitUtil.awaitViewMatching(withId(R.id.token_list), hasDescendant(withText("microsoft.com")))

                // Select "Work" again, then delete all tokens with "Work" -> filter resets to All categories
                onView(withId(R.id.action_filter)).perform(click())
                onView(withText("Work")).perform(click())
                TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 1)

                scenario.onActivity { activity ->
                    runBlocking(Dispatchers.IO) {
                        val all = activity.otpTokenDatabase.otpTokenDao().getAll().first()
                        val workToken = all.first { "Work" in it.categories }
                        activity.otpTokenDatabase.otpTokenDao().deleteById(workToken.id)
                    }
                }

                TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 1)
                TestWaitUtil.awaitViewMatching(withId(R.id.token_list), hasDescendant(withText("microsoft.com")))
                scenario.onActivity { activity ->
                    assertThat(activity.settings.selectedCategory).isNull()
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            android.preference.PreferenceManager.getDefaultSharedPreferences(getInstrumentation().targetContext)
                .edit()
                .remove("selectedCategory")
                .commit()
        }
    }
}
