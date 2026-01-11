package io.github.mrcabbagestick.scrambbled.config

import com.corundumstudio.socketio.SocketIOServer
import com.corundumstudio.socketio.protocol.Packet
import com.corundumstudio.socketio.protocol.PacketType
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.GameSpecificEvent
import io.github.mrcabbagestick.scrambbled.session.SessionRegistry
import io.github.mrcabbagestick.scrambbled.socket.listeners.ConnectListener
import io.github.mrcabbagestick.scrambbled.user.User
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.corundumstudio.socketio.Configuration as SocketIOConfiguration

class MessageEvent(
    @JsonProperty("message") val message: String
)

data class UserData(val data: String)

@Configuration
class SocketIOConfig {
    @Value("\${spring.config.host}")
    lateinit var host: String

    @Value("\${spring.config.socketio.port}")
    lateinit var port: Integer

    private lateinit var server: SocketIOServer

    @Bean
    fun socketIOServer(): SocketIOServer {
        val config = SocketIOConfiguration().apply {
            hostname = host
            port = this@SocketIOConfig.port.toInt()
//            context = "/ws"
        }

        config.socketConfig.apply {
            // Allow socketio server to start on the same port after restart
            isReuseAddress = true
        }

        server = SocketIOServer(config)

        server.addConnectListener(ConnectListener())

        server.addEventListener("game-specific", GameSpecificEvent::class.java){ client, event, ack ->
            ack.sendAckData(event.gameEventName)
        }

        server.addEventListener("chat message", MessageEvent::class.java) {client, event, ack ->
            ack.sendAckData("You send: ${event.message}")
        }

        server.addEventListener("user data", UserData::class.java) {client, event, ack ->
            client.sendEvent("user data", "User data:\nSessionId: ${client.sessionId}\nNamespace: ${client.namespace}\nAddress: ${client.remoteAddress}")
        }

        server.start()

        return server
    }


    @PreDestroy
    fun stopSocketServer(){
        println("Stopping socketIO server")
        server.stop()
    }
}