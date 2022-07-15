package org.fedorahosted.freeotp.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class TokenTouchCallback(private val lifecycleOwner: LifecycleOwner,
                         private val adapter: TokenListAdapter,
                         private val optTokenDatabase: OtpTokenDatabase)
    : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP
        or ItemTouchHelper.DOWN
        or ItemTouchHelper.LEFT
        or ItemTouchHelper.RIGHT, 0) {

    // List of all Token movements during drag (pair.first = sourceToken.id, pair.second = targetToken.id)
    private val movePairs: MutableList<Pair<Long,Long>> = CopyOnWriteArrayList(mutableListOf())


    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        // Clear movePairs when drag starts
        if(actionState ==  ItemTouchHelper.ACTION_STATE_DRAG){
            movePairs.clear()
        }
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {

        lifecycleOwner.lifecycleScope.launch {
           val sourceToken = adapter.currentList[viewHolder.adapterPosition] ?: return@launch
           val targetToken = adapter.currentList[target.adapterPosition] ?: return@launch

            // Swap source and target token and update the view
           val rearrangedList = ArrayList(adapter.currentList)
           Collections.swap(rearrangedList, viewHolder.adapterPosition, target.adapterPosition)
           adapter.submitList(rearrangedList)
            // Keep track of all token moves
           movePairs.add(Pair(sourceToken.id, targetToken.id))
        }

        return true
    }

    /**
     * Is called after finishing or cancelling a drag/swipe (only called once per drag)
     */
    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        lifecycleOwner.lifecycleScope.launch {
            optTokenDatabase.otpTokenDao().movePairs(movePairs)
        }
        super.clearView(recyclerView, viewHolder)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
    }

}