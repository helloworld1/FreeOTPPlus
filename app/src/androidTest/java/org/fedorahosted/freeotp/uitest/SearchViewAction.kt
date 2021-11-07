package org.fedorahosted.freeotp.uitest

import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.hamcrest.Matcher
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import org.hamcrest.Matchers.allOf


object SearchViewAction {
    fun typeSearchViewText(text: String) = object: ViewAction {
        override fun getConstraints(): Matcher<View> {
            return allOf(isDisplayed(), isAssignableFrom(SearchView::class.java))
        }

        override fun getDescription(): String {
            return "type in on searchView text"
        }

        override fun perform(uiController: UiController?, view: View?) {
            (view as SearchView).setQuery(text,false);
        }
    }
}