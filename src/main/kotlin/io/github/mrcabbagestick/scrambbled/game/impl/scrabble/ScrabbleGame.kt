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
import kotlin.math.max
import kotlin.math.roundToInt

class ScrabbleGame : GameTemplate(Games.SCRABBLE_GAME), DictionaryAware {

    private var activeDictionary: WordDictionary? = null

    // --- CUSTOM CONFIG (set by SocketIOConfig before start_game) ---

    /** Overrides [defaultLetterValues] when uploaded via JSON config file. */
    private var customLetterValues: Map<Char, Int>? = null

    /**
     * Overrides [defaultLetterDistribution] when uploaded via JSON config file.
     * Applied together with [gameLengthMultiplier] inside [initializeLetterPouch].
     */
    private var customLetterDistribution: Map<Char, Int>? = null

    /**
     * Scales every letter count in the distribution.
     * 0.5 = short game, 1.0 = default, 1.5 = long, 2.0 = extended.
     * Each resulting count is clamped to a minimum of 1.
     */
    private var gameLengthMultiplier: Double = 1.0

    /** Effective letter values: custom if uploaded, otherwise English defaults. */
    val effectiveLetterValues: Map<Char, Int>
        get() = customLetterValues ?: defaultLetterValues

    /** Effective distribution: custom if uploaded, otherwise English defaults. */
    private val effectiveLetterDistribution: Map<Char, Int>
        get() = customLetterDistribution ?: defaultLetterDistribution

    // --- SETTERS (called from SocketIOConfig) ---

    override fun setDictionary(dictionary: WordDictionary?) {
        activeDictionary = dictionary
    }

    fun setCustomLetterValues(values: Map<Char, Int>) {
        customLetterValues = values
    }

    fun setCustomLetterDistribution(distribution: Map<Char, Int>) {
        customLetterDistribution = distribution
    }

    /**
     * @param multiplier scale factor for the letter counts.
     *   0.5  → ~half the tiles (short game)
     *   1.0  → default quantity (no change)
     *   1.5  → ~50 % more tiles
     *   2.0  → double tiles (extended game)
     */
    fun setGameLengthMultiplier(multiplier: Double) {
        gameLengthMultiplier = multiplier.coerceAtLeast(0.1)
    }

    // --- GAME STATE ---

    private val scores = mutableMapOf<UUID, Int>()
    private val letterPouch = mutableListOf<Char>()
    private val playerTrays = mutableMapOf<UUID, MutableList<Char>>()

    private val defaultLetterValues = mapOf(
        'A' to 1, 'B' to 3, 'C' to 2, 'D' to 2, 'E' to 1, 'F' to 5, 'G' to 3, 'H' to 3,
        'I' to 1, 'J' to 3, 'K' to 2, 'L' to 2, 'M' to 2, 'N' to 1, 'O' to 1, 'P' to 2,
        'Q' to 8, 'R' to 1, 'S' to 1, 'T' to 2, 'U' to 3, 'V' to 4, 'W' to 1, 'X' to 8,
        'Y' to 2, 'Z' to 1
    )

    private val defaultLetterDistribution = mapOf(
        'A' to 9, 'B' to 2, 'C' to 2, 'D' to 4, 'E' to 12, 'F' to 2, 'G' to 3, 'H' to 2,
        'I' to 9, 'J' to 1, 'K' to 1, 'L' to 4, 'M' to 2, 'N' to 6, 'O' to 8, 'P' to 2,
        'Q' to 1, 'R' to 6, 'S' to 4, 'T' to 6, 'U' to 4, 'V' to 2, 'W' to 2, 'X' to 1,
        'Y' to 2, 'Z' to 1
    )

    private var activeSpecials = mapOf<Pair<Int, Int>, SpecialSquare>()

    val board = Array(15) { Array<Char?>(15) { null } }
    private var isFirstMove = true
    private var isGameStarted = false
    private var currentPlayerIndex = 0
    private var consecutivePasses = 0

    // --- INITIALIZATION ---

    /**
     * Fills [letterPouch] using [effectiveLetterDistribution] scaled by [gameLengthMultiplier].
     *
     * When multiplier == 1.0 the counts are used verbatim (no rounding involved).
     * Otherwise each count is rounded to the nearest integer, clamped to at least 1.
     */
    private fun initializeLetterPouch() {
        letterPouch.clear()
        effectiveLetterDistribution.forEach { (letter, baseCount) ->
            val count = if (gameLengthMultiplier == 1.0) {
                baseCount
            } else {
                max(1, (baseCount * gameLengthMultiplier).roundToInt())
            }
            repeat(count) { letterPouch.add(letter) }
        }
        letterPouch.shuffle()
    }

    private fun generateBoardData(): BoardData {
        val specials = mutableListOf<SpecialSquare>()

        fun addSpecials(coords: List<Pair<Int, Int>>, wordMult: Int, letterMult: Int) {
            coords.forEach { (x, y) ->
                specials.add(SpecialSquare(x = x, y = y, wordMultiplier = wordMult, letterMultiplier = letterMult))
            }
        }

        // Triple word score — board edges
        addSpecials(listOf(
            0 to 0, 0 to 7, 0 to 14,
            7 to 0,         7 to 14,
            14 to 0, 14 to 7, 14 to 14
        ), wordMult = 3, letterMult = 1)

        // Double word score — diagonals + centre star
        addSpecials(listOf(
            1 to 1, 2 to 2, 3 to 3, 4 to 4,
            10 to 10, 11 to 11, 12 to 12, 13 to 13,
            1 to 13, 2 to 12, 3 to 11, 4 to 10,
            13 to 1, 12 to 2, 11 to 3, 10 to 4,
            7 to 7
        ), wordMult = 2, letterMult = 1)

        // Triple letter score
        addSpecials(listOf(
            1 to 5, 1 to 9,
            5 to 1, 5 to 5, 5 to 9, 5 to 13,
            9 to 1, 9 to 5, 9 to 9, 9 to 13,
            13 to 5, 13 to 9
        ), wordMult = 1, letterMult = 3)

        // Double letter score
        addSpecials(listOf(
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

        activeSpecials = specials.associateBy { Pair(it.x, it.y) }
        return BoardData(15, 15, Coordinates(7, 7), specials)
    }

    private fun refillTray(playerId: UUID) {
        val tray = playerTrays.getOrPut(playerId) { mutableListOf() }
        while (tray.size < 7 && letterPouch.isNotEmpty()) {
            tray.add(letterPouch.removeAt(0))
        }
    }

    private fun getTrayLetters(playerId: UUID): List<Letter> =
        (playerTrays[playerId] ?: emptyList()).map { Letter(it, effectiveLetterValues[it] ?: 0) }

    /** Sends personalised tray_update to every player — used only at game start. */
    private fun sendTrayUpdate(accessCode: String, server: SocketIOServer) {
        players.forEach { user ->
            sendToUser(user, server, "tray_update", TrayUpdate(getTrayLetters(user.userId)))
        }
    }

    // --- GAME LOOP ---

    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        if (!isGameStarted && !players.any { it.userId == user.userId }) {
            players.add(user)
            if (host == null) {
                host = user
                sendToUser(user, server, "host_assigned", mapOf("isHost" to true))
            }
            sendSysMsg(accessCode, server, "${user.nickname} dołączył do gry (Gracz ${players.size}).")
        } else {
            sendSysMsg(accessCode, server, "${user.nickname} dołączył jako Obserwator.")
        }
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        players.removeIf { it.userId == user.userId }
        if (user == host) {
            host = players.firstOrNull()
            if (host != null) {
                sendToUser(host!!, server, "host_assigned", mapOf("isHost" to true))
                sendSysMsg(accessCode, server, "${host!!.nickname} jest teraz hostem.")
            }
        }
        sendSysMsg(accessCode, server, "${user.nickname} opuścił grę.")
        if (players.isEmpty()) isGameStarted = false
    }

    override fun shouldTerminate(): Boolean = players.isEmpty()

    override fun getTypeForEventName(eventName: String): Class<*>? = when (eventName) {
        "start_game"     -> Any::class.java
        "submit_move"    -> SubmitMovePayload::class.java
        "swap_tiles"     -> SwapTilesPayload::class.java
        "pass"           -> Any::class.java
        "check_word"     -> CheckWordPayload::class.java
        "get_pouch_info" -> Any::class.java
        else             -> null
    }

    override fun <T> handleEvent(
        eventName: String, eventData: T,
        user: User, accessCode: String, server: SocketIOServer, ack: AckRequest
    ) {
        when (eventName) {
            "start_game"     -> handleStartGame(accessCode, server, user, ack)
            "submit_move"    -> handleSubmitMove(eventData as SubmitMovePayload, user, accessCode, server, ack)
            "swap_tiles"     -> handleSwapTiles(eventData as SwapTilesPayload, user, accessCode, server, ack)
            "pass"           -> handlePass(user, accessCode, server, ack)
            "check_word"     -> handleCheckWord(eventData as CheckWordPayload, ack)
            "get_pouch_info" -> handleGetPouchInfo(ack)
        }
    }

    // --- EVENT HANDLERS ---

    private fun handleStartGame(accessCode: String, server: SocketIOServer, user: User, ack: AckRequest) {
        fun ackError(msg: String) { if (ack.isAckRequested) ack.sendAckData(StartGameAckResponse("error", msg)) }

        if (user.userId != host?.userId) return ackError("Only the host can start the game")
        if (isGameStarted)               return ackError("Game has already started")
        if (players.isEmpty())           return ackError("No players in the room")

        isGameStarted = true
        consecutivePasses = 0
        isFirstMove = true

        players.shuffle()
        players.forEach { scores[it.userId] = 0 }

        initializeLetterPouch()
        players.forEach { refillTray(it.userId) }

        val boardData   = generateBoardData()
        val playerInfos = players.map { PlayerInfoDTO(it) }

        broadcastEvent(accessCode, server, "start_game", ScrabbleStartedPayload(boardData, playerInfos))
        sendTrayUpdate(accessCode, server)
        broadcastTurnStart(accessCode, server)

        if (ack.isAckRequested) ack.sendAckData(StartGameAckResponse("ok"))
    }

    /** Validates placement without committing — live feedback while tiles are still being placed. */
    private fun handleCheckWord(payload: CheckWordPayload, ack: AckRequest) {
        if (!ack.isAckRequested) return
        if (!isGameStarted) { ack.sendAckData(CheckWordResponse("invalid_placement")); return }

        val result = validateMove(
            board        = board,
            placedTiles  = payload.placedTiles,
            isFirstMove  = isFirstMove,
            dictionary   = activeDictionary,
            letterValues = effectiveLetterValues,
            specials     = activeSpecials
        )
        ack.sendAckData(CheckWordResponse(result.status, if (result.isValid) result.points else null))
    }

    /** Returns the count and sorted list of remaining letters in the pouch. */
    private fun handleGetPouchInfo(ack: AckRequest) {
        if (!ack.isAckRequested) return
        ack.sendAckData(PouchInfoResponse(count = letterPouch.size, letters = letterPouch.sorted()))
    }

    private fun broadcastTurnStart(accessCode: String, server: SocketIOServer) {
        broadcastEvent(accessCode, server, "turn_start", ScrabbleTurnStartPayload(
            activePlayerId = players[currentPlayerIndex].userId,
            lettersInPouch = letterPouch.size,
            scores         = scores.toMap()
        ))
    }

    private fun handleSubmitMove(
        move: SubmitMovePayload, user: User,
        accessCode: String, server: SocketIOServer, ack: AckRequest
    ) {
        fun ackError(msg: String) { if (ack.isAckRequested) ack.sendAckData(MoveAckResponse("error", message = msg)) }

        if (!isGameStarted || players[currentPlayerIndex].userId != user.userId) return ackError("Not your turn")

        val tray = playerTrays[user.userId] ?: return ackError("No tray found")

        val tempTray = tray.toMutableList()
        for (letter in move.placedTiles.map { it.letter }) {
            if (!tempTray.remove(letter)) return ackError("Nie masz odpowiednich liter na tacce!")
        }

        val result = PlacementValidator.validateMove(
            board        = board,
            placedTiles  = move.placedTiles,
            isFirstMove  = isFirstMove,
            dictionary   = activeDictionary,
            letterValues = effectiveLetterValues,
            specials     = activeSpecials
        )

        if (result.isValid) {
            isFirstMove = false
            consecutivePasses = 0
            scores[user.userId] = (scores[user.userId] ?: 0) + result.points

            move.placedTiles.forEach { board[it.y][it.x] = it.letter }
            playerTrays[user.userId] = tempTray
            refillTray(user.userId)

            if (ack.isAckRequested) ack.sendAckData(MoveAckResponse(
                status        = "accepted",
                points        = result.points,
                updatedScores = scores.toMap(),
                newTray       = getTrayLetters(user.userId),
                lettersInPouch = letterPouch.size
            ))

            broadcastEvent(accessCode, server, "move_accepted", MoveResultPayload(
                playerId        = user.userId,
                newlyPlacedTiles = move.placedTiles,
                pointsGained    = result.points,
                updatedScores   = scores.toMap()
            ))
            sendSysMsg(accessCode, server, "${user.nickname} ułożył słowo za ${result.points} pkt.")
            checkGameEndOrAdvanceTurn(accessCode, server)
        } else {
            ackError(when (result.status) {
                "must_contain_starting_square" -> "Pierwsze słowo musi przechodzić przez środek planszy!"
                "invalid_placement"            -> "Niedozwolony układ kafelków!"
                "bad"                          -> "Jedno lub więcej słów nie istnieje w słowniku!"
                else                           -> "Niedozwolony ruch!"
            })
        }
    }

    private fun handleSwapTiles(
        payload: SwapTilesPayload, user: User,
        accessCode: String, server: SocketIOServer, ack: AckRequest
    ) {
        fun ackError(msg: String) { if (ack.isAckRequested) ack.sendAckData(SwapAckResponse("error", message = msg)) }

        if (!isGameStarted || players[currentPlayerIndex].userId != user.userId) return ackError("Not your turn")
        val tray = playerTrays[user.userId] ?: return ackError("No tray found")

        payload.lettersToSwap.forEach { tray.remove(it) }
        letterPouch.addAll(payload.lettersToSwap)
        letterPouch.shuffle()
        refillTray(user.userId)

        consecutivePasses = 0

        if (ack.isAckRequested) ack.sendAckData(SwapAckResponse(
            status        = "ok",
            newTray       = getTrayLetters(user.userId),
            lettersInPouch = letterPouch.size
        ))

        sendSysMsg(accessCode, server, "${user.nickname} wymienił ${payload.lettersToSwap.size} liter.")
        checkGameEndOrAdvanceTurn(accessCode, server)
    }

    private fun handlePass(user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        if (!isGameStarted || players[currentPlayerIndex].userId != user.userId) {
            if (ack.isAckRequested) ack.sendAckData(PassAckResponse("error", "Not your turn"))
            return
        }
        consecutivePasses++
        if (ack.isAckRequested) ack.sendAckData(PassAckResponse("ok"))
        sendSysMsg(accessCode, server, "${user.nickname} pasuje.")
        checkGameEndOrAdvanceTurn(accessCode, server)
    }

    private fun checkGameEndOrAdvanceTurn(accessCode: String, server: SocketIOServer) {
        val isTrayEmpty = playerTrays[players[currentPlayerIndex].userId]?.isEmpty() == true

        if (consecutivePasses >= players.size * 2 || (isTrayEmpty && letterPouch.isEmpty())) {
            val winnerId = scores.maxByOrNull { it.value }?.key
            broadcastEvent(accessCode, server, "game_over", mapOf(
                "winner"      to winnerId,
                "finalScores" to scores
            ))
            sendSysMsg(accessCode, server, "Gra zakończona! Wygrywa: ${players.find { it.userId == winnerId }?.nickname}")
            isGameStarted = false
        } else {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size
            broadcastTurnStart(accessCode, server)
        }
    }
}