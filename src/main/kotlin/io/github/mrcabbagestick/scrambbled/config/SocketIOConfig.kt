package io.github.mrcabbagestick.scrambbled.config

import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.DictionaryAware
import io.github.mrcabbagestick.scrambbled.game.impl.scrabble.ScrabbleGame
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
            maxFramePayloadLength = 40 * 1024 * 1024
            maxHttpContentLength  = 40 * 1024 * 1024
        }

        server = SocketIOServer(config)
        server.addConnectListener(connectListener)
        server.addDisconnectListener(disconnectListener)

        // ─── GAME EVENTS ─────────────────────────────────────────────────────────────
        // All game-specific logic (including Scrabble configuration) is handled inside
        // the game classes themselves via game-specific-event.

        server.addEventListener("game-specific-event", GameSpecificEvent::class.java) { client, event, ack ->
            val user    = userService.getUser(client.sessionId) ?: return@addEventListener
            val session = sessionService.getSession(user.accessCode) ?: return@addEventListener
            session.game.handleEvent(event, user, user.accessCode, server, ack)
        }

        // ─── CHAT ────────────────────────────────────────────────────────────────────

        server.addEventListener("chat-message", MessageEvent::class.java) { client, event, ack ->
            val user    = userService.getUser(client.sessionId) ?: return@addEventListener
            val session = sessionService.getSession(user.accessCode) ?: return@addEventListener
            session.game.sendChat(user.accessCode, server, user, event.message)
            ack.sendAckData("Message sent")
        }

        // ─── UTILITY ─────────────────────────────────────────────────────────────────

        server.addEventListener("all-players", Any::class.java) { client, _, ack ->
            val user    = userService.getUser(client.sessionId) ?: return@addEventListener
            val session = sessionService.getSession(user.accessCode) ?: return@addEventListener
            ack.sendAckData(session.game.getPlayers())
        }

        server.addEventListener("get-host", Any::class.java) { client, _, ack ->
            val user    = userService.getUser(client.sessionId) ?: return@addEventListener
            val session = sessionService.getSession(user.accessCode) ?: return@addEventListener
            ack.sendAckData(session.game.getHost())
        }

        server.addEventListener("user-data", UserData::class.java) { client, _, _ ->
            client.sendEvent("user-data", "SessionId: ${client.sessionId}\nAddress: ${client.remoteAddress}")
        }

        // ─── FILE UPLOADS ────────────────────────────────────────────────────────────
        // File uploads stay here because they transfer raw binary (ByteArray) which
        // can't be cleanly wrapped in the JSON-based game-specific-event envelope.
        // The actual processing is fully delegated to the game instance.

        /**
         * dictionary-upload
         *
         * Uploads a custom word list (one word per line, UTF-8).
         * Works for any game that implements DictionaryAware.
         *
         * Payload: `{ filename: String, data: ByteArray }`
         * ACK:     String confirmation
         */
        server.addEventListener("dictionary-upload", FileUploadEvent::class.java) { client, event, ack ->
            val user = userService.getUser(client.sessionId) ?: run {
                ack.sendAckData("You are not connected to any session"); return@addEventListener
            }
            val session = sessionService.getSession(user.accessCode) ?: run {
                ack.sendAckData("Session timed out or does not exist"); return@addEventListener
            }

            val game = session.game
            if (game !is DictionaryAware) {
                ack.sendAckData("This game does not support custom dictionaries")
                return@addEventListener
            }

            val customDict = dictionaryService.parseCustomDictionary(
                "custom_${session.accessCode}", ByteArrayInputStream(event.data)
            )
            game.setDictionary(customDict)
            session.game.sendSysMsg(user.accessCode, server,
                "Host wgrał własny słownik: ${event.filename} (${customDict.wordCount} słów).")

            ack.sendAckData("Dictionary uploaded: ${customDict.wordCount} words loaded")
        }

        /**
         * upload-letter-values
         *
         * Uploads a JSON file with custom letter point values and/or letter distribution
         * for the pouch. Only applies to Scrabble sessions.
         *
         * Expected JSON (both keys optional):
         * ```json
         * {
         *   "letterValues":       { "A": 1, "B": 3, … },
         *   "letterDistribution": { "A": 9, "B": 2, … }
         * }
         * ```
         *
         * Parsing and application are fully handled by [ScrabbleGame.applyLetterConfig].
         *
         * Payload: `{ filename: String, data: ByteArray }`
         * ACK:     String confirmation
         */
        server.addEventListener("upload-letter-values", FileUploadEvent::class.java) { client, event, ack ->
            val user = userService.getUser(client.sessionId) ?: run {
                ack.sendAckData("Not in a session"); return@addEventListener
            }
            val session = sessionService.getSession(user.accessCode) ?: run {
                ack.sendAckData("Session not found"); return@addEventListener
            }

            val game = session.game
            if (game !is ScrabbleGame) {
                ack.sendAckData("This game does not support custom letter values")
                return@addEventListener
            }

            runCatching { game.applyLetterConfig(event.data) }
                .fold(
                    onSuccess = { summary ->
                        session.game.sendSysMsg(user.accessCode, server,
                            "Host wgrał własne wartości liter: ${event.filename}. $summary.")
                        ack.sendAckData("Loaded from ${event.filename}: $summary")
                    },
                    onFailure = { e ->
                        ack.sendAckData("Error parsing ${event.filename}: ${e.message}")
                    }
                )
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

// ─── DATA CLASSES ────────────────────────────────────────────────────────────────

class MessageEvent(@JsonProperty("message") val message: String)

data class UserData(@JsonProperty("data") val data: String)

class FileUploadEvent(
    @JsonProperty("filename") val filename: String,
    @JsonProperty("data")     val data: ByteArray
)