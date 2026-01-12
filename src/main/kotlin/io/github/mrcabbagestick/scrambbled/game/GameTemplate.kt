package io.github.mrcabbagestick.scrambbled.game

import com.corundumstudio.socketio.AckRequest
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mrcabbagestick.scrambbled.socket.event.GameSpecificEvent
import io.github.mrcabbagestick.scrambbled.user.User
import io.github.mrcabbagestick.tools.catchToNull

abstract class GameTemplate(val game: Games){
    abstract fun onUserJoin(user: User);
    abstract fun onUserLeft(user: User);
    abstract fun getTypeForEventName(eventName: String): Class<*>?
    abstract fun <T> handleEvent(eventName: String, eventData: T, user: User, ack: AckRequest)

    fun handleEvent(event: GameSpecificEvent<*>, user: User, ack: AckRequest){

        val eventType = getTypeForEventName(event.eventName) ?:
            return System.err.println("Event name '${event.eventName}' has no corresponding type in game: '${game.gameId}'")

        val data = catchToNull<Any, IllegalArgumentException> {
            ObjectMapper().convertValue(event.data, eventType)
        } ?:
            return System.err.println("Event '${event.eventName}' cannot be cast to type '$eventType'")

        handleEvent(event.eventName, data, user, ack)
    }
}