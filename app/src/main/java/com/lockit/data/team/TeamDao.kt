package com.lockit.data.team

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data access for teams and shared credentials.
 */
@Dao
interface TeamDao {

    // === Teams ===

    @Query("SELECT * FROM teams ORDER BY createdAt DESC")
    fun getAllTeams(): Flow<List<TeamEntity>>

    @Query("SELECT * FROM teams WHERE id = :teamId")
    suspend fun getTeamById(teamId: String): TeamEntity?

    @Query("SELECT COUNT(*) FROM teams")
    suspend fun getTeamCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTeam(team: TeamEntity)

    @Delete
    suspend fun deleteTeam(team: TeamEntity)

    @Query("DELETE FROM teams WHERE id = :teamId")
    suspend fun deleteTeamById(teamId: String)

    // === Members ===

    @Query("SELECT * FROM team_members WHERE teamId = :teamId ORDER BY joinedAt ASC")
    suspend fun getMembers(teamId: String): List<TeamMemberEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMember(member: TeamMemberEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembers(members: List<TeamMemberEntity>)

    @Query("DELETE FROM team_members WHERE teamId = :teamId AND id = :memberId")
    suspend fun deleteMember(teamId: String, memberId: String)

    @Query("DELETE FROM team_members WHERE teamId = :teamId")
    suspend fun clearMembers(teamId: String)

    // === Shared Credentials ===

    @Query("SELECT * FROM shared_credentials WHERE teamId = :teamId ORDER BY createdAt DESC")
    fun getSharedCredentials(teamId: String): Flow<List<SharedCredentialEntity>>

    @Query("SELECT * FROM shared_credentials WHERE id = :id")
    suspend fun getSharedCredentialById(id: String): SharedCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSharedCredential(cred: SharedCredentialEntity)

    @Delete
    suspend fun deleteSharedCredential(cred: SharedCredentialEntity)

    @Query("DELETE FROM shared_credentials WHERE teamId = :teamId AND credentialId = :credentialId")
    suspend fun deleteSharedCredentialByOriginalId(teamId: String, credentialId: String)

    @Query("DELETE FROM shared_credentials WHERE teamId = :teamId")
    suspend fun deleteAllSharedCredentials(teamId: String)

    // === Clear all team data ===

    @Transaction
    suspend fun clearAllTeamData(teamId: String) {
        deleteAllSharedCredentials(teamId)
        clearMembers(teamId)
        deleteTeamById(teamId)
    }

    // === Transaction wrappers for atomic operations ===

    @Transaction
    suspend fun insertTeamWithMember(team: TeamEntity, member: TeamMemberEntity) {
        insertTeam(team)
        insertMember(member)
    }
}