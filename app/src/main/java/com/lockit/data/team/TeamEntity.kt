package com.lockit.data.team

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.Instant

/**
 * Room entity for Team.
 */
@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey
    val id: String,              // UUID
    val name: String,            // Team name
    val teamKeyEncoded: String,  // Base64 encoded AES-256 key
    val inviteCode: String,      // Invite code for joining
    val createdAt: Long,         // Epoch millis
    val createdBy: String,       // Creator device/member ID
    val myMemberId: String,      // This device's member ID in the team
    val myRole: String,          // ADMIN, MEMBER, VIEWER
) {
    fun toTeam(): Team {
        val teamKey = java.util.Base64.getUrlDecoder().decode(teamKeyEncoded)
        val role = TeamRole.valueOf(myRole)
        val member = TeamMember(
            id = myMemberId,
            name = android.os.Build.MODEL,
            role = role,
            joinedAt = Instant.ofEpochMilli(createdAt),
        )
        return Team(
            id = id,
            name = name,
            teamKey = teamKey,
            inviteCode = inviteCode,
            members = listOf(member),  // Only local member stored
            createdAt = Instant.ofEpochMilli(createdAt),
            createdBy = createdBy,
        )
    }

    companion object {
        fun fromTeam(team: Team, myMemberId: String): TeamEntity {
            val keyEncoded = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(team.teamKey)
            val myMember = team.members.find { it.id == myMemberId }
            return TeamEntity(
                id = team.id,
                name = team.name,
                teamKeyEncoded = keyEncoded,
                inviteCode = team.inviteCode,
                createdAt = team.createdAt.toEpochMilli(),
                createdBy = team.createdBy,
                myMemberId = myMemberId,
                myRole = myMember?.role?.name ?: TeamRole.MEMBER.name,
            )
        }
    }
}

/**
 * Room entity for member list (stored separately for sync).
 */
@Entity(
    tableName = "team_members",
    foreignKeys = [
        ForeignKey(
            entity = TeamEntity::class,
            parentColumns = ["id"],
            childColumns = ["teamId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("teamId")]
)
data class TeamMemberEntity(
    @PrimaryKey
    val id: String,              // Member UUID
    val teamId: String,          // Team ID
    val name: String,            // Display name
    val role: String,            // ADMIN, MEMBER, VIEWER
    val joinedAt: Long,          // Epoch millis
)

/**
 * Room entity for shared credential reference.
 */
@Entity(
    tableName = "shared_credentials",
    foreignKeys = [
        ForeignKey(
            entity = TeamEntity::class,
            parentColumns = ["id"],
            childColumns = ["teamId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("teamId"), Index("credentialId")]
)
data class SharedCredentialEntity(
    @PrimaryKey
    val id: String,              // UUID
    val credentialId: String,    // Original credential ID (for reference)
    val teamId: String,          // Team ID
    val encryptedValue: ByteArray, // Encrypted with team key
    val createdBy: String,       // Member ID who shared
    val createdAt: Long,         // Epoch millis
    val lastModifiedBy: String,
    val lastModifiedAt: Long,
)