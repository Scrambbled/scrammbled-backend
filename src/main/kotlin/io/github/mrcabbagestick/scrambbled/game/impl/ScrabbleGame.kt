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

class ScrabbleGame : GameTemplate(Games.SCRABBLE_GAME), DictionaryAware {

    private var activeDictionary: WordDictionary? = null

    // --- STAN GRY ---
    private val players = mutableListOf<User>()
    private val scores = mutableMapOf<UUID, Int>()

    // Woreczek z literami (Pouch) i tacki graczy (Trays)
    private val letterPouch = mutableListOf<Char>()
    private val playerTrays = mutableMapOf<UUID, MutableList<Char>>()

    // Plansza: 15x15, null -> puste pole
    private val board = Array(15) { Array<Char?>(15) { null } }
    private var isFirstMove = true

    private var isGameStarted = false
    private var currentPlayerIndex = 0
    private var consecutivePasses = 0

    override fun setDictionary(dictionary: WordDictionary?) {
        this.activeDictionary = dictionary
    }

    private fun sendSysMsg(accessCode: String, server: SocketIOServer, msg: String) {
        server.getRoomOperations(accessCode).sendEvent("server message", ServerMessagePayload(msg))
    }

    // --- INITIALIZATION ---

    private fun initializeLetterPouch() {
        letterPouch.clear()
        // Prosty podział dla języka angielskiego (wersja uproszczona bez blanków)
        val distribution = mapOf(
            'A' to 9, 'B' to 2, 'C' to 2, 'D' to 4, 'E' to 12, 'F' to 2, 'G' to 3, 'H' to 2,
            'I' to 9, 'J' to 1, 'K' to 1, 'L' to 4, 'M' to 2, 'N' to 6, 'O' to 8, 'P' to 2,
            'Q' to 1, 'R' to 6, 'S' to 4, 'T' to 6, 'U' to 4, 'V' to 2, 'W' to 2, 'X' to 1,
            'Y' to 2, 'Z' to 1
        )
        distribution.forEach { (letter, count) ->
            repeat(count) { letterPouch.add(letter) }
        }
        letterPouch.shuffle()
    }

    private fun generateBoardData(): BoardData {
        // Standardowa plansza Scrabble 15x15. Start to (7,7)
        val specials = mutableListOf<SpecialSquare>()
        // TODO: Tu ogarnąć jak ustalać koordynaty pól premiowych dla planszy (przykład na sztywno)
        // np. specials.add(SpecialSquare(3, 1, 0, 0)) // Triple Word na rogu (x=0, y=0)

        return BoardData(15, 15, Coordinates(7, 7), specials)
    }

    private fun refillTray(playerId: UUID) {
        val tray = playerTrays.getOrPut(playerId) { mutableListOf() }
        while (tray.size < 7 && letterPouch.isNotEmpty()) {
            tray.add(letterPouch.removeAt(0))
        }
    }

    private fun sendTrayUpdate(accessCode: String, server: SocketIOServer) {
        players.forEach { user ->
            val tray = playerTrays[user.userId] ?: emptyList()
            server.getClient(user.userId)?.sendEvent("tray_update", mapOf("tray" to tray))
        }
    }

    // --- GAME LOOP ---

    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted && !players.any { it.userId == user.userId }) {
            players.add(user)
            sendSysMsg(accessCode, server, "${user.nickname} dołączył do gry (Gracz ${players.size}).")
        } else {
            sendSysMsg(accessCode, server, "${user.nickname} dołączył jako Obserwator.")
            // TODO: Tu można dodać wysłanie pełnego stanu planszy (SyncPayload)
        }
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        players.removeIf { it.userId == user.userId }
        sendSysMsg(accessCode, server, "${user.nickname} opuścił grę.")
        if (players.isEmpty()) isGameStarted = false
    }

    override fun shouldTerminate(): Boolean = players.isEmpty()

    override fun getTypeForEventName(eventName: String): Class<*>? = when (eventName) {
        "start_game" -> Any::class.java // Pusty payload
        "submit_move" -> SubmitMovePayload::class.java
        "swap_tiles" -> SwapTilesPayload::class.java
        "pass" -> Any::class.java
        else -> null
    }

    override fun <T> handleEvent(eventName: String, eventData: T, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        when (eventName) {
            "start_game" -> handleStartGame(accessCode, server)
            "submit_move" -> handleSubmitMove(eventData as SubmitMovePayload, user, accessCode, server)
            "swap_tiles" -> handleSwapTiles(eventData as SwapTilesPayload, user, accessCode, server)
            "pass" -> handlePass(user, accessCode, server)
        }
    }

    private fun handleStartGame(accessCode: String, server: SocketIOServer) {
        if (isGameStarted || players.isEmpty()) return
        isGameStarted = true
        consecutivePasses = 0
        isFirstMove = true

        players.shuffle()
        players.forEach { scores[it.userId] = 0 }

        initializeLetterPouch()
        players.forEach { refillTray(it.userId) }

        val boardData = generateBoardData()
        val playerInfos = players.map { PlayerInfoDTO(it) }

        server.getRoomOperations(accessCode).sendEvent("game_started", ScrabbleStartedPayload(boardData, playerInfos))

        sendTrayUpdate(accessCode, server)
        broadcastTurnStart(accessCode, server)
    }

    private fun broadcastTurnStart(accessCode: String, server: SocketIOServer) {
        val activeUserId = players[currentPlayerIndex].userId
        server.getRoomOperations(accessCode).sendEvent("turn_start", mapOf(
            "activePlayerId" to activeUserId,
            "lettersInPouch" to letterPouch.size
        ))
    }

    // --- ACTIONS ---

    private fun handleSubmitMove(move: SubmitMovePayload, user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted || players[currentPlayerIndex].userId != user.userId) return

        val tray = playerTrays[user.userId] ?: return

        // 1. Sprawdzamy, czy gracz faktycznie ma kafelki, które próbuje położyć
        val usedLetters = move.placedTiles.map { it.letter }.toMutableList()
        val tempTray = tray.toMutableList()
        for (letter in usedLetters) {
            if (!tempTray.remove(letter)) {
                server.getClient(user.userId)?.sendEvent("move_error", "Nie masz odpowiednich liter na tacce!")
                return
            }
        }

        // TODO: 2. LOGIKA WALIDACJI SCRABBLE (Do zaimplementowania)
        // - Czy słowo jest w jednej linii?
        // - Czy styka się z innymi (lub jest na środku jeśli isFirstMove)?
        // - Czy słowa pobrane za pomocą `activeDictionary?.isValidWord(slowo)` istnieją?

        val isValid = true // TODO: Podmienić na właściwy walidator
        val pointsGained = 15 // TODO: Obliczyć punkty z uwzględnieniem BoardData i kafelków

        if (isValid) {
            // Aktualizacja stanu
            isFirstMove = false
            consecutivePasses = 0
            scores[user.userId] = (scores[user.userId] ?: 0) + pointsGained

            // Zapis na planszę
            move.placedTiles.forEach { tile ->
                board[tile.y][tile.x] = tile.letter
            }

            // Zabranie użytych kafelków z tacki i dobranie nowych z worka
            playerTrays[user.userId] = tempTray
            refillTray(user.userId)

            sendTrayUpdate(accessCode, server) // Wysyłamy graczowi nowe kafelki

            // Rozsyłamy sukces do reszty
            val successPayload = MoveResultPayload(user.userId, move.placedTiles, pointsGained, scores)
            server.getRoomOperations(accessCode).sendEvent("move_accepted", successPayload)
            sendSysMsg(accessCode, server, "${user.nickname} ułożył słowo za $pointsGained pkt.")

            checkGameEndOrAdvanceTurn(accessCode, server)
        } else {
            server.getClient(user.userId)?.sendEvent("move_error", "Niedozwolony ruch lub słowo nie istnieje!")
        }
    }

    private fun handleSwapTiles(payload: SwapTilesPayload, user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted || players[currentPlayerIndex].userId != user.userId) return

        val tray = playerTrays[user.userId] ?: return

        // Zdejmujemy litery, wymieniamy, tasujemy
        payload.lettersToSwap.forEach { tray.remove(it) }
        letterPouch.addAll(payload.lettersToSwap)
        letterPouch.shuffle()
        refillTray(user.userId)

        consecutivePasses = 0
        sendTrayUpdate(accessCode, server)
        sendSysMsg(accessCode, server, "${user.nickname} wymienił ${payload.lettersToSwap.size} liter.")

        checkGameEndOrAdvanceTurn(accessCode, server)
    }

    private fun handlePass(user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted || players[currentPlayerIndex].userId != user.userId) return

        consecutivePasses++
        sendSysMsg(accessCode, server, "${user.nickname} pasuje.")
        checkGameEndOrAdvanceTurn(accessCode, server)
    }

    private fun checkGameEndOrAdvanceTurn(accessCode: String, server: SocketIOServer) {
        // Gra kończy się jeśli wszyscy spasowali 2x z rzędu lub ktoś wyłożył wszystkie litery i worek jest pusty
        val isTrayEmpty = playerTrays[players[currentPlayerIndex].userId]?.isEmpty() == true

        if (consecutivePasses >= players.size * 2 || (isTrayEmpty && letterPouch.isEmpty())) {
            val winnerId = scores.maxByOrNull { it.value }?.key
            server.getRoomOperations(accessCode).sendEvent("game_over", mapOf("winner" to winnerId, "finalScores" to scores))
            sendSysMsg(accessCode, server, "Gra zakończona! Wygrywa: ${players.find { it.userId == winnerId }?.nickname}")
            isGameStarted = false
        } else {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size
            broadcastTurnStart(accessCode, server)
        }
    }
}

// --- DTO ---

data class PlayerInfoDTO(
    val id: UUID,
    val nickname: String,
    val iconUrl: String // "/static/user_icons/sock_puppet_blue.png"???
) {
    constructor(user: User) : this(
        id = user.userId,
        nickname = user.nickname,
        iconUrl = "/static/user_icons/${user.icon}.png"
    )
}

// Plansza Daniela
data class Coordinates(val x: Int, val y: Int)

data class SpecialSquare(
    val wordMultiplier: Int,
    val letterMultiplier: Int,
    val x: Int,
    val y: Int
)

data class BoardData(
    val width: Int,
    val height: Int,
    val startingSquare: Coordinates,
    val specialSquares: List<SpecialSquare>
)

// --- PAYLOADY ---

data class PlacedTile(val letter: Char, val x: Int, val y: Int)

data class SubmitMovePayload @JsonCreator constructor(
    @JsonProperty("placedTiles") val placedTiles: List<PlacedTile>
)

data class SwapTilesPayload @JsonCreator constructor(
    @JsonProperty("lettersToSwap") val lettersToSwap: List<Char>
)

data class ScrabbleStartedPayload(
    val boardData: BoardData,
    val players: List<PlayerInfoDTO>
)

data class MoveResultPayload(
    val playerId: UUID,
    val newlyPlacedTiles: List<PlacedTile>,
    val pointsGained: Int,
    val updatedScores: Map<UUID, Int>
)