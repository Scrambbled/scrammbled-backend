package io.github.mrcabbagestick.scrambbled.game.impl

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.user.User

class TestGame : GameTemplate(Games.TEST_GAME) {
    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        println("User ${user.userId} joined TestGame in room: $accessCode")
        server.getRoomOperations(accessCode).sendEvent("user_joined", user)
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        println("User ${user.userId} left TestGame in room: $accessCode")
        server.getRoomOperations(accessCode).sendEvent("user_left", user)
    }

    override fun getTypeForEventName(eventName: String): Class<*>? = when (eventName) {
        "test_event" -> TestEventData::class.java
        else -> null
    }

    override fun <T> handleEvent(eventName: String, eventData: T, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        when (eventName) {
            "test_event" -> {
                val data = eventData as TestEventData
                println("User ${user.userId} called '$eventName' with data: $data")
                ack.sendAckData("We done good")

                server.getRoomOperations(accessCode).sendEvent("test_event_broadcast", "Somebody triggered the event!")
            }
        }
    }

    override fun shouldTerminate(): Boolean {
        TODO("Not yet implemented")
    }
}

data class TestEventData(@JsonProperty("message") val message: String)