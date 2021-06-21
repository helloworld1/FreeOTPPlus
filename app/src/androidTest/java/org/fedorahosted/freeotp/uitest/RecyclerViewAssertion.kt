package org.fedorahosted.freeotp.uitest

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.ViewAssertion
import com.google.common.truth.Truth.assertThat

object RecyclerViewAssertion {
    fun childrenCount(count: Int): ViewAssertion = ViewAssertion { view, noViewFoundException ->
        if (noViewFoundException != null) {
            throw noViewFoundException
        }


        val recyclerView = view as RecyclerView
        assertThat(recyclerView.adapter?.itemCount).isEqualTo(count)
    }
}