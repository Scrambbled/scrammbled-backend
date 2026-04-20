package io.github.mrcabbagestick.scrambbled.game

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mrcabbagestick.scrambbled.config.ServerMessagePayload
import io.github.mrcabbagestick.scrambbled.socket.event.GameSpecificEvent
import io.github.mrcabbagestick.scrambbled.user.User
import io.github.mrcabbagestick.tools.catchToNull
import java.util.UUID

abstract class GameTemplate(val game: Games) {
    protected val players = mutableListOf<User>();

    abstract fun onUserJoin(user: User, accessCode: String, server: SocketIOServer)
    abstract fun onUserLeft(user: User, accessCode: String, server: SocketIOServer)
    abstract fun getTypeForEventName(eventName: String): Class<*>?
    abstract fun <T> handleEvent(eventName: String, eventData: T, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest)
    public open fun shouldTerminate(): Boolean = true

    fun

    fun handleEvent(event: GameSpecificEvent<*>, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        val eventType = getTypeForEventName(event.eventName)
            ?: return System.err.println("Event name '${event.eventName}' has no corresponding type in game: '${game.gameId}'")

        val data = catchToNull<Any, IllegalArgumentException> {
            ObjectMapper().convertValue(event.data, eventType)
        } ?: return System.err.println("Event '${event.eventName}' cannot be cast to type '$eventType'")

        handleEvent(event.eventName, data, user, accessCode, server, ack)
    }

    protected fun sendSysMsg(accessCode: String, server: SocketIOServer, msg: String) {
        server.getRoomOperations(accessCode).sendEvent("server message", ServerMessagePayload(msg))
    }

}