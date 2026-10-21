package com.gildongmu.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveInfoTest {
    @Test
    fun `parked and waiting in traffic can touch`() {
        assertTrue(DriveInfo(DriveState.PARKED, false, 0, 0f).canSend)
        assertTrue(DriveInfo(DriveState.IDLING, true, 0x50, 0f).canSend)
    }

    @Test
    fun `moving can touch when the vehicle only restricts setup and video`() {
        // measured on the Pleos emulator: moving -> DO required, flags 0x50
        assertTrue(DriveInfo(DriveState.MOVING, true, 0x50, 60f).canSend)
    }

    @Test
    fun `a fully restricted vehicle locks touch at any speed`() {
        assertFalse(DriveInfo(DriveState.MOVING, true, 0x1FF, 60f).canSend)
        assertFalse(DriveInfo(DriveState.IDLING, true, 0x1FF, 0f).canSend)
    }

    @Test
    fun `no vehicle data means no restriction`() {
        assertTrue(DriveInfo(DriveState.UNKNOWN, false, 0, null).canSend)
    }
}
