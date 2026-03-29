package io.github.mrcabbagestick.scrambbled.game.impl

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

class WordsInWordsGame : GameTemplate(Games.WORDS_IN_WORDS) {

    private val players = mutableListOf<UUID>()

    private val roundScores = mutableMapOf<UUID, Int>()
    private val gameScores = mutableMapOf<UUID, Int>()
    private val usedWords = mutableSetOf<String>()

    private var isGameStarted = false
    private var baseSentence: String = ""
    private var currentRound = 1
    private var maxRounds = 3
    private var currentPlayerIndex = 0
    private var consecutivePasses = 0

    private val sentencePool = listOf(
        "KOT W BUTACH",
        "ZUPA POMIDOROWA",
        "WŁADCA PIERŚCIENI",
        "STARA SZAFA",
        "LATAJĄCY DYWAN"
    )

    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted && !players.contains(user.userId)) {
            players.add(user.userId)
            val playerNumber = players.size
            server.getRoomOperations(accessCode).sendEvent("chat message", "Gracz ${user.userId.toString().substring(0,5)} dołączył jako Gracz $playerNumber.")
        } else if (isGameStarted && !players.contains(user.userId)) {
            server.getRoomOperations(accessCode).sendEvent("chat message", "Gracz ${user.userId.toString().substring(0,5)} dołączył jako Obserwator.")
        }
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        players.remove(user.userId)
        server.getRoomOperations(accessCode).sendEvent("chat message", "Gracz ${user.userId.toString().substring(0,5)} opuścił grę.")
        if (players.isEmpty()) isGameStarted = false
    }

    override fun shouldTerminate(): Boolean = players.isEmpty()

    override fun getTypeForEventName(eventName: String): Class<*>? = when (eventName) {
        "start_game" -> StartGameData::class.java
        "submit_word" -> SubmitWordData::class.java
        "pass" -> Any::class.java
        else -> null
    }

    override fun <T> handleEvent(
        eventName: String,
        eventData: T,
        user: User,
        accessCode: String,
        server: SocketIOServer,
        ack: AckRequest
    ) {
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
        currentRound = 1
        currentPlayerIndex = 0
        consecutivePasses = 0
        usedWords.clear()

        players.forEach {
            gameScores[it] = 0
            roundScores[it] = 0
        }

        baseSentence = sentencePool.random()

        val payload = GameStartedPayload(baseSentence, maxRounds, players)
        server.getRoomOperations(accessCode).sendEvent("game_started", payload)

        broadcastTurnStart(accessCode, server)
    }

    private fun broadcastTurnStart(accessCode: String, server: SocketIOServer) {
        val activePlayerId = players[currentPlayerIndex]
        val payload = TurnStartPayload(activePlayerId, currentRound, baseSentence)
        server.getRoomOperations(accessCode).sendEvent("turn_start", payload)
    }

    private fun handleSubmitWord(word: String, user: User, accessCode: String, server: SocketIOServer) {
        if (players[currentPlayerIndex] != user.userId) return

        val upperWord = word.uppercase()

        val isUnique = !usedWords.contains(upperWord)
        val canBeFormed = canFormWord(upperWord, baseSentence)
        val isLongEnough = upperWord.length >= 2

        if (isUnique && canBeFormed && isLongEnough) {
            // SUCCESS
            usedWords.add(upperWord)
            val points = upperWord.length
            roundScores[user.userId] = (roundScores[user.userId] ?: 0) + points

            consecutivePasses = 0

            val payload = WordResultPayload(true, upperWord, points, "Właściwe słowo!", roundScores)
            server.getRoomOperations(accessCode).sendEvent("word_result", payload)

            advanceTurn(accessCode, server)
        } else {
            // FAIL
            val reason = when {
                !isLongEnough -> "Za krótkie!"
                !isUnique -> "To słowo zostało już użyte!"
                !canBeFormed -> "Brak liter w zdaniu bazowym!"
                else -> "Niewłaściwe słowo!"
            }
            val payload = WordResultPayload(false, upperWord, 0, reason, roundScores)
            server.getRoomOperations(accessCode).sendEvent("word_result", payload)
        }
    }

    private fun handlePass(user: User, accessCode: String, server: SocketIOServer) {
        if (players[currentPlayerIndex] != user.userId) return

        server.getRoomOperations(accessCode).sendEvent("chat message", "Gracz ${user.userId.toString().substring(0,5)} pasuje.")

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
        if (roundWinnerId != null) {
            gameScores[roundWinnerId] = (gameScores[roundWinnerId] ?: 0) + 1
            server.getRoomOperations(accessCode).sendEvent("chat message", "Runda zakończona! Gracz ${roundWinnerId.toString().substring(0,5)} wygrywa rundę.")
        }

        if (currentRound >= maxRounds) {
            val gameWinnerId = gameScores.maxByOrNull { it.value }?.key
            val payload = GameOverPayload(gameWinnerId, gameScores)
            server.getRoomOperations(accessCode).sendEvent("game_over", payload)
            isGameStarted = false
        } else {
            currentRound++
            consecutivePasses = 0
            baseSentence = sentencePool.random()

            usedWords.clear()

            players.forEach {
                roundScores[it] = 0
            }

            server.getRoomOperations(accessCode).sendEvent("chat message", "Rozpoczyna się runda $currentRound!")
            broadcastTurnStart(accessCode, server)
        }
    }

    private fun canFormWord(word: String, sentence: String): Boolean {
        val availableLetters = sentence.uppercase().replace(" ", "").toMutableList()
        for (char in word) {
            if (!availableLetters.remove(char)) return false
        }
        return true
    }
}

data class SubmitWordData @JsonCreator constructor(
    @JsonProperty("word") val word: String
)

data class StartGameData @JsonCreator constructor(
    @JsonProperty("rounds") val rounds: Int = 3
)

data class GameStartedPayload(
    val baseSentence: String,
    val totalRounds: Int,
    val players: List<UUID>
)

data class TurnStartPayload(
    val activePlayerId: UUID,
    val currentRound: Int,
    val baseSentence: String
)

data class WordResultPayload(
    val success: Boolean,
    val word: String,
    val pointsGained: Int,
    val message: String,
    val updatedScores: Map<UUID, Int>
)

data class GameOverPayload(
    val winnerId: UUID?,
    val finalScores: Map<UUID, Int>
)