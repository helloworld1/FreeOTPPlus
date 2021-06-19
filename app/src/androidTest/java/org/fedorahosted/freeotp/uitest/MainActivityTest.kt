package org.fedorahosted.freeotp.uitest

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import org.fedorahosted.freeotp.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class MainActivityTest {
    @get:Rule
    var activityRule = activityScenarioRule<MainActivity>()

    @Test
    fun test() {
    }
}