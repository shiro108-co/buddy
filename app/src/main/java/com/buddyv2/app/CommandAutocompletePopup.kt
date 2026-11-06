package com.buddyv2.app

import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
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
 * Compact with max height, scrollable, smooth animations.
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

    // Compact sizing constants (in dp)
    private val MAX_HEIGHT_DP = 280
    private val ITEM_HEIGHT_DP = 56
    private val GAP_ABOVE_INPUT_DP = 8

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
            holder.itemView.translationY = 10f
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(120)
                .setStartDelay(position * 20L)
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
            // Enable nested scrolling for smooth scrolling
            isNestedScrollingEnabled = true
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
            // Smooth show animation
            setAnimationStyle(android.R.style.Animation_Dialog)
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

        // Get screen dimensions
        val displayMetrics = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(displayMetrics)
        val screenHeight = displayMetrics.heightPixels

        // Measure the recycler view with max height constraint
        val maxHeightPx = dpToPx(MAX_HEIGHT_DP)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(anchorView.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)

        recyclerView?.measure(widthSpec, heightSpec)
        val popupHeight = recyclerView?.measuredHeight ?: 0

        // Position above the anchor view
        val x = anchorRect.left
        var y = anchorRect.top - popupHeight - dpToPx(GAP_ABOVE_INPUT_DP)

        // Ensure popup doesn't go off-screen at top
        if (y < 0) {
            y = dpToPx(GAP_ABOVE_INPUT_DP) // Show below input if not enough space above
        }

        // Ensure popup doesn't go off-screen at bottom (behind keyboard/nav bar)
        val popupBottom = y + popupHeight
        if (popupBottom > screenHeight) {
            y = screenHeight - popupHeight - dpToPx(GAP_ABOVE_INPUT_DP)
            if (y < 0) y = 0
        }

        popupWindow?.update(x, y, anchorView.width, popupHeight)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
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