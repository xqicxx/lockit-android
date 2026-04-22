package com.lockit.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

        // Migration from v1 to v2: add team tables
        private val MIGRATION_1_2 = Migration(1, 2) { db ->
            // Create teams table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS teams (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    teamKeyEncoded TEXT NOT NULL,
                    inviteCode TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    createdBy TEXT NOT NULL,
                    myMemberId TEXT NOT NULL,
                    myRole TEXT NOT NULL
                )
            """)
            // Create team_members table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS team_members (
                    id TEXT PRIMARY KEY NOT NULL,
                    teamId TEXT NOT NULL,
                    name TEXT NOT NULL,
                    role TEXT NOT NULL,
                    joinedAt INTEGER NOT NULL,
                    FOREIGN KEY (teamId) REFERENCES teams(id) ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_team_members_teamId ON team_members(teamId)")
            // Create shared_credentials table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS shared_credentials (
                    id TEXT PRIMARY KEY NOT NULL,
                    credentialId TEXT NOT NULL,
                    teamId TEXT NOT NULL,
                    encryptedValue BLOB NOT NULL,
                    createdBy TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    lastModifiedBy TEXT NOT NULL,
                    lastModifiedAt INTEGER NOT NULL,
                    FOREIGN KEY (teamId) REFERENCES teams(id) ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_credentials_teamId ON shared_credentials(teamId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_credentials_credentialId ON shared_credentials(credentialId)")
        }

        fun getInstance(context: Context): LockitDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LockitDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
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
