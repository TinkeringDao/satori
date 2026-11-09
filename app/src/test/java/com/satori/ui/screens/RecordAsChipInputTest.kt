package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recorder's Record as chip: no chip while the recording can't be switched; a tap opens the
 * sheet; over the lock screen, where the grille stops a recording only on a hold, only a hold opens
 * it, so a brush in a pocket can't file the recording under another category.
 */
class RecordAsChipInputTest {

    @Test
    fun `while the recording can't be switched there is no chip to take input`() {
        assertNull(recordAsChipInput(opensRecordAs = false, holdToStop = false))
        assertNull(recordAsChipInput(opensRecordAs = false, holdToStop = true))
    }

    @Test
    fun `a tap opens the sheet, and over the lock screen only a hold does`() {
        assertEquals(ChipInput.TAP, recordAsChipInput(opensRecordAs = true, holdToStop = false))
        assertEquals(ChipInput.HOLD, recordAsChipInput(opensRecordAs = true, holdToStop = true))
    }
}
