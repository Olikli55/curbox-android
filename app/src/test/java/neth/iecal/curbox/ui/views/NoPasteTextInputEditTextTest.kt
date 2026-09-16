package neth.iecal.curbox.ui.views

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoPasteTextInputEditTextTest {

    @Test
    fun bulkImeInsertionWithoutCompositionIsBlocked() {
        assertTrue(isBulkImeInsertion("copied sentence", null))
    }

    @Test
    fun committingActiveKeyboardCompositionIsAllowed() {
        assertFalse(isBulkImeInsertion("sentence", "sentence"))
    }

    @Test
    fun singleTypedCharacterIsAllowed() {
        assertFalse(isBulkImeInsertion("a", null))
    }
}
