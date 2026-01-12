package io.github.mrcabbagestick.scrambbled.game

import com.corundumstudio.socketio.AckRequest
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mrcabbagestick.scrambbled.socket.event.GameSpecificEvent
import io.github.mrcabbagestick.scrambbled.user.User

abstract class GameTemplate(val game: Games){
    abstract fun onUserJoin(user: User);
    abstract fun onUserLeft(user: User);
    abstract fun getTypeForEventName(eventName: String): Class<*>?
    abstract fun <T> handleEvent(eventName: String, eventData: T, user: User, ack: AckRequest)

    fun handleEvent(event: GameSpecificEvent<*>, user: User, ack: AckRequest){
        val eventType = getTypeForEventName(event.eventName)

        if(eventType == null){
            System.err.println("Event name '${event.eventName}' has no corresponding type in game: '${game.gameId}'")
            return
        }

        val data = try {
            ObjectMapper().convertValue(event.data, eventType)
        }catch(_: IllegalArgumentException){
            System.err.println("Event '${event.eventName}' cannot be cast to type '$eventType'")
            return
        }

        handleEvent(event.eventName, data, user, ack)
    }
}