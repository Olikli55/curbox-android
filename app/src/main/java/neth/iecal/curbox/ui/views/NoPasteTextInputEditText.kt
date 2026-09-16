package neth.iecal.curbox.ui.views

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.DragEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import com.google.android.material.textfield.TextInputEditText

internal fun isBulkImeInsertion(
    text: CharSequence,
    activeComposition: CharSequence?
): Boolean {
    if (Character.codePointCount(text, 0, text.length) <= 1) return false
    return activeComposition?.toString() != text.toString()
}

/**
 * Text input for challenges that must be completed by typing.
 *
 * Paste is blocked from the regular and selection action menus, keyboard
 * clipboard panels, and drag and drop.
 */
class NoPasteTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    override fun onTextContextMenuItem(id: Int): Boolean {
        return when (id) {
            android.R.id.paste,
            android.R.id.pasteAsPlainText -> false
            else -> super.onTextContextMenuItem(id)
        }
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        return if (event.action == DragEvent.ACTION_DROP) false else super.onDragEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val inputConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33NoPasteInputConnection(inputConnection)
        } else {
            NoPasteInputConnection(inputConnection)
        }
    }

    private fun isBulkImeInsertion(text: CharSequence): Boolean {
        val editable = editableText
        val composingStart = BaseInputConnection.getComposingSpanStart(editable)
        val composingEnd = BaseInputConnection.getComposingSpanEnd(editable)
        val activeComposition = if (composingStart >= 0 && composingEnd > composingStart) {
            editable.subSequence(composingStart, composingEnd)
        } else {
            null
        }
        return isBulkImeInsertion(text, activeComposition)
    }

    private open inner class NoPasteInputConnection(
        target: InputConnection
    ) : InputConnectionWrapper(target, false) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            return if (isBulkImeInsertion(text)) {
                true
            } else {
                super.commitText(text, newCursorPosition)
            }
        }

        override fun performContextMenuAction(id: Int): Boolean {
            return if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                true
            } else {
                super.performContextMenuAction(id)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private inner class Api33NoPasteInputConnection(
        target: InputConnection
    ) : NoPasteInputConnection(target) {

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
            textAttribute: TextAttribute?
        ): Boolean {
            return if (isBulkImeInsertion(text)) {
                true
            } else {
                super.commitText(text, newCursorPosition, textAttribute)
            }
        }
    }

    init {
        // Removes paste from the floating insertion toolbar on supported Android versions.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            customInsertionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(
                    mode: android.view.ActionMode,
                    menu: android.view.Menu
                ) = false

                override fun onPrepareActionMode(
                    mode: android.view.ActionMode,
                    menu: android.view.Menu
                ) = false

                override fun onActionItemClicked(
                    mode: android.view.ActionMode,
                    item: android.view.MenuItem
                ) = false

                override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
            }
        }

        // Prevents autofill services from filling the typing challenge.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }
}
