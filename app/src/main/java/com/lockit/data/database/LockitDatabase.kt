package com.lockit.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CredentialEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LockitDatabase : RoomDatabase() {

    abstract fun credentialDao(): CredentialDao

    companion object {
        private const val DB_NAME = "lockit.db"

        @Volatile
        private var INSTANCE: LockitDatabase? = null

        fun getInstance(context: Context): LockitDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LockitDatabase::class.java,
                    DB_NAME
                ).build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Close and reset the database singleton. Call before file-level DB replacement.
         */
        fun closeAndReset(context: Context) {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
