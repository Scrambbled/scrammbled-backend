package io.github.mrcabbagestick.scrambbled.config

import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
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
    private val objectMapper = ObjectMapper()

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

        server.addEventListener("game-specific-event", GameSpecificEvent::class.java) { client, event, ack ->
            val user    = userService.getUser(client.sessionId) ?: return@addEventListener
            val session = sessionService.getSession(user.accessCode) ?: return@addEventListener
            session.game.handleEvent(event, user, user.accessCode, server, ack)
        }

        // ─── CHAT ─────────────────────────────────────────────────────────────────────

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

        // ─── SCRABBLE — PRE-GAME CONFIGURATION ──────────────────────────────────────

        /**
         * configure-game
         *
         * Sent by the host before clicking "Start" to set up the language and game length.
         *
         * Payload:
         * ```json
         * {
         *   "language":            "en" | "pl" | "custom",
         *   "gameLengthMultiplier": 1.0          // optional, default 1.0
         *                                        // 0.5 = short, 1.0 = normal,
         *                                        // 1.5 = long,  2.0 = extended
         * }
         * ```
         *
         * For "en" / "pl": the built-in dictionary is loaded immediately.
         * For "custom":    the host must also upload files via [dictionary-upload]
         *                  and [upload-letter-values] before calling start_game.
         *
         * ACK: `{ status: "ok", language: "..." }` or `{ status: "error", message: "..." }`
         */
        server.addEventListener("configure-game", ConfigureGameEvent::class.java) { client, event, ack ->
            val user = userService.getUser(client.sessionId) ?: run {
                ack.sendAckData(mapOf("status" to "error", "message" to "Not in a session"))
                return@addEventListener
            }
            val session = sessionService.getSession(user.accessCode) ?: run {
                ack.sendAckData(mapOf("status" to "error", "message" to "Session not found"))
                return@addEventListener
            }
            if (session.game.getHost().host?.userId != user.userId) {
                ack.sendAckData(mapOf("status" to "error", "message" to "Only the host can configure the game"))
                return@addEventListener
            }

            val game = session.game

            // Apply game-length multiplier to ScrabbleGame (silently ignored for other games)
            if (game is ScrabbleGame) {
                game.setGameLengthMultiplier(event.gameLengthMultiplier)
            }

            when (val lang = event.language.lowercase()) {
                "en", "pl" -> {
                    val dict = dictionaryService.getGlobalDictionary(lang) ?: run {
                        ack.sendAckData(mapOf("status" to "error", "message" to "Dictionary '$lang' not available on server"))
                        return@addEventListener
                    }
                    if (game is DictionaryAware) {
                        (game as DictionaryAware).setDictionary(dict)
                    }
                    game.sendSysMsg(user.accessCode, server,
                        "Język: ${lang.uppercase()}, mnożnik długości gry: ${event.gameLengthMultiplier}×.")
                }
                "custom" -> {
                    game.sendSysMsg(user.accessCode, server,
                        "Tryb własny – prześlij słownik i plik wartości liter przed startem. " +
                                "Mnożnik długości gry: ${event.gameLengthMultiplier}×.")
                }
                else -> {
                    ack.sendAckData(mapOf("status" to "error", "message" to "Unknown language: $lang"))
                    return@addEventListener
                }
            }

            ack.sendAckData(mapOf("status" to "ok", "language" to event.language,
                "gameLengthMultiplier" to event.gameLengthMultiplier))
        }

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
                ack.sendAckData("You are not connected to any session")
                return@addEventListener
            }
            val session = sessionService.getSession(user.accessCode) ?: run {
                ack.sendAckData("Session timed out or does not exist")
                return@addEventListener
            }

            val customDict = dictionaryService.parseCustomDictionary(
                "custom_${session.accessCode}", ByteArrayInputStream(event.data)
            )
            if (session.game is DictionaryAware) {
                (session.game as DictionaryAware).setDictionary(customDict)
            }
            session.game.sendSysMsg(user.accessCode, server,
                "Host wgrał własny słownik: ${event.filename} (${customDict.wordCount} słów).")

            ack.sendAckData("Dictionary uploaded: ${customDict.wordCount} words loaded")
        }

        /**
         * upload-letter-values
         *
         * Uploads a JSON file with custom letter point values **and** the base letter
         * distribution for the pouch. Only applies to Scrabble sessions.
         *
         * Expected JSON structure:
         * ```json
         * {
         *   "letterValues": {
         *     "A": 1, "B": 3, "C": 3, "D": 2, "E": 1, ...
         *   },
         *   "letterDistribution": {
         *     "A": 9, "B": 2, "C": 2, "D": 4, "E": 12, ...
         *   }
         * }
         * ```
         *
         * Both keys are optional — if one is absent the game keeps its current/default values.
         * The actual pouch counts = distribution * gameLengthMultiplier (set via configure-game).
         *
         * Payload: `{ filename: String, data: ByteArray }`
         * ACK:     String confirmation
         */
        server.addEventListener("upload-letter-values", FileUploadEvent::class.java) { client, event, ack ->
            val user = userService.getUser(client.sessionId) ?: run {
                ack.sendAckData("Not in a session")
                return@addEventListener
            }
            val session = sessionService.getSession(user.accessCode) ?: run {
                ack.sendAckData("Session not found")
                return@addEventListener
            }

            val game = session.game
            if (game !is ScrabbleGame) {
                ack.sendAckData("This game does not support custom letter values")
                return@addEventListener
            }

            runCatching { parseLetterConfig(event.data) }
                .fold(
                    onSuccess = { config ->
                        var summary = mutableListOf<String>()

                        config.letterValues?.let { values ->
                            if (values.isNotEmpty()) {
                                game.setCustomLetterValues(values)
                                summary += "${values.size} letter values"
                            }
                        }
                        config.letterDistribution?.let { dist ->
                            if (dist.isNotEmpty()) {
                                game.setCustomLetterDistribution(dist)
                                summary += "${dist.size} distribution entries"
                            }
                        }

                        if (summary.isEmpty()) {
                            ack.sendAckData("Warning: no valid data found in ${event.filename}")
                            return@fold
                        }

                        val msg = "Loaded from ${event.filename}: ${summary.joinToString(", ")}."
                        session.game.sendSysMsg(user.accessCode, server, "Host wgrał własne wartości liter. $msg")
                        ack.sendAckData(msg)
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

    // ─── HELPERS ─────────────────────────────────────────────────────────────────

    /**
     * Parses the letter-config JSON file.
     *
     * Both top-level keys are optional so the host can upload a file that only
     * overrides values, only overrides distribution, or both at once.
     *
     * JSON keys are single characters (case-insensitive); they are normalised to
     * uppercase Char internally.
     */
    private fun parseLetterConfig(data: ByteArray): LetterConfigJson {
        val raw = objectMapper.readValue(data, RawLetterConfigJson::class.java)

        val values = raw.letterValues
            ?.mapKeys { (k, _) -> k.trim().uppercase().first() }

        val distribution = raw.letterDistribution
            ?.mapKeys { (k, _) -> k.trim().uppercase().first() }

        return LetterConfigJson(letterValues = values, letterDistribution = distribution)
    }
}

// ─── DATA CLASSES ────────────────────────────────────────────────────────────────

class MessageEvent(@JsonProperty("message") val message: String)

data class UserData(@JsonProperty("data") val data: String)

class FileUploadEvent(
    @JsonProperty("filename") val filename: String,
    @JsonProperty("data")     val data: ByteArray
)

/**
 * Payload for the `configure-game` socket event.
 *
 * @property language            "en" | "pl" | "custom"
 * @property gameLengthMultiplier scale factor for letter counts in the pouch.
 *                               1.0 = default (no change), 0.5 = short, 2.0 = extended.
 */
class ConfigureGameEvent(
    @JsonProperty("language")             val language: String,
    @JsonProperty("gameLengthMultiplier") val gameLengthMultiplier: Double = 1.0
)

/** Raw Jackson target for the uploaded JSON file — String keys before normalisation. */
private data class RawLetterConfigJson(
    @JsonProperty("letterValues")       val letterValues: Map<String, Int>? = null,
    @JsonProperty("letterDistribution") val letterDistribution: Map<String, Int>? = null
)

/** Parsed and normalised version of [RawLetterConfigJson] with Char keys. */
data class LetterConfigJson(
    val letterValues: Map<Char, Int>? = null,
    val letterDistribution: Map<Char, Int>? = null
)