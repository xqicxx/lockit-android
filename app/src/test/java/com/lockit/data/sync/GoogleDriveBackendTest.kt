package com.lockit.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveBackendTest {

    @Test
    fun `appDataFolder query omits virtual parent clause`() {
        val query = GoogleDriveBackend.parentScopedQuery(
            GoogleDriveBackend.APP_DATA_FOLDER_ID,
            "name='vault.enc'",
        )

        assertEquals("name='vault.enc'", query)
    }

    @Test
    fun `legacy folder query keeps explicit parent clause`() {
        val query = GoogleDriveBackend.parentScopedQuery(
            "folder-123",
            "name='vault.enc'",
        )

        assertEquals("'folder-123' in parents and name='vault.enc'", query)
    }

    @Test
    fun `signed in account still configures when Drive client is not ready`() {
        assertTrue(GoogleDriveBackend.shouldConfigureDrive(signedIn = true, driveReady = false))
        assertFalse(GoogleDriveBackend.shouldConfigureDrive(signedIn = true, driveReady = true))
        assertFalse(GoogleDriveBackend.shouldConfigureDrive(signedIn = false, driveReady = false))
    }
}
