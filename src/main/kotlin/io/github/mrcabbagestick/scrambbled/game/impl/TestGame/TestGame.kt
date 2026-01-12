package io.github.mrcabbagestick.scrambbled.game.impl.TestGame

import com.corundumstudio.socketio.AckRequest
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.user.User

class TestGame: GameTemplate(Games.TEST_GAME) {
    override fun onUserJoin(user: User) {
        println("User joined TestGame: ${user.userId}")
    }

    override fun onUserLeft(user: User) {
        println("User left TestGame: ${user.userId}")
    }

    override fun getTypeForEventName(eventName: String): Class<*>? = when(eventName){
        "test_event" -> TestEventData::class.java
        else -> null
    }

    override fun <T> handleEvent(eventName: String, eventData: T, user: User, ack: AckRequest) {
        println("User('${user.userId}') called $eventName with data: $eventData")
    }
}

data class TestEventData(@JsonProperty("message") val message: String)