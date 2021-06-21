package org.fedorahosted.freeotp.uitest

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
            child?.performClick()?: throw AssertionError("Cannot find view with id: $id")
        }
    }
}
