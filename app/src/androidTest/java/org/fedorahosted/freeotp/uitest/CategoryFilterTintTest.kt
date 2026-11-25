package org.fedorahosted.freeotp.uitest

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.scan.CameraChoice
import org.fedorahosted.freeotp.ui.scan.chooseCameraChoice
import org.fedorahosted.freeotp.uitest.TestWaitUtil.assertCardAtPositionHasText
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTextDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTokenCardCount
import org.fedorahosted.freeotp.uitest.TestWaitUtil.clickCardAtPosition
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class CategoryFilterTintTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

    @Test
    fun testFilterIconTintedAndMultiCategoryFiltering() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.selectedCategory = null
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(
                            id = 0L,
                            ordinal = 0L,
                            categories = listOf("Work", "Cloud"),
                        )
                    )
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_TOTP_TOKEN_2.copy(
                            id = 0L,
                            ordinal = 1L,
                            categories = emptyList(),
                        )
                    )
                }
            }

            // Category labels are not displayed on the main token list card
            composeRule.awaitTokenCardCount(2)
            composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST)
                .onChildren()[0]
                .assert(!hasText("Work • Cloud"))

            // Initially "All categories" is active -> filter icon button is unselected (default tint)
            composeRule.awaitTagDisplayed(UiTestTags.CATEGORY_FILTER_BUTTON)
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsNotSelected()

            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.CATEGORY_FILTER_ALL)
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_ALL).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_UNCATEGORIZED).assertIsNotSelected()

            // Select "Uncategorized" -> filter icon button highlighted as selected and shows Token 2
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_UNCATEGORIZED).performClick()
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsSelected()
            composeRule.awaitTokenCardCount(1)
            composeRule.assertCardAtPositionHasText(0, "microsoft.com")

            // Select "Work" -> Token 1 matches
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.categoryFilterItemTag("Work"))
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_UNCATEGORIZED).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.categoryFilterItemTag("Work")).performClick()
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsSelected()
            composeRule.awaitTokenCardCount(1)
            composeRule.assertCardAtPositionHasText(0, "github.com")

            // Select "Cloud" -> Token 1 also matches its second category
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.categoryFilterItemTag("Cloud"))
            composeRule.onNodeWithTag(UiTestTags.categoryFilterItemTag("Work")).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.categoryFilterItemTag("Cloud")).performClick()
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsSelected()
            composeRule.awaitTokenCardCount(1)
            composeRule.assertCardAtPositionHasText(0, "github.com")

            // Select "All categories" -> restored to unselected (default tint) and shows both tokens
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.CATEGORY_FILTER_ALL)
            composeRule.onNodeWithTag(UiTestTags.categoryFilterItemTag("Cloud")).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_ALL).performClick()
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsNotSelected()
            composeRule.awaitTokenCardCount(2)
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
                            categories = listOf("Work"),
                        )
                    )
                    db.otpTokenDao().insert(
                        TestData.OTP_TOTP_TOKEN_2.copy(
                            id = 2L,
                            ordinal = 1L,
                            categories = listOf("Personal"),
                        )
                    )
                }
                activity.navigateToEditToken(1L)
            }

            // Existing "Work" chip is selected; toggle "Personal" on as well
            composeRule.awaitTagDisplayed(UiTestTags.editCategoryChipTag("Work"))
            composeRule.onNodeWithTag(UiTestTags.editCategoryChipTag("Work")).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.editCategoryChipTag("Personal"))
                .performScrollTo()
                .performClick()

            // Add a brand new category "Cloud" via [+ New category] chip
            composeRule.onNodeWithTag(UiTestTags.ADD_CATEGORY_CHIP)
                .performScrollTo()
                .performClick()
            composeRule.awaitTagDisplayed(UiTestTags.CATEGORY_NAME_INPUT)
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_NAME_INPUT).performTextReplacement("Cloud")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.awaitTagDisplayed(UiTestTags.editCategoryChipTag("Cloud"))
            composeRule.onNodeWithTag(UiTestTags.editCategoryChipTag("Cloud")).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.SAVE_BUTTON).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.TOKEN_LIST)
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
                OtpTokenDatabase.MIGRATION_3_4,
            )
            .build()

        try {
            val tokens = runBlocking(Dispatchers.IO) { migratedDb.otpTokenDao().getAll().first() }
            val allCategories = runBlocking(Dispatchers.IO) { migratedDb.otpTokenDao().getAllCategories().first() }

            assertThat(tokens).hasSize(5)
            assertThat(tokens[0].categories).containsExactly("Work")
            assertThat(tokens[1].categories).containsExactly("Cloud, \"Infra\"")
            assertThat(tokens[2].categories).isEmpty()
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

        val parsedWithNewline = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/Example:alice?secret=JBSW%0AY3DP%09EHPK3PXP&issuer=Example")
        )
        assertThat(parsedWithNewline.label).isEqualTo("alice")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(
                            id = 0L,
                            ordinal = 0L,
                            secret = "0189_CORRUPTED!",
                        )
                    )
                }
            }

            composeRule.awaitTokenCardCount(1)
            composeRule.clickCardAtPosition(0)
            composeRule.awaitTextDisplayed(string(R.string.invalid_token))
        }
    }

    @Test
    fun testCameraSelectorFallbackOrderWhenBackCameraMissing() {
        assertThat(
            chooseCameraChoice(
                hasBackCamera = true,
                hasFrontCamera = true,
                hasAnyCamera = true,
            )
        ).isEqualTo(CameraChoice.BACK)

        assertThat(
            chooseCameraChoice(
                hasBackCamera = false,
                hasFrontCamera = true,
                hasAnyCamera = true,
            )
        ).isEqualTo(CameraChoice.FRONT)

        assertThat(
            chooseCameraChoice(
                hasBackCamera = false,
                hasFrontCamera = false,
                hasAnyCamera = true,
            )
        ).isEqualTo(CameraChoice.ANY)

        assertThat(
            chooseCameraChoice(
                hasBackCamera = false,
                hasFrontCamera = false,
                hasAnyCamera = false,
            )
        ).isEqualTo(CameraChoice.NONE)
    }

    @Test
    fun testEmptyViewDistinguishesNoKeysFromZeroFilterAndSearchMatches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.selectedCategory = null
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.otpTokenDao().deleteAll()
                }
            }

            // 1. Truly empty database with no active search/filter -> shows "No OTP keys installed."
            composeRule.awaitTagDisplayed(UiTestTags.EMPTY_VIEW)
            composeRule.onNodeWithTag(UiTestTags.EMPTY_VIEW).assertTextEquals(string(R.string.no_keys))

            // 2. Insert a categorized token -> list appears
            scenario.onActivity { activity ->
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(
                            id = 0L,
                            ordinal = 0L,
                            categories = listOf("Work"),
                        )
                    )
                }
            }
            composeRule.awaitTokenCardCount(1)
            composeRule.assertCardAtPositionHasText(0, "github.com")

            // 3. Filter by "Uncategorized" (0 matches while tokens exist) -> shows "No matching tokens found."
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.CATEGORY_FILTER_UNCATEGORIZED)
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_UNCATEGORIZED).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.EMPTY_VIEW)
            composeRule.onNodeWithTag(UiTestTags.EMPTY_VIEW)
                .assertTextEquals(string(R.string.no_matching_tokens))

            // 4. Reset filter to "All categories", then search for non-matching query -> "No matching tokens found."
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.CATEGORY_FILTER_ALL)
            composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_ALL).performClick()
            composeRule.awaitTokenCardCount(1)
            composeRule.onNodeWithTag(UiTestTags.SEARCH_BUTTON).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.SEARCH_FIELD)
            composeRule.onNodeWithTag(UiTestTags.SEARCH_FIELD).performTextReplacement("zzz_no_match")
            composeRule.awaitTagDisplayed(UiTestTags.EMPTY_VIEW)
            composeRule.onNodeWithTag(UiTestTags.EMPTY_VIEW)
                .assertTextEquals(string(R.string.no_matching_tokens))

            // 5. Clear search query -> token list reappears; wipe DB -> reverts to "No OTP keys installed."
            composeRule.onNodeWithTag(UiTestTags.SEARCH_CLEAR_BUTTON).performClick()
            composeRule.awaitTokenCardCount(1)
            composeRule.assertCardAtPositionHasText(0, "github.com")
            composeRule.onNodeWithTag(UiTestTags.SEARCH_COLLAPSE_BUTTON).performClick()

            scenario.onActivity { activity ->
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.otpTokenDao().deleteAll()
                }
            }
            composeRule.awaitTagDisplayed(UiTestTags.EMPTY_VIEW)
            composeRule.onNodeWithTag(UiTestTags.EMPTY_VIEW).assertTextEquals(string(R.string.no_keys))
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
                                categories = listOf("Work", "Cloud"),
                            )
                        )
                        activity.otpTokenDatabase.otpTokenDao().insert(
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 0L,
                                ordinal = 1L,
                                categories = emptyList(),
                            )
                        )
                    }
                }
                composeRule.awaitTokenCardCount(2)

                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.categoryFilterItemTag("Work"))
                composeRule.onNodeWithTag(UiTestTags.categoryFilterItemTag("Work")).performClick()
                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsSelected()
                composeRule.awaitTokenCardCount(1)
                composeRule.assertCardAtPositionHasText(0, "github.com")
            }

            // Launch #2: verify "Work" category filter and active toolbar icon + menu radio state are restored on launch
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                composeRule.awaitTokenCardCount(1)
                composeRule.assertCardAtPositionHasText(0, "github.com")
                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsSelected()

                scenario.onActivity { activity ->
                    assertThat(activity.settings.selectedCategory).isEqualTo("Work")
                }

                // Switch to "Uncategorized" and exit
                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.CATEGORY_FILTER_UNCATEGORIZED)
                composeRule.onNodeWithTag(UiTestTags.categoryFilterItemTag("Work")).assertIsSelected()
                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_UNCATEGORIZED).performClick()
                composeRule.awaitTokenCardCount(1)
                composeRule.assertCardAtPositionHasText(0, "microsoft.com")
            }

            // Launch #3: verify "Uncategorized" is restored on launch, then test fallback when saved category disappears
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                composeRule.awaitTokenCardCount(1)
                composeRule.assertCardAtPositionHasText(0, "microsoft.com")
                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsSelected()

                // Select "Work" again, then delete all tokens with "Work" -> filter resets to All categories
                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.categoryFilterItemTag("Work"))
                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_UNCATEGORIZED).assertIsSelected()
                composeRule.onNodeWithTag(UiTestTags.categoryFilterItemTag("Work")).performClick()
                composeRule.awaitTokenCardCount(1)

                scenario.onActivity { activity ->
                    runBlocking(Dispatchers.IO) {
                        val all = activity.otpTokenDatabase.otpTokenDao().getAll().first()
                        val workToken = all.first { "Work" in it.categories }
                        activity.otpTokenDatabase.otpTokenDao().deleteById(workToken.id)
                    }
                }

                composeRule.awaitTokenCardCount(1)
                composeRule.assertCardAtPositionHasText(0, "microsoft.com")
                composeRule.onNodeWithTag(UiTestTags.CATEGORY_FILTER_BUTTON).assertIsNotSelected()
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
