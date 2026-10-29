package com.example.stickerpackbuilder.reverse

import org.junit.Assert.*
import org.junit.Test

class ImportControlPolicyTest {
    @Test fun aClippedRecentsRowDoesNotHideTheRestOfTheAlbumMenu() {
        assertTrue(ImportControlPolicy.albumListEntry("RSL_94b8feca2c2c",360,1080))
        assertFalse(ImportControlPolicy.albumListEntry("RSL_94b8feca2c2c",48,1080))
        assertFalse(ImportControlPolicy.albumListEntry("RSL_family",360,1080))
    }
    @Test fun anEnteringPickerHeaderIsNotAnOpenAlbumMenu() {
        assertFalse(ImportControlPolicy.albumMenuRow("Recents",360,360,616,1080))
        assertFalse(ImportControlPolicy.albumMenuRow("Recents",760,760,1016,1080))
        assertFalse(ImportControlPolicy.albumMenuRow("Recents",48,48,304,1080))
        assertTrue(ImportControlPolicy.albumMenuRow("Recents",360,48,1080,1080))
        assertTrue(ImportControlPolicy.albumMenuRow("RSL_94b8feca2c2c",360,48,1080,1080))
    }
    @Test fun offscreenOrUnrecognisedAlbumRowsCannotOpenASelection() {
        assertFalse(ImportControlPolicy.albumMenuRow("Recents",360,-30,1080,1080))
        assertFalse(ImportControlPolicy.albumMenuRow("Recents",360,48,1110,1080))
        assertFalse(ImportControlPolicy.albumMenuRow("Family",360,48,1080,1080))
        assertFalse(ImportControlPolicy.albumMenuRow("Recents",0,0,0,0))
    }

    @Test fun aSavedPlusLabelCannotBeMistakenForAnOpenCreationScreen() {
        assertFalse(ImportControlPolicy.creationSurface(listOf(1900),emptyList(),2340,false,true))
        assertTrue(ImportControlPolicy.creationSurface(listOf(141),listOf(99),2340,true,false))
        assertTrue(ImportControlPolicy.creationSurface(listOf(1986),listOf(141),2340,false,false))
        assertFalse(ImportControlPolicy.creationSurface(emptyList(),listOf(171),2340,false,false))
        assertFalse(ImportControlPolicy.creationSurface(listOf(141),listOf(99),2340,false,true))
    }

    @Test fun cameraTileIsSkippedInTheVerifiedAlbum() {
        val point=ImportControlPolicy.fileCell(1080,2340,705,listOf(0,747,358,1105))!!
        assertEquals(537f,point.first,0f)
        assertEquals(926f,point.second,0f)
        assertTrue(point.first>358)
        assertNull(ImportControlPolicy.fileCell(1080,2340,705,listOf(137,884,221,968)))
        assertNull(ImportControlPolicy.fileCell(1080,2340,705,listOf(0,354,358,712)))
    }
    @Test fun olderPickerWithoutCameraUsesItsOnlyFileCell() {
        assertEquals(180f,ImportControlPolicy.fileCell(1080,2340,705,null)!!.first,0f)
        assertNull(ImportControlPolicy.fileCell(0,2340,705,null))
    }

    @Test fun slidingOffscreenControlsMustSettleBeforeAnyGestureIsBuilt() {
        assertFalse(ImportControlPolicy.touchInside(-700,600,-100,700,1080,2340))
        assertFalse(ImportControlPolicy.touchInside(-20,600,500,700,1080,2340))
        assertTrue(ImportControlPolicy.touchInside(48,645,600,705,1080,2340))
    }
    @Test fun animatedEditorCanCreateWithoutStaticEditingToolsButPickerCannot() {
        assertTrue(ImportControlPolicy.createButton("Create sticker",1986,2046,2340,false,false,true))
        assertFalse(ImportControlPolicy.createButton("Create sticker",1986,2046,2340,false,true,true))
        assertFalse(ImportControlPolicy.createButton("Create sticker",141,201,2340,false,false,true))
    }
    @Test fun theSameAlbumNameInTheHeaderCannotBeChosenAsAnAlbumListRow() {
        assertFalse(ImportControlPolicy.albumRow(48,1080))
        assertTrue(ImportControlPolicy.albumRow(360,1080))
    }
    @Test fun creationTitleCannotBeMistakenForTheFinalCreateButton() {
        assertFalse(ImportControlPolicy.createButton("Create sticker",141,201,2340,false,true))
        assertFalse(ImportControlPolicy.createButton("Create sticker",141,201,0,true,false))
        assertFalse(ImportControlPolicy.createButton("Create sticker",1986,2046,2340,false,true))
        assertTrue(ImportControlPolicy.createButton("Create sticker",1986,2046,2340,true,false))
        assertFalse(ImportControlPolicy.createButton("Send",1986,2046,2340,true,false))
    }
}
