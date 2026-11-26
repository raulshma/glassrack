package com.raulshma.grack.feature.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6 transfer state math: progress fraction/percent (including the clamp at both ends
 * and the unknown-total case), the 100 MB confirmation threshold boundary, and the upload
 * target path derivation from the browsed directory.
 */
class FileTransferPolicyTest {

    private fun transfer(bytes: Long, total: Long) = ActiveFileTransfer(
        id = "xfer-1",
        direction = FileTransferDirection.DOWNLOAD,
        remotePath = "/srv/file.bin",
        localFileName = "file.bin",
        bytesTransferred = bytes,
        totalBytes = total,
    )

    // --- Progress math -------------------------------------------------------------------------

    @Test
    fun percentTracksTheFractionAcrossTheWholeRange() {
        assertEquals(0, transfer(bytes = 0L, total = 4096L).percent)
        assertEquals(25, transfer(bytes = 1024L, total = 4096L).percent)
        assertEquals(50, transfer(bytes = 2048L, total = 4096L).percent)
        assertEquals(99, transfer(bytes = 4095L, total = 4096L).percent)
        assertEquals(100, transfer(bytes = 4096L, total = 4096L).percent)
    }

    @Test
    fun fractionIsClampedAtBothEndsWhenTheTotalUndercounts() {
        assertEquals(0f, transfer(bytes = 0L, total = 100L).fraction)
        assertEquals(0.25f, transfer(bytes = 25L, total = 100L).fraction)
        assertEquals(1f, transfer(bytes = 150L, total = 100L).fraction)
    }

    @Test
    fun unknownTotalRendersAnIndeterminateIndicator() {
        assertNull(transfer(bytes = 512L, total = -1L).fraction)
        assertNull(transfer(bytes = 512L, total = -1L).percent)
        assertNull(transfer(bytes = 0L, total = 0L).percent)
    }

    // --- Size confirmation threshold -----------------------------------------------------------

    @Test
    fun exactlyOneHundredMBNeedsNoConfirmation() {
        assertEquals(100L * 1024 * 1024, LARGE_TRANSFER_CONFIRM_BYTES)
        assertTrue(!requiresTransferConfirmation(100L * 1024 * 1024))
    }

    @Test
    fun oneByteAboveTheThresholdRequiresConfirmation() {
        assertTrue(requiresTransferConfirmation(100L * 1024 * 1024 + 1))
    }

    @Test
    fun smallFilesAndUnknownSizesTransferWithoutConfirmation() {
        assertTrue(!requiresTransferConfirmation(0L))
        assertTrue(!requiresTransferConfirmation(64L * 1024))
        assertTrue(!requiresTransferConfirmation(-1L))
    }

    // --- Upload target path --------------------------------------------------------------------

    @Test
    fun uploadsLandInTheBrowsedDirectory() {
        assertEquals("/app.iso", remotePathForUpload("/", "app.iso"))
        assertEquals("/srv/app.iso", remotePathForUpload("/srv", "app.iso"))
        assertEquals("/srv/app.iso", remotePathForUpload("/srv/", "app.iso"))
        assertEquals("/srv/nested/app.iso", remotePathForUpload("/srv/nested", "app.iso"))
    }
}
