package io.github.mrcabbagestick.scrambbled.game

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mrcabbagestick.scrambbled.socket.event.GameSpecificEvent
import io.github.mrcabbagestick.scrambbled.user.PlayerInfoDTO
import io.github.mrcabbagestick.scrambbled.user.User
import io.github.mrcabbagestick.tools.catchToNull

abstract class GameTemplate(val game: Games) {
    protected val players = mutableListOf<User>()
    protected var host: User? = null

    abstract fun onUserJoin(user: User, accessCode: String, server: SocketIOServer)
    abstract fun onUserLeft(user: User, accessCode: String, server: SocketIOServer)
    abstract fun getTypeForEventName(eventName: String): Class<*>?
    abstract fun <T> handleEvent(eventName: String, eventData: T, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest)
    open fun shouldTerminate(): Boolean = true

    fun handleEvent(event: GameSpecificEvent<*>, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        val eventType = getTypeForEventName(event.eventName)
            ?: return System.err.println("Event name '${event.eventName}' has no corresponding type in game: '${game.gameId}'")

        val data = catchToNull<Any, IllegalArgumentException> {
            ObjectMapper().convertValue(event.data, eventType)
        } ?: return System.err.println("Event '${event.eventName}' cannot be cast to type '$eventType'")

        handleEvent(event.eventName, data, user, accessCode, server, ack)
    }

    fun getHost(): HostPayload = HostPayload(host?.let { PlayerInfoDTO(it) })

    fun getPlayers(): AllPlayersPayload = AllPlayersPayload(players.map { PlayerInfoDTO(it) })

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

    // --- DTOs ---

    data class AllPlayersPayload(
        val players: List<PlayerInfoDTO>
    )

    data class HostPayload(
        val host: PlayerInfoDTO?
    )

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