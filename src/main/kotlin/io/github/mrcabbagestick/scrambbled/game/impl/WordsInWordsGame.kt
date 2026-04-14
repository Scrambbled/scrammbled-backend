package io.github.mrcabbagestick.scrambbled.game.impl

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.config.ServerMessagePayload
import io.github.mrcabbagestick.scrambbled.game.DictionaryAware
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.tools.dictionary.WordDictionary
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

class WordsInWordsGame : GameTemplate(Games.WORDS_IN_WORDS), DictionaryAware {

    private val players = mutableListOf<UUID>()
    private val nicknames = mutableMapOf<UUID, String>()

    private val roundScores = mutableMapOf<UUID, Int>()
    private val gameScores = mutableMapOf<UUID, Int>()
    private val usedWords = mutableSetOf<String>()

    private var isGameStarted = false
    private var gameMode: String = "sentence" // "sentence" lub "random"
    private var currentPool: String = ""      // Zastępuje baseSentence

    private var currentRound = 1
    private var maxRounds = 3
    private var currentPlayerIndex = 0
    private var consecutivePasses = 0

    private var activeDictionary: WordDictionary? = null

    override fun setDictionary(dictionary: WordDictionary?) {
        this.activeDictionary = dictionary
    }

    private val sentencePool = listOf(
        "Bread remembers", "We are not electric", "Teachers do not cook",
        "Doctors have a rocket", "Cat talks", "Robot comes",
        "Scientists show", "Cities have solutions", "Robot is futuristic",
        "Dolphin has problems", "Computer calculates slowly"
    )

    private fun sendSysMsg(accessCode: String, server: SocketIOServer, msg: String) {
        server.getRoomOperations(accessCode).sendEvent("server message", ServerMessagePayload(msg))
    }

    // Prosty generator upewniający się, że w puli są samogłoski i spółgłoski
    private fun generateRandomLetters(): String {
        val vowels = listOf('A', 'E', 'I', 'O', 'U', 'Y')
        val consonants = ('A'..'Z').filter { it !in vowels }

        // 4 samogłoski i 8 spółgłosek (łącznie 12 liter)
        val pool = (1..4).map { vowels.random() } + (1..8).map { consonants.random() }
        return pool.shuffled().joinToString(" ")
    }

    private fun refreshPool() {
        currentPool = if (gameMode == "random") {
            generateRandomLetters()
        } else {
            sentencePool.random()
        }
    }

    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        nicknames[user.userId] = user.nickname

        if (!isGameStarted && !players.contains(user.userId)) {
            players.add(user.userId)
            sendSysMsg(accessCode, server, "Gracz ${user.nickname} dołączył (Gracz ${players.size}).")
        } else {
            sendSysMsg(accessCode, server, "Gracz ${user.nickname} dołączył jako Obserwator.")
            if (isGameStarted) {
                val payload = GameSyncPayload(currentPool, currentRound, maxRounds, players[currentPlayerIndex], roundScores, gameScores)
                server.getClient(user.userId)?.sendEvent("game_sync", payload)
            }
        }
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        val wasActiveTurn = isGameStarted && players.isNotEmpty() && players[currentPlayerIndex] == user.userId
        players.remove(user.userId)

        sendSysMsg(accessCode, server, "Gracz ${user.nickname} opuścił grę.")

        if (players.isEmpty()) {
            isGameStarted = false
        } else if (isGameStarted && wasActiveTurn) {
            consecutivePasses++
            if (currentPlayerIndex >= players.size) currentPlayerIndex = 0
            advanceTurn(accessCode, server)
        }
    }

    override fun shouldTerminate(): Boolean = players.isEmpty()

    override fun getTypeForEventName(eventName: String): Class<*>? = when (eventName) {
        "start_game" -> StartGameData::class.java
        "submit_word" -> SubmitWordData::class.java
        "pass" -> Any::class.java
        else -> null
    }

    override fun <T> handleEvent(eventName: String, eventData: T, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        when (eventName) {
            "start_game" -> handleStartGame(eventData as StartGameData, accessCode, server)
            "submit_word" -> handleSubmitWord((eventData as SubmitWordData).word, user, accessCode, server)
            "pass" -> handlePass(user, accessCode, server)
        }
    }

    private fun handleStartGame(data: StartGameData, accessCode: String, server: SocketIOServer) {
        if (isGameStarted || players.isEmpty()) return
        isGameStarted = true

        maxRounds = if (data.rounds > 0) data.rounds else 3
        gameMode = data.mode // Zapisujemy wybrany tryb z frontendu ("sentence" lub "random")
        currentRound = 1
        currentPlayerIndex = 0
        consecutivePasses = 0
        usedWords.clear()

        players.forEach {
            gameScores[it] = 0
            roundScores[it] = 0
        }
        players.shuffle()

        refreshPool() // Ustawia zdanie lub losowe litery w zależności od trybu

        val payload = GameStartedPayload(currentPool, maxRounds, players, gameMode)
        server.getRoomOperations(accessCode).sendEvent("game_started", payload)
        sendSysMsg(accessCode, server, "Gra wystartowała! Tryb: ${if(gameMode == "random") "Losowe Litery" else "Zdania"}.")

        broadcastTurnStart(accessCode, server)
    }

    private fun broadcastTurnStart(accessCode: String, server: SocketIOServer) {
        val activePlayerId = players[currentPlayerIndex]
        val payload = TurnStartPayload(activePlayerId, currentRound, currentPool)
        server.getRoomOperations(accessCode).sendEvent("turn_start", payload)
    }

    private fun handleSubmitWord(word: String, user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted || players[currentPlayerIndex] != user.userId) return

        val upperWord = word.trim().uppercase()

        val isUnique = !usedWords.contains(upperWord)
        val canBeFormed = canFormWord(upperWord, currentPool)
        val isLongEnough = upperWord.length >= 2
        val isRealWord = activeDictionary?.isValidWord(upperWord) ?: false

        if (isUnique && canBeFormed && isLongEnough && isRealWord) {
            usedWords.add(upperWord)

            val points = upperWord.length + upperWord.sumOf { char ->
                when (char) { 'Z' -> 7; 'J' -> 6; 'Q' -> 5; 'X' -> 4; 'K' -> 3; 'V' -> 2; 'B' -> 1; else -> 0 }
            }

            roundScores[user.userId] = (roundScores[user.userId] ?: 0) + points
            consecutivePasses = 0

            val payload = WordResultPayload(true, upperWord, points, "Zaliczono!", roundScores)
            server.getRoomOperations(accessCode).sendEvent("word_result", payload)
            sendSysMsg(accessCode, server, "${user.nickname} ułożył(a) '$upperWord' (+${points} pkt).")

            advanceTurn(accessCode, server)
        } else {
            val reason = when {
                !isLongEnough -> "Słowo za krótkie!"
                !isUnique -> "To słowo zostało już użyte!"
                !canBeFormed -> "Brak wymaganych liter w puli!"
                !isRealWord -> "Słowo nie istnieje w słowniku!"
                else -> "Niewłaściwe słowo!"
            }
            val payload = WordResultPayload(false, upperWord, 0, reason, roundScores)
            server.getClient(user.userId)?.sendEvent("word_result", payload)
        }
    }

    private fun handlePass(user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted || players.isEmpty() || players[currentPlayerIndex] != user.userId) return

        val payload = PlayerPassedPayload(user.userId)
        server.getRoomOperations(accessCode).sendEvent("user_passed", payload)
        sendSysMsg(accessCode, server, "${user.nickname} pasuje.")

        consecutivePasses++
        advanceTurn(accessCode, server)
    }

    private fun advanceTurn(accessCode: String, server: SocketIOServer) {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size

        if (consecutivePasses >= players.size) {
            endRound(accessCode, server)
        } else {
            broadcastTurnStart(accessCode, server)
        }
    }

    private fun endRound(accessCode: String, server: SocketIOServer) {
        val roundWinnerId = roundScores.maxByOrNull { it.value }?.key

        if (roundWinnerId != null && (roundScores[roundWinnerId] ?: 0) > 0) {
            gameScores[roundWinnerId] = (gameScores[roundWinnerId] ?: 0) + 1
            server.getRoomOperations(accessCode).sendEvent("round_end", EndRoundPayload(roundWinnerId, roundScores))
            sendSysMsg(accessCode, server, "Rundę wygrywa ${nicknames[roundWinnerId]}!")
        } else {
            server.getRoomOperations(accessCode).sendEvent("round_end", EndRoundPayload(null, roundScores))
            sendSysMsg(accessCode, server, "Nikt nie zdobył punktów w tej rundzie.")
        }

        if (currentRound >= maxRounds) {
            val gameWinnerId = gameScores.maxByOrNull { it.value }?.key
            val payload = GameOverPayload(gameWinnerId, gameScores)
            server.getRoomOperations(accessCode).sendEvent("game_over", payload)
            sendSysMsg(accessCode, server, "Koniec gry! Zwycięzca: ${nicknames[gameWinnerId] ?: "Brak"}")
            isGameStarted = false
        } else {
            currentRound++
            consecutivePasses = 0
            refreshPool() // Losujemy nową pulę/zdanie
            usedWords.clear()

            players.sortBy { gameScores[it] ?: 0 }
            currentPlayerIndex = 0

            players.forEach { roundScores[it] = 0 }

            sendSysMsg(accessCode, server, "--- RUNDA $currentRound ---")
            broadcastTurnStart(accessCode, server)
        }
    }

    private fun canFormWord(word: String, pool: String): Boolean {
        val availableLetters = pool.uppercase().replace(Regex("[^A-Z]"), "").toMutableList()
        for (char in word) {
            if (!availableLetters.remove(char)) return false
        }
        return true
    }
}

// --- DTOs ---

data class SubmitWordData @JsonCreator constructor(@JsonProperty("word") val word: String)

data class StartGameData @JsonCreator constructor(
    @JsonProperty("rounds") val rounds: Int = 3,
    @JsonProperty("mode") val mode: String = "sentence" // Z frontu może przyjść "sentence" lub "random"
)

data class GameStartedPayload(val currentPool: String, val totalRounds: Int, val players: List<UUID>, val mode: String)
data class TurnStartPayload(val activePlayerId: UUID, val currentRound: Int, val currentPool: String)
data class WordResultPayload(val success: Boolean, val word: String, val pointsGained: Int, val message: String, val updatedScores: Map<UUID, Int>)
data class PlayerPassedPayload(val playerId: UUID)
data class EndRoundPayload(val winnerId: UUID?, val scores: Map<UUID, Int>)
data class GameOverPayload(val winnerId: UUID?, val finalScores: Map<UUID, Int>)
data class GameSyncPayload(
    val currentPool: String, val currentRound: Int, val maxRounds: Int,
    val activePlayerId: UUID, val roundScores: Map<UUID, Int>, val gameScores: Map<UUID, Int>
)