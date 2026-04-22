package com.lockit.data.team

import java.time.Instant

/**
 * Team member role.
 */
enum class TeamRole {
    ADMIN,      // Can manage team + edit credentials
    MEMBER,     // Can edit shared credentials
    VIEWER,     // Can only view shared credentials
}

/**
 * Team member.
 */
data class TeamMember(
    val id: String,          // UUID
    val name: String,        // Display name (device name or user name)
    val role: TeamRole,
    val joinedAt: Instant,
)

/**
 * Team - a group sharing credentials.
 */
data class Team(
    val id: String,          // UUID
    val name: String,        // Team name
    val teamKey: ByteArray,  // AES-256 shared key
    val inviteCode: String,  // For new members to join
    val members: List<TeamMember>,
    val createdAt: Instant,
    val createdBy: String,   // Member ID of creator
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Team) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    /**
     * Check if user is admin of this team.
     */
    fun isAdmin(memberId: String): Boolean {
        return members.find { it.id == memberId }?.role == TeamRole.ADMIN
    }

    /**
     * Encode team invite for sharing (QR code or link).
     */
    fun encodeInvite(): String {
        return TeamCrypto.encodeTeamInvite(teamKey, inviteCode)
    }
}

/**
 * Shared credential reference (stored in team database).
 */
data class SharedCredential(
    val id: String,               // UUID
    val credentialId: String,     // Original credential ID
    val teamId: String,           // Team ID
    val encryptedValue: ByteArray, // Encrypted with team key
    val createdBy: String,        // Member ID
    val createdAt: Instant,
    val lastModifiedBy: String,
    val lastModifiedAt: Instant,
)