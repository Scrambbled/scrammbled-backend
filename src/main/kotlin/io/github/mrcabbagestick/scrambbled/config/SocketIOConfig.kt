package io.github.mrcabbagestick.scrambbled.config

import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.DictionaryAware
import io.github.mrcabbagestick.scrambbled.session.SessionService
import io.github.mrcabbagestick.scrambbled.socket.event.GameSpecificEvent
import io.github.mrcabbagestick.scrambbled.socket.listeners.SocketConnectListener
import io.github.mrcabbagestick.scrambbled.socket.listeners.SocketDisconnectListener
import io.github.mrcabbagestick.scrambbled.tools.dictionary.DictionaryService
import io.github.mrcabbagestick.scrambbled.user.UserService
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import com.corundumstudio.socketio.Configuration as SocketIOConfiguration

class MessageEvent(@JsonProperty("message") val message: String)
data class UserData(@JsonProperty("data") val data: String)

class FileUploadEvent(
    @JsonProperty("filename") val filename: String,
    @JsonProperty("data") val data: ByteArray
)

@Configuration
class SocketIOConfig(
    private val connectListener: SocketConnectListener,
    private val disconnectListener: SocketDisconnectListener,
    private val userService: UserService,
    private val sessionService: SessionService,
    private val dictionaryService: DictionaryService
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

        server.addEventListener("dictionary upload", FileUploadEvent::class.java) { client, event, ack ->
            val user = userService.getUser(client.sessionId)
            if(user == null) {
                ack.sendAckData("You are not connected to any session")
                return@addEventListener
            }

            val session = sessionService.getSession(user.accessCode)
            if(session == null) {
                ack.sendAckData("Session timed out or does not exist")
                return@addEventListener
            }

            val inputStream = ByteArrayInputStream(event.data)
            val customDict = dictionaryService.parseCustomDictionary("custom_${session.accessCode}", inputStream)
            
            if (session.game is DictionaryAware) {
                session.game.setDictionary(customDict)
            }

            server.getRoomOperations(session.accessCode)
                .sendEvent("chat message", "Host uploaded a custom dictionary: ${event.filename} (${customDict.wordCount} słów).")
            ack.sendAckData("Dictionary uploaded successfully")
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