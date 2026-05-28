package io.github.mrcabbagestick.scrambbled.game.impl

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.DictionaryAware
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.game.PlayerRole
import io.github.mrcabbagestick.scrambbled.tools.dictionary.WordDictionary
import io.github.mrcabbagestick.scrambbled.user.PlayerInfoDTO
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

class WordsInWordsGame : GameTemplate(Games.WORDS_IN_WORDS), DictionaryAware {

    private val roundScores = mutableMapOf<UUID, Int>()
    private val gameScores = mutableMapOf<UUID, Int>()
    private val usedWords = mutableSetOf<String>()

    private var isGameStarted = false
    private var gameMode: String = "sentence"
    private var currentPool: String = ""

    private var betweenRounds = false
    private var currentRound = 1
    private var maxRounds = 3
    private var currentPlayerIndex = 0
    private var consecutivePasses = 0

    private var activeDictionary: WordDictionary? = null

    override fun setDictionary(dictionary: WordDictionary?) { activeDictionary = dictionary }

    private val sentencePool = listOf(
        "Bread remembers", "We are not electric", "Teachers do not cook",
        "Doctors have a rocket", "Cat talks", "Robot comes",
        "Scientists show", "Cities have solutions", "Robot is futuristic",
        "Dolphin has problems", "Computer calculates slowly"
    )

    private fun generateRandomLetters(): String {
        val vowels = listOf('A', 'E', 'I', 'O', 'U', 'Y')
        val consonants = ('A'..'Z').filter { it !in vowels }
        return ((1..4).map { vowels.random() } + (1..8).map { consonants.random() })
            .shuffled().joinToString(" ")
    }

    private fun refreshPool() {
        currentPool = if (gameMode == "random") generateRandomLetters() else sentencePool.random()
    }

    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        val isNewPlayer = !isGameStarted && !players.contains(user)

        if (isNewPlayer) {
            players.add(user)
        }

        if (players.size == 1 || host == null) {
            host = players.first()
            broadcastEvent(accessCode, server, "host_assigned", PlayerInfoDTO(host!!))
        }

        val role = if (isNewPlayer) PlayerRole.PLAYER else PlayerRole.OBSERVER

        // Standard room tracking + player_joined broadcast + room_state to newcomer
        trackAndBroadcastJoin(user, role, accessCode, server)

        if (!isNewPlayer && isGameStarted) {
            // Send game state snapshot to the late-joining observer
            val syncPayload = GameSyncPayload(
                currentPool, currentRound, maxRounds,
                players[currentPlayerIndex].userId, roundScores, gameScores
            )
            server.getClient(user.userId)?.sendEvent("game_sync", syncPayload)
        }
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        val wasActiveTurn = isGameStarted && players.isNotEmpty() && players[currentPlayerIndex] == user
        players.remove(user)

        if (user == host) {
            host = players.firstOrNull()
            host?.let {
                broadcastEvent(accessCode, server, "host_assigned", PlayerInfoDTO(it))
                sendSysMsg(accessCode, server, "${it.nickname} jest teraz hostem.")
            }
        }

        // Standard player_left broadcast
        trackAndBroadcastLeave(user, accessCode, server)

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
        "start_game"  -> StartGameData::class.java
        "submit_word" -> SubmitWordData::class.java
        "pass"        -> Any::class.java
        "start_round" -> Any::class.java
        else          -> null
    }

    override fun <T> handleEvent(
        eventName: String, eventData: T,
        user: User, accessCode: String, server: SocketIOServer, ack: AckRequest
    ) {
        when (eventName) {
            "start_game"  -> handleStartGame(eventData as StartGameData, accessCode, server, user)
            "submit_word" -> handleSubmitWord((eventData as SubmitWordData).word, user, accessCode, server)
            "pass"        -> handlePass(user, accessCode, server)
            "start_round" -> handleStartRound(accessCode, server, user)
        }
    }

    private fun handleStartGame(data: StartGameData, accessCode: String, server: SocketIOServer, user: User) {
        if (user != host || isGameStarted || players.isEmpty()) return

        isGameStarted = true
        maxRounds = if (data.rounds > 0) data.rounds else 3
        gameMode = data.mode
        currentRound = 1
        currentPlayerIndex = 0
        consecutivePasses = 0
        betweenRounds = false
        usedWords.clear()

        players.forEach { roundScores[it.userId] = 0; gameScores[it.userId] = 0 }
        players.shuffle()
        refreshPool()

        broadcastEvent(accessCode, server, "game_started", GameStartedPayload(
            currentPool, maxRounds, players.map { PlayerInfoDTO(it) }, gameMode))
        sendSysMsg(accessCode, server, "Gra wystartowała! Tryb: ${if (gameMode == "random") "Losowe Litery" else "Zdania"}.")
        broadcastEvent(accessCode, server, "new_round", StartRoundPayload(currentRound))
        broadcastTurnStart(accessCode, server)
    }

    private fun broadcastTurnStart(accessCode: String, server: SocketIOServer) =
        broadcastEvent(accessCode, server, "turn_start",
            TurnStartPayload(players[currentPlayerIndex].userId, currentRound, currentPool))

    private fun handleSubmitWord(word: String, user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted || players[currentPlayerIndex] != user || betweenRounds) return

        val upperWord = word.trim().uppercase()
        val isUnique     = !usedWords.contains(upperWord)
        val canBeFormed  = canFormWord(upperWord, currentPool)
        val isLongEnough = upperWord.length >= 2
        val isRealWord   = activeDictionary?.isValidWord(upperWord) ?: false

        if (isUnique && canBeFormed && isLongEnough && isRealWord) {
            usedWords.add(upperWord)
            val points = upperWord.length + upperWord.sumOf { c ->
                when (c) { 'Z' -> 7; 'J' -> 6; 'Q' -> 5; 'X' -> 4; 'K' -> 3; 'V' -> 2; 'B' -> 1; else -> 0 }
            }
            roundScores[user.userId] = (roundScores[user.userId] ?: 0) + points
            consecutivePasses = 0
            broadcastEvent(accessCode, server, "word_result",
                WordResultPayload(true, upperWord, points, "Zaliczono!", roundScores))
            sendSysMsg(accessCode, server, "${user.nickname} ułożył(a) '$upperWord' (+$points pkt).")
            advanceTurn(accessCode, server)
        } else {
            val reason = when {
                !isLongEnough -> "Słowo za krótkie!"
                !isUnique     -> "To słowo zostało już użyte!"
                !canBeFormed  -> "Brak wymaganych liter w puli!"
                !isRealWord   -> "Słowo nie istnieje w słowniku!"
                else          -> "Niewłaściwe słowo!"
            }
            broadcastEvent(accessCode, server, "word_result",
                WordResultPayload(false, upperWord, 0, reason, roundScores))
        }
    }

    private fun handlePass(user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted || players.isEmpty() || players[currentPlayerIndex] != user || betweenRounds) return
        broadcastEvent(accessCode, server, "pass", PlayerPassedPayload(user.userId))
        sendSysMsg(accessCode, server, "${user.nickname} pasuje.")
        consecutivePasses++
        advanceTurn(accessCode, server)
    }

    private fun handleStartRound(accessCode: String, server: SocketIOServer, user: User) {
        if (user != host || !betweenRounds) return
        betweenRounds = false
        broadcastEvent(accessCode, server, "new_round", StartRoundPayload(currentRound))
        sendSysMsg(accessCode, server, "New round: $currentRound!")
        broadcastTurnStart(accessCode, server)
    }

    private fun advanceTurn(accessCode: String, server: SocketIOServer) {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        if (consecutivePasses >= players.size) endRound(accessCode, server)
        else broadcastTurnStart(accessCode, server)
    }

    private fun endRound(accessCode: String, server: SocketIOServer) {
        val roundWinnerId = roundScores.maxByOrNull { it.value }?.key

        if (roundWinnerId != null && (roundScores[roundWinnerId] ?: 0) > 0) {
            gameScores[roundWinnerId] = (gameScores[roundWinnerId] ?: 0) + 1
            broadcastEvent(accessCode, server, "round_end", EndRoundPayload(roundWinnerId, roundScores))
            sendSysMsg(accessCode, server, "Rundę wygrywa ${players.find { it.userId == roundWinnerId }?.nickname}!")
        } else {
            broadcastEvent(accessCode, server, "round_end", EndRoundPayload(null, roundScores))
            sendSysMsg(accessCode, server, "Nikt nie zdobył punktów w tej rundzie.")
        }

        if (currentRound >= maxRounds) {
            val gameWinnerId = gameScores.maxByOrNull { it.value }?.key
            broadcastEvent(accessCode, server, "game_over", GameOverPayload(gameWinnerId, gameScores))
            sendSysMsg(accessCode, server, "Koniec gry! Zwycięzca: ${players.find { it.userId == gameWinnerId }?.nickname}")
            isGameStarted = false
        } else {
            betweenRounds = true
            currentRound++
            consecutivePasses = 0
            refreshPool()
            usedWords.clear()
            players.sortBy { gameScores[it.userId] ?: 0 }
            currentPlayerIndex = 0
            players.forEach { roundScores[it.userId] = 0 }
            sendSysMsg(accessCode, server, "--- RUNDA $currentRound ---")
            broadcastTurnStart(accessCode, server)
        }
    }

    private fun canFormWord(word: String, pool: String): Boolean {
        val available = pool.uppercase().replace(Regex("[^A-Z]"), "").toMutableList()
        for (char in word) { if (!available.remove(char)) return false }
        return true
    }
}

// --- DTOs ---

data class SubmitWordData @JsonCreator constructor(@JsonProperty("word") val word: String)

data class StartGameData @JsonCreator constructor(
    @JsonProperty("rounds") val rounds: Int = 3,
    @JsonProperty("mode")   val mode: String = "sentence"
)

data class StartRoundPayload(val round: Int)

data class GameStartedPayload(
    val currentPool: String,
    val totalRounds: Int,
    val players: List<PlayerInfoDTO>,
    val mode: String
)

data class TurnStartPayload(val activePlayerId: UUID, val currentRound: Int, val currentPool: String)
data class WordResultPayload(val success: Boolean, val word: String, val pointsGained: Int, val message: String, val updatedScores: Map<UUID, Int>)
data class PlayerPassedPayload(val playerId: UUID)
data class EndRoundPayload(val winnerId: UUID?, val scores: Map<UUID, Int>)
data class GameOverPayload(val winnerId: UUID?, val finalScores: Map<UUID, Int>)
data class GameSyncPayload(
    val currentPool: String, val currentRound: Int, val maxRounds: Int,
    val activePlayerId: UUID, val roundScores: Map<UUID, Int>, val gameScores: Map<UUID, Int>
)