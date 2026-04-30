package com.lockit.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleDriveBackendTest {

    @Test
    fun `appDataFolder metadata creates folder under app data space`() {
        val metadata = GoogleDriveBackend.appDataFolderMetadata("lockit-sync")

        assertEquals("lockit-sync", metadata.name)
        assertEquals("application/vnd.google-apps.folder", metadata.mimeType)
        assertEquals(listOf(GoogleDriveBackend.APP_DATA_FOLDER_ID), metadata.parents)
    }
}
