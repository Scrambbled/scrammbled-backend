package io.github.mrcabbagestick.scrambbled.game

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mrcabbagestick.scrambbled.socket.event.GameSpecificEvent
import io.github.mrcabbagestick.scrambbled.user.PlayerInfoDTO
import io.github.mrcabbagestick.scrambbled.user.User
import io.github.mrcabbagestick.tools.catchToNull
import java.util.UUID

enum class PlayerRole { PLAYER, OBSERVER }

abstract class GameTemplate(val game: Games) {

    /** Active players participating in the game (affect scoring, turn order, etc.). */
    protected val players = mutableListOf<User>()

    /** Every connected socket — players and observers alike. Preserves join order. */
    private val connectedMembers = LinkedHashMap<UUID, ConnectedMember>()

    protected var host: User? = null

    // ─── ABSTRACT CONTRACT ────────────────────────────────────────────────────

    /**
     * Called when a user connects to the room.
     * The implementation must:
     * 1. Decide whether the user is a [PlayerRole.PLAYER] or [PlayerRole.OBSERVER].
     * 2. Call [trackAndBroadcastJoin] with the decided role — this sends
     *    `player_joined` to all and `room_state` to the newcomer.
     * 3. Do any game-specific setup.
     */
    abstract fun onUserJoin(user: User, accessCode: String, server: SocketIOServer)

    /**
     * Called when a user disconnects.
     * The implementation must call [trackAndBroadcastLeave] so the frontend
     * receives `player_left`.
     */
    abstract fun onUserLeft(user: User, accessCode: String, server: SocketIOServer)

    abstract fun getTypeForEventName(eventName: String): Class<*>?
    abstract fun <T> handleEvent(eventName: String, eventData: T, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest)
    open fun shouldTerminate(): Boolean = true

    fun handleEvent(event: GameSpecificEvent<*>, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        val eventType = getTypeForEventName(event.eventName)
            ?: return System.err.println("Event '${event.eventName}' has no type mapping in game '${game.gameId}'")

        val data = catchToNull<Any, IllegalArgumentException> {
            ObjectMapper().convertValue(event.data, eventType)
        } ?: return System.err.println("Event '${event.eventName}' cannot be cast to '$eventType'")

        handleEvent(event.eventName, data, user, accessCode, server, ack)
    }

    // ─── ROOM STATE HELPERS ──────────────────────────────────────────────────
    // Call these from onUserJoin / onUserLeft in every game implementation.

    /**
     * Registers the user in [connectedMembers], then:
     * - Broadcasts `player_joined` to the whole room (including the newcomer)
     *   so every client can update its player list.
     * - Sends `room_state` exclusively to the newcomer so they can populate
     *   the full list of members already present.
     *
     * Must be called from [onUserJoin] after host and [players] are updated.
     */
    protected fun trackAndBroadcastJoin(
        user: User, role: PlayerRole,
        accessCode: String, server: SocketIOServer
    ) {
        connectedMembers[user.userId] = ConnectedMember(user, role)

        broadcastEvent(accessCode, server, "player_joined", PlayerJoinedPayload(
            player = PlayerInfoDTO(user),
            role   = role.name.lowercase(),
            hostId = host?.userId
        ))

        // Only the newcomer needs the full snapshot — everyone else already has it.
        sendToUser(user, server, "room_state", buildRoomState())
    }

    /**
     * Removes the user from [connectedMembers] and broadcasts `player_left`
     * to the whole room.
     *
     * Must be called from [onUserLeft] after host and [players] are updated,
     * so that [buildRoomState]-related fields already reflect the post-leave state.
     */
    protected fun trackAndBroadcastLeave(user: User, accessCode: String, server: SocketIOServer) {
        connectedMembers.remove(user.userId)
        broadcastEvent(accessCode, server, "player_left", PlayerLeftPayload(PlayerInfoDTO(user)))
    }

    private fun buildRoomState() = RoomStatePayload(
        gameId   = game.gameId,
        gameName = game.name,         // enum name, e.g. "SCRABBLE_GAME"
        members  = connectedMembers.values.map { RoomMemberDTO(PlayerInfoDTO(it.user), it.role.name.lowercase()) },
        hostId   = host?.userId
    )

    // ─── ACK QUERIES ─────────────────────────────────────────────────────────

    /** ACK response for the `get-host` socket event. */
    fun getHost(): HostPayload = HostPayload(host?.let { PlayerInfoDTO(it) })

    /** ACK response for the `all-players` socket event. */
    fun getPlayers(): AllPlayersPayload = AllPlayersPayload(players.map { PlayerInfoDTO(it) })

    // ─── BROADCAST / SEND HELPERS ────────────────────────────────────────────

    protected fun broadcastEvent(accessCode: String, server: SocketIOServer, eventName: String, payload: Any) =
        server.getRoomOperations(accessCode).sendEvent(eventName, payload)

    protected fun sendToUser(user: User, server: SocketIOServer, eventName: String, payload: Any) =
        server.getClient(user.userId)?.sendEvent(eventName, payload)

    protected fun sendToGroup(users: Collection<User>, server: SocketIOServer, eventName: String, payload: Any) =
        users.forEach { server.getClient(it.userId)?.sendEvent(eventName, payload) }

    fun sendSysMsg(accessCode: String, server: SocketIOServer, msg: String) =
        broadcastEvent(accessCode, server, "server-message", ServerMessagePayload(msg))

    fun sendChat(accessCode: String, server: SocketIOServer, user: User, msg: String) =
        broadcastEvent(accessCode, server, "chat-message", ChatBroadcastPayload(PlayerInfoDTO(user), msg))

    // ─── DTOs ────────────────────────────────────────────────────────────────

    /** Internal tracking model — never sent to the frontend directly. */
    data class ConnectedMember(val user: User, val role: PlayerRole)

    // --- Room state ---

    /**
     * Broadcast to ALL when anyone joins.
     * Includes [hostId] so the frontend can mark the host without a separate request.
     */
    data class PlayerJoinedPayload(
        val player: PlayerInfoDTO,
        val role: String,          // "player" | "observer"
        val hostId: UUID?
    )

    /** Broadcast to ALL when anyone leaves. */
    data class PlayerLeftPayload(
        val player: PlayerInfoDTO
    )

    /**
     * Sent exclusively to the newcomer so they can populate the waiting-room / lobby list.
     * Contains the full snapshot of everyone already present.
     */
    data class RoomStatePayload(
        val gameId: String,            // e.g. "scrabble_game", "rps_game"
        val gameName: String,          // enum name, e.g. "SCRABBLE_GAME"
        val members: List<RoomMemberDTO>,
        val hostId: UUID?
    )

    data class RoomMemberDTO(
        val player: PlayerInfoDTO,
        val role: String           // "player" | "observer"
    )

    // --- ACK payloads ---

    data class AllPlayersPayload(val players: List<PlayerInfoDTO>)
    data class HostPayload(val host: PlayerInfoDTO?)

    // --- Chat / system messages ---

    data class ChatBroadcastPayload(
        @JsonProperty("sender")    val sender: PlayerInfoDTO,
        @JsonProperty("message")   val message: String,
        @JsonProperty("timestamp") val timestamp: Long = System.currentTimeMillis()
    )

    data class ServerMessagePayload(
        @JsonProperty("message")   val message: String,
        @JsonProperty("timestamp") val timestamp: Long = System.currentTimeMillis()
    )
}