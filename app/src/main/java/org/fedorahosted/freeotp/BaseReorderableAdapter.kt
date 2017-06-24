/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp

import android.content.ClipData
import android.view.DragEvent
import android.view.View
import android.view.View.DragShadowBuilder
import android.view.View.OnDragListener
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.BaseAdapter

abstract class BaseReorderableAdapter : BaseAdapter() {
    private inner class Reference<T>(internal var reference: T)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        if (convertView == null) {
            val type = getItemViewType(position)
            convertView = createView(parent, type)

            convertView.setOnDragListener { dstView, event ->
                val ref = event.localState as Reference<View>
                val srcView = ref.reference

                when (event.action) {
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        srcView.visibility = View.VISIBLE
                        dstView.visibility = View.INVISIBLE

                        move((srcView.getTag(R.id.reorder_key) as Int).toInt(),
                                (dstView.getTag(R.id.reorder_key) as Int).toInt())
                        ref.reference = dstView
                    }

                    DragEvent.ACTION_DRAG_ENDED -> srcView.post { srcView.visibility = View.VISIBLE }
                }

                true
            }

            convertView.setOnLongClickListener { view ->
                // Force a reset of any states
                notifyDataSetChanged()

                // Start the drag on the main loop to allow
                // the above state reset to settle.
                view.post {
                    val data = ClipData.newPlainText("", "")
                    val sb = View.DragShadowBuilder(view)
                    view.startDrag(data, sb, Reference(view), 0)
                }

                true
            }
        }

        convertView.setTag(R.id.reorder_key, Integer.valueOf(position))
        bindView(convertView, position)
        return convertView
    }

    protected abstract fun move(fromPosition: Int, toPosition: Int)

    protected abstract fun bindView(view: View, position: Int)

    protected abstract fun createView(parent: ViewGroup, type: Int): View
}
