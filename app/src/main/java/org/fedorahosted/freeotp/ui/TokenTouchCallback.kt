package org.fedorahosted.freeotp.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.OtpTokenDatabase

class TokenTouchCallback(private val lifecycleOwner: LifecycleOwner,
                         val adapter: TokenListAdapter,
                         private val optTokenDatabase: OtpTokenDatabase)
    : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN , 0) {

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        lifecycleOwner.lifecycleScope.launch {
            val sourceToken = adapter.currentList[viewHolder.adapterPosition] ?: return@launch
            val targetToken = adapter.currentList[target.adapterPosition] ?: return@launch
            optTokenDatabase.otpTokenDao().move(sourceToken.id, targetToken.id)

            // optTokenDatabase.otpTokenDao().getAll().collect {
            //     adapter.submitList(it)
            // }
        }

        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
    }
}