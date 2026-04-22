package com.lockit.data.team

import android.content.Context
import com.lockit.data.audit.AuditLogger
import com.lockit.data.database.LockitDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * Manages team operations: create, join, invite.
 */
class TeamManager(
    private val context: Context,
    private val auditLogger: AuditLogger,
) {

    private val teamDao: TeamDao by lazy {
        LockitDatabase.getInstance(context).teamDao()
    }

    /**
     * Create a new team.
     * Generates team key, invite code, and adds this device as admin.
     * @return The created team with invite string for sharing.
     */
    suspend fun createTeam(name: String): Result<Team> = withContext(Dispatchers.IO) {
        try {
            val teamId = UUID.randomUUID().toString()
            val memberId = "${android.os.Build.MODEL}-${UUID.randomUUID().toString().take(8)}"
            val teamKey = TeamCrypto.generateTeamKey()
            val inviteCode = TeamCrypto.generateInviteCode()
            val now = Instant.now()

            val team = Team(
                id = teamId,
                name = name,
                teamKey = teamKey,
                inviteCode = inviteCode,
                members = listOf(
                    TeamMember(
                        id = memberId,
                        name = android.os.Build.MODEL,
                        role = TeamRole.ADMIN,
                        joinedAt = now,
                    )
                ),
                createdAt = now,
                createdBy = memberId,
            )

            // Store in database
            val entity = TeamEntity.fromTeam(team, memberId)
            teamDao.insertTeam(entity)

            // Audit log
            auditLogger.logTeamCreated(teamId, name)

            Result.success(team)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Join a team using invite string.
     * Invite string format: base64(teamKey):inviteCode
     */
    suspend fun joinTeam(inviteString: String): Result<Team> = withContext(Dispatchers.IO) {
        try {
            val decoded = TeamCrypto.decodeTeamInvite(inviteString)
            if (decoded == null) {
                return@withContext Result.failure(IllegalArgumentException("Invalid invite string"))
            }

            val (teamKey, inviteCode) = decoded
            val memberId = "${android.os.Build.MODEL}-${UUID.randomUUID().toString().take(8)}"
            val now = Instant.now()

            // Generate a team ID from invite code hash (all members use same ID)
            val teamId = UUID.nameUUIDFromBytes(inviteCode.toByteArray()).toString()

            // Check if already joined this team
            val existing = teamDao.getTeamById(teamId)
            if (existing != null) {
                return@withContext Result.failure(IllegalStateException("Already a member of this team"))
            }

            val team = Team(
                id = teamId,
                name = "Team",  // Name can be updated later
                teamKey = teamKey,
                inviteCode = inviteCode,
                members = listOf(
                    TeamMember(
                        id = memberId,
                        name = android.os.Build.MODEL,
                        role = TeamRole.MEMBER,  // New joiners are members
                        joinedAt = now,
                    )
                ),
                createdAt = now,
                createdBy = memberId,  // Will be updated when syncing with team admin
            )

            val entity = TeamEntity.fromTeam(team, memberId)
            teamDao.insertTeam(entity)

            auditLogger.logTeamJoined(teamId)

            Result.success(team)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get all teams this device belongs to.
     */
    fun getAllTeams() = teamDao.getAllTeams()

    /**
     * Get a specific team by ID.
     */
    suspend fun getTeamById(teamId: String): Team? = withContext(Dispatchers.IO) {
        teamDao.getTeamById(teamId)?.toTeam()
    }

    /**
     * Leave a team (delete local membership).
     */
    suspend fun leaveTeam(teamId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            teamDao.clearAllTeamData(teamId)
            auditLogger.logTeamLeft(teamId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate QR code content for team invite.
     * Format: lockit-team://join?invite=<encodedInvite>
     */
    fun generateInviteQrContent(team: Team): String {
        val encodedInvite = team.encodeInvite()
        return "lockit-team://join?invite=$encodedInvite"
    }

    /**
     * Parse QR code content to get invite string.
     */
    fun parseInviteQrContent(qrContent: String): String? {
        val prefix = "lockit-team://join?invite="
        return if (qrContent.startsWith(prefix)) {
            qrContent.removePrefix(prefix)
        } else {
            null
        }
    }
}

/**
 * Audit action names for team operations.
 */
fun AuditLogger.logTeamCreated(teamId: String, teamName: String) {
    log("TEAM_CREATED", "teamId=$teamId teamName=$teamName")
}

fun AuditLogger.logTeamJoined(teamId: String) {
    log("TEAM_JOINED", "teamId=$teamId")
}

fun AuditLogger.logTeamLeft(teamId: String) {
    log("TEAM_LEFT", "teamId=$teamId")
}