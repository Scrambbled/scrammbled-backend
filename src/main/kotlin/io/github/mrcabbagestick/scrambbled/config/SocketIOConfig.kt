package io.github.mrcabbagestick.scrambbled.config

import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.session.SessionService
import io.github.mrcabbagestick.scrambbled.socket.event.GameSpecificEvent
import io.github.mrcabbagestick.scrambbled.socket.listeners.SocketConnectListener
import io.github.mrcabbagestick.scrambbled.socket.listeners.SocketDisconnectListener
import io.github.mrcabbagestick.scrambbled.user.UserService
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.corundumstudio.socketio.Configuration as SocketIOConfiguration

class MessageEvent(@JsonProperty("message") val message: String)
data class UserData(@JsonProperty("data") val data: String)

@Configuration
class SocketIOConfig(
    private val connectListener: SocketConnectListener,
    private val disconnectListener: SocketDisconnectListener,
    private val userService: UserService,
    private val sessionService: SessionService
) {
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
            socketConfig.isReuseAddress = true
        }

        server = SocketIOServer(config)

        server.addConnectListener(connectListener)
        server.addDisconnectListener(disconnectListener)

        server.addEventListener("game-specific-event", GameSpecificEvent::class.java) { client, event, ack ->
            val user = userService.getUser(client.sessionId) ?: return@addEventListener
            val session = sessionService.getSession(user.accessCode) ?: return@addEventListener

            session.game.handleEvent(event, user, user.accessCode, server, ack)
        }

        server.addEventListener("chat message", MessageEvent::class.java) { _, event, ack ->
            ack.sendAckData("You sent: ${event.message}")
        }

        server.addEventListener("user data", UserData::class.java) { client, _, _ ->
            client.sendEvent("user data", "User data:\nSessionId: ${client.sessionId}\nNamespace: ${client.namespace}\nAddress: ${client.remoteAddress}")
        }

        server.start()
        return server
    }

    @PreDestroy
    fun stopSocketServer() {
        println("Stopping SocketIO server")
        server.stop()
    }
}