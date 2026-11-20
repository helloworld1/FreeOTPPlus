package org.fedorahosted.freeotp.uitest

import android.os.Build
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import org.hamcrest.Matcher
import org.hamcrest.Matchers

object RecyclerViewAction {
    fun clickChildViewWithId(id: Int) = object: ViewAction {
        override fun getConstraints(): Matcher<View> {
            return Matchers.any(View::class.java)
        }

        override fun getDescription(): String {
            return "Click on recycler view child"
        }

        override fun perform(uiController: UiController?, view: View?) {
            val child = view?.findViewById<View>(id)
                ?: throw AssertionError("Cannot find view with id: $id")
            child.performClick()
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
                // Android 6.0 PopupWindow runs an internal EpicenterTranslateClipReveal transition
                // not tracked by Espresso; pump the main looper so clipBounds reach 100%.
                uiController?.loopMainThreadForAtLeast(350)
            } else {
                uiController?.loopMainThreadUntilIdle()
            }
        }
    }
}
