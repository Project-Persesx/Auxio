/*
 * Copyright (c) 2021 Auxio Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.queue

import android.annotation.SuppressLint
import android.graphics.drawable.LayerDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.MaterialShapeDrawable
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemQueueSongBinding
import org.oxycblt.auxio.list.recycler.PlayingIndicatorAdapter
import org.oxycblt.auxio.list.recycler.SongViewHolder
import org.oxycblt.auxio.list.recycler.SyncListDiffer
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.getDimen
import org.oxycblt.auxio.util.inflater

/**
 * A [RecyclerView.Adapter] that shows an editable list of queue items.
 * @param listener A [Listener] to bind interactions to.
 * @author Alexander Capehart (OxygenCobalt)
 */
class QueueAdapter(private val listener: Listener) : RecyclerView.Adapter<QueueSongViewHolder>() {
    private var differ = SyncListDiffer(this, QueueSongViewHolder.DIFF_CALLBACK)
    // Since PlayingIndicator adapter relies on an item value, we cannot use it for this
    // adapter, as one item can appear at several points in the UI. Use a similar implementation
    // with an index value instead.
    private var currentIndex = 0
    private var isPlaying = false

    override fun getItemCount() = differ.currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        QueueSongViewHolder.new(parent)

    override fun onBindViewHolder(holder: QueueSongViewHolder, position: Int) =
        throw IllegalStateException()

    override fun onBindViewHolder(
        viewHolder: QueueSongViewHolder,
        position: Int,
        payload: List<Any>
    ) {
        if (payload.isEmpty()) {
            viewHolder.bind(differ.currentList[position], listener)
        }

        viewHolder.isFuture = position > currentIndex
        viewHolder.updatePlayingIndicator(position == currentIndex, isPlaying)
    }

    /**
     * Synchronously update the list with new items. This is exceedingly slow for large diffs, so
     * only use it for trivial updates.
     * @param newList The new [Song]s for the adapter to display.
     */
    fun submitList(newList: List<Song>) {
        differ.submitList(newList)
    }

    /**
     * Replace the list with a new list. This is exceedingly slow for large diffs, so only use it
     * for trivial updates.
     * @param newList The new [Song]s for the adapter to display.
     */
    fun replaceList(newList: List<Song>) {
        differ.replaceList(newList)
    }

    /**
     * Set the position of the currently playing item in the queue. This will mark the item as
     * playing and any previous items as played.
     * @param index The position of the currently playing item in the queue.
     * @param isPlaying Whether playback is ongoing or paused.
     */
    fun setPosition(index: Int, isPlaying: Boolean) {
        var updatedIndex = false

        if (index != currentIndex) {
            val lastIndex = currentIndex
            currentIndex = index
            updatedIndex = true

            // Have to update not only the currently playing item, but also all items marked
            // as playing.
            if (currentIndex < lastIndex) {
                notifyItemRangeChanged(0, lastIndex + 1, PAYLOAD_UPDATE_POSITION)
            } else {
                notifyItemRangeChanged(0, currentIndex + 1, PAYLOAD_UPDATE_POSITION)
            }
        }

        if (this.isPlaying != isPlaying) {
            this.isPlaying = isPlaying
            // Don't need to do anything if we've already sent an update from changing the
            // index.
            if (!updatedIndex) {
                notifyItemChanged(index, PAYLOAD_UPDATE_POSITION)
            }
        }
    }

    /** A listener for queue list events. */
    interface Listener {
        /**
         * Called when a [RecyclerView.ViewHolder] in the list as clicked.
         * @param viewHolder The [RecyclerView.ViewHolder] that was clicked.
         */
        fun onClick(viewHolder: RecyclerView.ViewHolder)

        /**
         * Called when the drag handle on a [RecyclerView.ViewHolder] is clicked, requesting that a
         * drag should be started.
         * @param viewHolder The [RecyclerView.ViewHolder] to start dragging.
         */
        fun onPickUp(viewHolder: RecyclerView.ViewHolder)
    }

    companion object {
        private val PAYLOAD_UPDATE_POSITION = Any()
    }
}

/**
 * A [PlayingIndicatorAdapter.ViewHolder] that displays a queue [Song]. Use [new] to create an
 * instance.
 * @author Alexander Capehart (OxygenCobalt)
 */
class QueueSongViewHolder private constructor(private val binding: ItemQueueSongBinding) :
    PlayingIndicatorAdapter.ViewHolder(binding.root) {
    /** The "body" view of this [QueueSongViewHolder] that shows the [Song] information. */
    val bodyView: View
        get() = binding.body

    /** The background view of this [QueueSongViewHolder] that shows the delete icon. */
    val backgroundView: View
        get() = binding.background

    /** The actual background drawable of this [QueueSongViewHolder] that can be manipulated. */
    val backgroundDrawable =
        MaterialShapeDrawable.createWithElevationOverlay(binding.root.context).apply {
            fillColor = binding.context.getAttrColorCompat(R.attr.colorSurface)
            elevation = binding.context.getDimen(R.dimen.elevation_normal) * 5
            alpha = 0
        }

    /** If this queue item is considered "in the future" (i.e has not played yet). */
    var isFuture: Boolean
        get() = binding.songAlbumCover.isEnabled
        set(value) {
            // Don't want to disable clicking, just indicate the body and handle is disabled
            binding.songAlbumCover.isEnabled = value
            binding.songName.isEnabled = value
            binding.songInfo.isEnabled = value
            binding.songDragHandle.isEnabled = value
        }

    init {
        binding.body.background =
            LayerDrawable(
                arrayOf(
                    MaterialShapeDrawable.createWithElevationOverlay(binding.context).apply {
                        fillColor = binding.context.getAttrColorCompat(R.attr.colorSurface)
                        elevation = binding.context.getDimen(R.dimen.elevation_normal)
                    },
                    backgroundDrawable))
    }

    /**
     * Bind new data to this instance.
     * @param song The new [Song] to bind.
     * @param listener A [QueueAdapter.Listener] to bind interactions to.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun bind(song: Song, listener: QueueAdapter.Listener) {
        binding.body.setOnClickListener { listener.onClick(this) }

        binding.songAlbumCover.bind(song)
        binding.songName.text = song.resolveName(binding.context)
        binding.songInfo.text = song.resolveArtistContents(binding.context)
        // TODO: Why is this here?
        binding.background.isInvisible = true

        // Set up the drag handle to start a drag whenever it is touched.
        binding.songDragHandle.setOnTouchListener { _, motionEvent ->
            binding.songDragHandle.performClick()
            if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                listener.onPickUp(this)
                true
            } else false
        }
    }

    override fun updatePlayingIndicator(isActive: Boolean, isPlaying: Boolean) {
        binding.interactBody.isSelected = isActive
        binding.songAlbumCover.isPlaying = isPlaying
    }

    companion object {
        /**
         * Create a new instance.
         * @param parent The parent to inflate this instance from.
         * @return A new instance.
         */
        fun new(parent: View) =
            QueueSongViewHolder(ItemQueueSongBinding.inflate(parent.context.inflater))

        /** A comparator that can be used with DiffUtil. */
        val DIFF_CALLBACK = SongViewHolder.DIFF_CALLBACK
    }
}
