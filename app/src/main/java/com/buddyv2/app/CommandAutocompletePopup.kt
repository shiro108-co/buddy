package com.buddyv2.app

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat

/**
 * Floating autocomplete popup for @ command suggestions.
 * Appears above the input field when user types @.
 */
class CommandAutocompletePopup(
    private val context: Context,
    private val anchorView: View,
    private val onCommandSelected: (CommandRegistry.Command, Int, Int) -> Unit,
    private val onDismiss: () -> Unit
) {

    private var popupWindow: PopupWindow? = null
    private var recyclerView: RecyclerView? = null
    private var adapter: CommandAdapter? = null
    private var currentQuery = ""
    private var atPosition = -1
    private var cursorPosition = -1
    private val allCommands = CommandRegistry.allCommands

    private inner class CommandAdapter(
        val commands: List<CommandRegistry.Command>
    ) : RecyclerView.Adapter<CommandAdapter.CommandViewHolder>() {

        inner class CommandViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(R.id.cmdIcon)
            val nameView: TextView = view.findViewById(R.id.cmdName)
            val syntaxView: TextView = view.findViewById(R.id.cmdSyntax)
            val descView: TextView = view.findViewById(R.id.cmdDescription)
            val categoryView: TextView = view.findViewById(R.id.cmdCategory)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommandViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.item_command_suggestion, parent, false
            )
            return CommandViewHolder(view)
        }

        override fun onBindViewHolder(holder: CommandViewHolder, position: Int) {
            val command = commands[position]
            holder.iconView.setImageResource(command.iconRes)
            holder.iconView.setColorFilter(ContextCompat.getColor(context, R.color.primary))
            holder.nameView.text = command.name
            holder.syntaxView.text = command.syntax
            holder.descView.text = command.description
            holder.categoryView.text = command.category.label

            holder.itemView.setOnClickListener {
                onCommandSelected(command, atPosition, cursorPosition)
                dismiss()
            }

            // Staggered animation
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 20f
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150)
                .setStartDelay(position * 30L)
                .start()
        }

        override fun getItemCount() = commands.size
    }

    fun show(atPos: Int, cursorPos: Int, query: String) {
        if (popupWindow?.isShowing == true) {
            updateQuery(query)
            return
        }

        atPosition = atPos
        cursorPosition = cursorPos
        currentQuery = query

        val filteredCommands = CommandRegistry.filterCommands(query)

        // Create RecyclerView
        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = CommandAdapter(filteredCommands).also { adapter = it }
            setBackgroundResource(R.drawable.autocomplete_bg)
        }

        // Create PopupWindow
        popupWindow = PopupWindow(
            recyclerView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable
        ).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.autocomplete_bg))
            setElevation(8f)
            setClippingEnabled(false)
            isOutsideTouchable = true
            setOnDismissListener { onDismiss() }
        }

        // Position popup above anchor view
        anchorView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                anchorView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                positionPopup()
            }
        })

        // Handle anchor view layout changes
        anchorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (popupWindow?.isShowing == true) {
                positionPopup()
            }
        }

        // Show popup
        popupWindow!!.showAtLocation(anchorView, Gravity.NO_GRAVITY, 0, 0)
        positionPopup()
    }

    private fun positionPopup() {
        val anchorRect = Rect()
        anchorView.getGlobalVisibleRect(anchorRect)

        val popupRect = Rect()
        recyclerView?.getDrawingRect(popupRect)

        // Measure the recycler view to get its height
        recyclerView?.measure(
            View.MeasureSpec.makeMeasureSpec(anchorView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = recyclerView?.measuredHeight ?: 0

        // Position above the anchor view
        val x = anchorRect.left
        val y = anchorRect.top - popupHeight - 8 // 8dp gap

        popupWindow?.update(x, y, anchorView.width, popupHeight)
    }

    fun updateQuery(query: String) {
        currentQuery = query
        val filteredCommands = CommandRegistry.filterCommands(query)
        adapter = CommandAdapter(filteredCommands)
        recyclerView?.adapter = adapter

        if (filteredCommands.isEmpty()) {
            dismiss()
        } else {
            positionPopup()
        }
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
        recyclerView = null
        adapter = null
        atPosition = -1
        cursorPosition = -1
        currentQuery = ""
    }

    val isShowing: Boolean
        get() = popupWindow?.isShowing == true

    fun moveSelection(direction: Int) {
        val adapter = this.adapter
        val layoutManager = recyclerView?.layoutManager as? LinearLayoutManager
        if (adapter == null || layoutManager == null) return

        var currentPos = layoutManager.findFirstVisibleItemPosition()
        if (currentPos == RecyclerView.NO_POSITION) currentPos = 0

        val newPos = (currentPos + direction).coerceIn(0, adapter.itemCount - 1)
        layoutManager.scrollToPositionWithOffset(newPos, 0)

        // Update the visible item selection visually
        val viewHolder = recyclerView?.findViewHolderForAdapterPosition(newPos)
        viewHolder?.itemView?.performClick()
    }

    fun selectCurrent() {
        val layoutManager = recyclerView?.layoutManager as? LinearLayoutManager
        val adapter = this.adapter
        if (adapter == null || layoutManager == null) return

        val pos = layoutManager.findFirstVisibleItemPosition()
        if (pos != RecyclerView.NO_POSITION && pos < adapter.itemCount) {
            val command = adapter.commands[pos]
            onCommandSelected(command, atPosition, cursorPosition)
            dismiss()
        }
    }
}