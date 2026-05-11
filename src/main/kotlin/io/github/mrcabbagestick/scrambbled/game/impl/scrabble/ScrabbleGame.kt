package io.github.mrcabbagestick.scrambbled.game.impl.scrabble

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import io.github.mrcabbagestick.scrambbled.game.DictionaryAware
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.game.impl.scrabble.PlacementValidator.validateMove
import io.github.mrcabbagestick.scrambbled.tools.dictionary.WordDictionary
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

class ScrabbleGame : GameTemplate(Games.SCRABBLE_GAME), DictionaryAware {

    private var activeDictionary: WordDictionary? = null

    // --- STAN GRY ---
    private val scores = mutableMapOf<UUID, Int>()

    // Woreczek z literami (Pouch) i tacki graczy (Trays)
    private val letterPouch = mutableListOf<Char>()
    private val playerTrays = mutableMapOf<UUID, MutableList<Char>>()

    // Wartości punktowe liter
    private val letterValues = mapOf(
        'A' to 1, 'B' to 3, 'C' to 2, 'D' to 2, 'E' to 1, 'F' to 5, 'G' to 3, 'H' to 3,
        'I' to 1, 'J' to 3, 'K' to 2, 'L' to 2, 'M' to 2, 'N' to 1, 'O' to 1, 'P' to 2,
        'Q' to 8,  'R' to 1, 'S' to 1, 'T' to 2, 'U' to 3, 'V' to 4, 'W' to 1, 'X' to 8,
        'Y' to 2, 'Z' to 1
    )

    private var activeSpecials = mapOf<Pair<Int, Int>, SpecialSquare>()

    // Plansza: 15x15, null -> puste pole
    val board = Array(15) { Array<Char?>(15) { null } }
    private var isFirstMove = true

    private var isGameStarted = false
    private var currentPlayerIndex = 0
    private var consecutivePasses = 0

    //==================================
    //TODO: Przerobić co się da na ACK!!
    //==================================

    override fun setDictionary(dictionary: WordDictionary?) {
        this.activeDictionary = dictionary
    }

    // --- INITIALIZATION ---

    private fun initializeLetterPouch() {
        letterPouch.clear()
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
        val specials = mutableListOf<SpecialSquare>()

        fun addSpecialSquares(coords: List<Pair<Int, Int>>, wordMult: Int, letterMult: Int) {
            coords.forEach { (x, y) ->
                specials.add(
                    SpecialSquare(
                        x = x,
                        y = y,
                        wordMultiplier = wordMult,
                        letterMultiplier = letterMult
                    )
                )
            }
        }

        // 1. Potrójna premia słowna - pola na krawędziach
        addSpecialSquares(listOf(
            0 to 0, 0 to 7, 0 to 14,
            7 to 0,         7 to 14,
            14 to 0, 14 to 7, 14 to 14
        ), wordMult = 3, letterMult = 1)

        // 2. Podwójna premia słowna - przekątne + środek
        addSpecialSquares(listOf(
            1 to 1, 2 to 2, 3 to 3, 4 to 4,
            10 to 10, 11 to 11, 12 to 12, 13 to 13,
            1 to 13, 2 to 12, 3 to 11, 4 to 10,
            13 to 1, 12 to 2, 11 to 3, 10 to 4,
            7 to 7 // Pole startowe tradycyjnie działa jak podwójna premia słowna
        ), wordMult = 2, letterMult = 1)

        // 3. Potrójna premia literowa
        addSpecialSquares(listOf(
            1 to 5, 1 to 9,
            5 to 1, 5 to 5, 5 to 9, 5 to 13,
            9 to 1, 9 to 5, 9 to 9, 9 to 13,
            13 to 5, 13 to 9
        ), wordMult = 1, letterMult = 3)

        // 4. Podwójna premia literowa
        addSpecialSquares(listOf(
            0 to 3, 0 to 11,
            2 to 6, 2 to 8,
            3 to 0, 3 to 7, 3 to 14,
            6 to 2, 6 to 6, 6 to 8, 6 to 12,
            7 to 3, 7 to 11,
            8 to 2, 8 to 6, 8 to 8, 8 to 12,
            11 to 0, 11 to 7, 11 to 14,
            12 to 6, 12 to 8,
            14 to 3, 14 to 11
        ), wordMult = 1, letterMult = 2)

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
            val letters = playerTrays[user.userId] ?: emptyList()
            val tray = letters.map { letter ->
                Letter(letter, letterValues[letter] ?: 0)
            }

            sendToUser(user, server, "tray_update", TrayUpdate(tray))
//            server.getClient(user.userId)?.sendEvent("tray_update", mapOf("tray" to tray))
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
        "check_word" -> CheckWordPayload::class.java
        else -> null
    }

    override fun <T> handleEvent(eventName: String, eventData: T, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        when (eventName) {
            "start_game" -> handleStartGame(accessCode, server)
            "submit_move" -> handleSubmitMove(eventData as SubmitMovePayload, user, accessCode, server)
            "swap_tiles" -> handleSwapTiles(eventData as SwapTilesPayload, user, accessCode, server)
            "pass" -> handlePass(user, accessCode, server)
            "check_word" -> handleCheckWord(eventData as CheckWordPayload, user, ack)
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

        broadcastEvent(accessCode, server, "start_game", ScrabbleStartedPayload(boardData, playerInfos))
//        server.getRoomOperations(accessCode).sendEvent("game_started", ScrabbleStartedPayload(boardData, playerInfos))

        sendTrayUpdate(accessCode, server)
        broadcastTurnStart(accessCode, server)
    }

    private fun handleCheckWord(payload: CheckWordPayload, user: User, ack: AckRequest) {
        // 1. Sprawdzenie, czy gra trwa
        if (!isGameStarted) {
            if (ack.isAckRequested) ack.sendAckData(CheckWordResponse("invalid_placement"))
            return
        }

        // 2. Walidacja
        val validationResult = validateMove(
            board = this.board,
            placedTiles = payload.placedTiles,
            isFirstMove = this.isFirstMove,
            dictionary = this.activeDictionary,
            letterValues = this.letterValues,
            specials = this.activeSpecials
        )

        // 4. Wysłanie odpowiedzi
        val response = CheckWordResponse(
            status = validationResult.status,
            points = if (validationResult.isValid) validationResult.points else null
        )

        if (ack.isAckRequested) {
            ack.sendAckData(response)
        }
    }

    private fun broadcastTurnStart(accessCode: String, server: SocketIOServer) {
        val activeUserId = players[currentPlayerIndex].userId
        broadcastEvent(accessCode, server, "turn_start", mapOf(
            "activePlayerId" to activeUserId,
            "lettersInPouch" to letterPouch.size
        ))
//        server.getRoomOperations(accessCode).sendEvent("turn_start", mapOf(
//            "activePlayerId" to activeUserId,
//            "lettersInPouch" to letterPouch.size
//        ))
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
                sendToUser(user, server, "move_error", "Nie masz odpowiednich liter na tacce!")
//                server.getClient(user.userId)?.sendEvent("move_error", "Nie masz odpowiednich liter na tacce!")
                return
            }
        }

        // 2. LOGIKA WALIDACJI SCRABBLE
        val validationResult = PlacementValidator.validateMove(
            board = this.board,
            placedTiles = move.placedTiles,
            isFirstMove = this.isFirstMove,
            dictionary = this.activeDictionary,
            letterValues = this.letterValues,
            specials = this.activeSpecials
        )

        if (validationResult.isValid) {
            // Aktualizacja stanu
            isFirstMove = false
            consecutivePasses = 0
            scores[user.userId] = (scores[user.userId] ?: 0) + validationResult.points

            // Zapis na planszę
            move.placedTiles.forEach { tile ->
                board[tile.y][tile.x] = tile.letter
            }

            // Zabranie użytych kafelków z tacki i dobranie nowych z worka
            playerTrays[user.userId] = tempTray
            refillTray(user.userId)

            sendTrayUpdate(accessCode, server) // Wysyłamy graczowi nowe kafelki

            // Rozsyłamy sukces do reszty
            val successPayload = MoveResultPayload(user.userId, move.placedTiles, validationResult.points, scores)
            broadcastEvent(accessCode, server, "move_accepted", successPayload)
            sendSysMsg(accessCode, server, "${user.nickname} ułożył słowo za ${validationResult.points} pkt.")

            checkGameEndOrAdvanceTurn(accessCode, server)
        } else {
            // Wysłanie konkretnego błędu w oparciu o validationResult.status
            val errorMessage = when (validationResult.status) {
                "must_contain_starting_square" -> "Pierwsze słowo musi przechodzić przez środek planszy!"
                "invalid_placement" -> "Niedozwolony układ kafelków!"
                "bad" -> "Jedno lub więcej słów nie istnieje w słowniku!"
                else -> "Niedozwolony ruch!"
            }
            sendToUser(user, server, "move_error", errorMessage)
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
            broadcastEvent(accessCode, server, "game_over", mapOf("winner" to winnerId, "finalScores" to scores))
//            server.getRoomOperations(accessCode).sendEvent("game_over", mapOf("winner" to winnerId, "finalScores" to scores))
            sendSysMsg(accessCode, server, "Gra zakończona! Wygrywa: ${players.find { it.userId == winnerId }?.nickname}")
            isGameStarted = false
        } else {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size
            broadcastTurnStart(accessCode, server)
        }
    }
}