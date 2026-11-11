package org.fedorahosted.freeotp.uitest

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.ui.AboutActivity
import org.fedorahosted.freeotp.ui.AddActivity
import org.fedorahosted.freeotp.ui.DeleteActivity
import org.fedorahosted.freeotp.ui.EditActivity
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.ShareQrCodeActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class FlagSecureTest {

    @Test
    fun testMainActivitySetsFlagSecure() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFlagSecureSet(activity)
            }
        }
    }

    @Test
    fun testAddActivitySetsFlagSecure() {
        ActivityScenario.launch(AddActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFlagSecureSet(activity)
            }
        }
    }

    @Test
    fun testEditActivitySetsFlagSecure() {
        val tokenId = insertTestToken()
        val intent = Intent(getInstrumentation().targetContext, EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_TOKEN_ID, tokenId)

        ActivityScenario.launch<EditActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFlagSecureSet(activity)
            }
        }
    }

    @Test
    fun testShareQrCodeActivitySetsFlagSecure() {
        val tokenId = insertTestToken()
        val intent = Intent(getInstrumentation().targetContext, ShareQrCodeActivity::class.java)
            .putExtra(ShareQrCodeActivity.EXTRA_TOKEN_ID, tokenId)

        ActivityScenario.launch<ShareQrCodeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFlagSecureSet(activity)
            }
        }
    }

    @Test
    fun testDeleteActivitySetsFlagSecure() {
        val tokenId = insertTestToken()
        val intent = Intent(getInstrumentation().targetContext, DeleteActivity::class.java)
            .putExtra(DeleteActivity.EXTRA_TOKEN_ID, tokenId)

        ActivityScenario.launch<DeleteActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFlagSecureSet(activity)
            }
        }
    }

    @Test
    fun testAboutActivitySetsFlagSecure() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFlagSecureSet(activity)
            }
        }
    }

    private fun insertTestToken(): Long {
        var tokenId = 1L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().insert(TestData.OTP_TOTP_TOKEN_2.copy(id = 1, ordinal = 1))
                    tokenId = 1L
                }
            }
        }
        return tokenId
    }

    private fun assertFlagSecureSet(activity: Activity) {
        val flags = activity.window.attributes.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE)
            .isEqualTo(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
