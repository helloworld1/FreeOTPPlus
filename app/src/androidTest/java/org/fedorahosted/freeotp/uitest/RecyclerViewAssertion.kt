package org.fedorahosted.freeotp.uitest

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.matcher.BoundedMatcher
import com.google.common.truth.Truth.assertThat
import org.hamcrest.Description
import org.hamcrest.Matcher

object RecyclerViewAssertion {
    fun childrenCount(count: Int): ViewAssertion = ViewAssertion { view, noViewFoundException ->
        if (noViewFoundException != null) {
            throw noViewFoundException
        }

        val recyclerView = view as RecyclerView
        assertThat(recyclerView.adapter?.itemCount).isEqualTo(count)
    }

    fun atPosition(position: Int, itemMatcher: Matcher<View>): Matcher<View> =
        object : BoundedMatcher<View, RecyclerView>(RecyclerView::class.java) {
            override fun describeTo(description: Description) {
                description.appendText("has item at position $position: ")
                itemMatcher.describeTo(description)
            }

            override fun matchesSafely(view: RecyclerView): Boolean {
                val viewHolder = view.findViewHolderForAdapterPosition(position) ?: return false
                return itemMatcher.matches(viewHolder.itemView)
            }
        }
}