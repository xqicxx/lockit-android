package com.lockit.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lockit.data.team.TeamDao
import com.lockit.data.team.TeamEntity
import com.lockit.data.team.TeamMemberEntity
import com.lockit.data.team.SharedCredentialEntity

@Database(
    entities = [
        CredentialEntity::class,
        TeamEntity::class,
        TeamMemberEntity::class,
        SharedCredentialEntity::class,
    ],
    version = 2,
    exportSchema = false
)
abstract class LockitDatabase : RoomDatabase() {

    abstract fun credentialDao(): CredentialDao
    abstract fun teamDao(): TeamDao

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
                )
                    .fallbackToDestructiveMigration()  // Allow version upgrade without migration
                    .build()
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

        /**
         * Get the database file path for sync operations.
         */
        fun getDatabaseFile(context: Context): java.io.File {
            return context.applicationContext.getDatabasePath(DB_NAME)
        }
    }
}
