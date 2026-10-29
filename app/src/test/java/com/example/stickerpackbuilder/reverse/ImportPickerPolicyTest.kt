package com.example.stickerpackbuilder.reverse

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportPickerPolicyTest {
    @Test fun anOpenSavedPickerIsNotClosedByItsStillVisibleToggleButton() {
        assertEquals(ImportPickerPolicy.Action.CREATE,ImportPickerPolicy.action(false,true,true,true))
    }
    @Test fun anotherPickerTabIsChangedToSavedBeforeTryingToOpenThePickerAgain() {
        assertEquals(ImportPickerPolicy.Action.SAVED_TAB,ImportPickerPolicy.action(false,false,true,true))
        assertEquals(ImportPickerPolicy.Action.OPEN_PICKER,ImportPickerPolicy.action(false,false,false,true))
    }
    @Test fun creationScreenNeverFallsBackToStickerOrChatControls() {
        assertEquals(ImportPickerPolicy.Action.ALBUM,ImportPickerPolicy.action(true,true,true,true))
        assertEquals(ImportPickerPolicy.Action.WAIT,ImportPickerPolicy.action(false,false,false,false))
    }
}
