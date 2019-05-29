package org.fedorahosted.freeotp.ui

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.UiLifecycleScope

class TokenTouchCallback(private val adapter: TokenListAdapter,
                         private val tokenPersistence: TokenPersistence,
                         private val uiLifecycleScope: UiLifecycleScope)
    : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN , 0) {

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
        uiLifecycleScope.launch {
            val sourceToken = adapter.currentList[viewHolder.adapterPosition]
            val targetToken = adapter.currentList[target.adapterPosition]
            tokenPersistence.move(sourceToken.id, targetToken.id)
            adapter.submitList(tokenPersistence.getTokens())
        }

        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
    }
}