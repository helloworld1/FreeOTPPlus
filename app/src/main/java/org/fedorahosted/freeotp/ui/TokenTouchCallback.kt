package org.fedorahosted.freeotp.ui

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

class TokenTouchCallback(
    private val lifecycleOwner: LifecycleOwner,
    private val adapter: TokenListAdapter,
    private val otpTokenDatabase: OtpTokenDatabase
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP
        or ItemTouchHelper.DOWN
        or ItemTouchHelper.LEFT
        or ItemTouchHelper.RIGHT,
    0
) {

    private val movePairs: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()
    private var dragWorkingList: MutableList<OtpToken>? = null

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            movePairs.clear()
            dragWorkingList = adapter.currentList.toMutableList()
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val fromPos = viewHolder.bindingAdapterPosition
        val toPos = target.bindingAdapterPosition
        val working = dragWorkingList ?: adapter.currentList.toMutableList().also {
            dragWorkingList = it
        }
        if (fromPos == RecyclerView.NO_POSITION ||
            toPos == RecyclerView.NO_POSITION ||
            fromPos !in working.indices ||
            toPos !in working.indices
        ) {
            return false
        }

        val sourceToken = working[fromPos]
        val targetToken = working[toPos]
        Collections.swap(working, fromPos, toPos)
        adapter.submitList(working.toList())
        movePairs.add(Pair(sourceToken.id, targetToken.id))
        return true
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        dragWorkingList = null
        val pairsSnapshot = movePairs.toList()
        movePairs.clear()
        if (pairsSnapshot.isNotEmpty()) {
            lifecycleOwner.lifecycleScope.launch {
                otpTokenDatabase.otpTokenDao().movePairs(pairsSnapshot)
            }
        }
        super.clearView(recyclerView, viewHolder)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
}
