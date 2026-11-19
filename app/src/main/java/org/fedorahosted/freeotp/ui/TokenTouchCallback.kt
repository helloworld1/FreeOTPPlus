package org.fedorahosted.freeotp.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

class TokenTouchCallback(
    private val lifecycleOwner: LifecycleOwner,
    private val adapter: TokenListAdapter,
    private val optTokenDatabase: OtpTokenDatabase
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP
        or ItemTouchHelper.DOWN
        or ItemTouchHelper.LEFT
        or ItemTouchHelper.RIGHT,
    0
) {

    private val movePairs: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            movePairs.clear()
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val fromPos = viewHolder.bindingAdapterPosition
        val toPos = target.bindingAdapterPosition
        val current = adapter.currentList
        if (fromPos == RecyclerView.NO_POSITION ||
            toPos == RecyclerView.NO_POSITION ||
            fromPos !in current.indices ||
            toPos !in current.indices
        ) {
            return false
        }

        val sourceToken = current[fromPos]
        val targetToken = current[toPos]
        val rearrangedList = ArrayList(current)
        Collections.swap(rearrangedList, fromPos, toPos)
        adapter.submitList(rearrangedList)
        movePairs.add(Pair(sourceToken.id, targetToken.id))
        return true
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        val pairsSnapshot = movePairs.toList()
        movePairs.clear()
        if (pairsSnapshot.isNotEmpty()) {
            lifecycleOwner.lifecycleScope.launch {
                optTokenDatabase.otpTokenDao().movePairs(pairsSnapshot)
            }
        }
        super.clearView(recyclerView, viewHolder)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
}
