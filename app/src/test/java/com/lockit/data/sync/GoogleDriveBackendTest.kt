package com.lockit.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveBackendTest {

    @Test
    fun `visible drive folder query keeps explicit parent clause`() {
        val query = GoogleDriveBackend.parentScopedQuery(
            "folder-123",
            "name='vault.enc'",
        )

        assertEquals("'folder-123' in parents and name='vault.enc'", query)
    }

    @Test
    fun `google drive sync uses visible drive space`() {
        assertEquals("drive", GoogleDriveBackend.SYNC_SPACE)
    }

    @Test
    fun `signed in account still configures when Drive client is not ready`() {
        assertTrue(GoogleDriveBackend.shouldConfigureDrive(signedIn = true, driveReady = false))
        assertFalse(GoogleDriveBackend.shouldConfigureDrive(signedIn = true, driveReady = true))
        assertFalse(GoogleDriveBackend.shouldConfigureDrive(signedIn = false, driveReady = false))
    }
}
