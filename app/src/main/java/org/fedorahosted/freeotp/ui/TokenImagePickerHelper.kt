package org.fedorahosted.freeotp.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.fedorahosted.freeotp.R
import org.liberty.android.freeotp.token_images.TokenImage
import org.liberty.android.freeotp.token_images.displayName
import java.util.Locale

internal object TokenImagePickerHelper {

    fun showSourceChooserDialog(
        context: Context,
        titleRes: Int,
        onBuiltInSelected: (TokenImage) -> Unit,
        onDeviceImageRequested: () -> Unit,
        onDialogChanged: (AlertDialog?) -> Unit
    ): AlertDialog {
        val options = arrayOf(
            context.getString(R.string.choose_builtin_icon),
            context.getString(R.string.choose_image_from_device)
        )
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val picker = showBuiltInIconPickerDialog(context, onBuiltInSelected)
                        onDialogChanged(picker)
                    }
                    1 -> onDeviceImageRequested()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        dialog.show()
        onDialogChanged(dialog)
        return dialog
    }

    fun showBuiltInIconPickerDialog(
        context: Context,
        onSelected: (TokenImage) -> Unit
    ): AlertDialog {
        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_builtin_icon_picker, null)
        val searchInput = contentView.findViewById<TextInputEditText>(R.id.icon_search_input)
        val recyclerView = contentView.findViewById<RecyclerView>(R.id.icon_picker_list)

        val allItems = TokenImage.values()
            .sortedBy { it.displayName().lowercase(Locale.ROOT) }

        var dialogRef: AlertDialog? = null
        val adapter = BuiltInIconAdapter(allItems) { selected ->
            dialogRef?.dismiss()
            onSelected(selected)
        }

        recyclerView.layoutManager = GridLayoutManager(context, 4)
        recyclerView.adapter = adapter

        searchInput.doAfterTextChanged { editable ->
            val query = editable?.toString()?.trim().orEmpty()
            if (query.isEmpty()) {
                adapter.submitList(allItems)
            } else {
                val filtered = allItems.filter { entry ->
                    entry.displayName().contains(query, ignoreCase = true) ||
                        entry.name.contains(query, ignoreCase = true) ||
                        entry.altIssuers.any { it.contains(query, ignoreCase = true) }
                }
                adapter.submitList(filtered)
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.choose_builtin_icon)
            .setView(contentView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialogRef = dialog
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        dialog.show()
        return dialog
    }

    private class BuiltInIconAdapter(
        initialItems: List<TokenImage>,
        private val onClick: (TokenImage) -> Unit
    ) : RecyclerView.Adapter<BuiltInIconAdapter.ViewHolder>() {

        private var items: List<TokenImage> = initialItems

        fun submitList(newItems: List<TokenImage>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_builtin_icon, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.image.setImageResource(item.resource)
            holder.name.text = item.displayName()
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.builtin_icon_image)
            val name: TextView = view.findViewById(R.id.builtin_icon_name)
        }
    }
}
