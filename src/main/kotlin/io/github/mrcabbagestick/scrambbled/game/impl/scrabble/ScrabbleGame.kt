package io.github.mrcabbagestick.scrambbled.game.impl.scrabble

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mrcabbagestick.scrambbled.game.DictionaryAware
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.game.PlayerRole
import io.github.mrcabbagestick.scrambbled.game.impl.scrabble.PlacementValidator.validateMove
import io.github.mrcabbagestick.scrambbled.tools.dictionary.WordDictionary
import io.github.mrcabbagestick.scrambbled.user.PlayerInfoDTO
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * @param dictionaryProvider Callback used by [handleConfigureGame] to load a built-in
 *   dictionary by language code ("en", "pl", …). Defaults to `{ null }` so the no-arg
 *   constructor reference `::ScrabbleGame` in [Games] still compiles; the real provider
 *   is injected by [SessionService] at session-creation time.
 */
class ScrabbleGame(
    private val dictionaryProvider: (String) -> WordDictionary? = { null }
) : GameTemplate(Games.SCRABBLE_GAME), DictionaryAware {

    private val objectMapper = ObjectMapper()

    // --- RUNTIME CONFIG (set via configure_game / upload_letter_values events) ---

    private var activeDictionary: WordDictionary? = dictionaryProvider("en")
    private var customLetterValues: Map<Char, Int>? = null
    private var customLetterDistribution: Map<Char, Int>? = null

    /**
     * Scale factor applied to every letter count when filling the pouch.
     *  0.5 → short game (~half the tiles)
     *  1.0 → default (no change)
     *  1.5 → long
     *  2.0 → extended
     */
    private var gameLengthMultiplier: Double = 1.0

    /** Effective letter values: custom if uploaded, otherwise English defaults. */
    val effectiveLetterValues: Map<Char, Int>
        get() = customLetterValues ?: defaultLetterValues

    /** Effective distribution: custom if uploaded, otherwise English defaults. */
    private val effectiveLetterDistribution: Map<Char, Int>
        get() = customLetterDistribution ?: defaultLetterDistribution

    override fun setDictionary(dictionary: WordDictionary?) {
        activeDictionary = dictionary
    }

    // --- STATIC DEFAULTS ---

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

    // --- GAME STATE ---

    private val scores = mutableMapOf<UUID, Int>()
    private val letterPouch = mutableListOf<Char>()
    private val playerTrays = mutableMapOf<UUID, MutableList<Char>>()
    private var activeSpecials = mapOf<Pair<Int, Int>, SpecialSquare>()

    val board = Array(15) { Array<Char?>(15) { null } }
    private var isFirstMove = true
    private var isGameStarted = false
    private var currentPlayerIndex = 0
    private var consecutivePasses = 0

    /**
     * Number of turns remaining in the last round.
     * -1 = normal play.
     *  N = last round in progress; decremented after each turn (including passes).
     *  0 = trigger game end.
     *
     * Set to [players.size] when the pouch becomes empty after any accepted move,
     * giving every player exactly one final turn (starting from the next player,
     * which means the player who emptied the bag plays last in the final round
     * since the rotation naturally wraps around).
     */
    private var lastRoundTurnsLeft: Int = -1

    // --- PUBLIC API (called from SocketIOConfig for file uploads) ---

    /**
     * Parses a letter-config JSON file and applies values it contains.
     * Called by `SocketIOConfig` when the host uploads via `upload-letter-values`.
     *
     * JSON (both keys optional):
     * ```json
     * {
     *   "letterValues":       { "A": 1, "B": 3, … },
     *   "letterDistribution": { "A": 9, "B": 2, … }
     * }
     * ```
     * @return structured result so the caller can build a typed ACK response.
     * @throws Exception if JSON cannot be parsed.
     */
    fun applyLetterConfig(jsonData: ByteArray): LetterConfigApplyResult {
        val raw = objectMapper.readValue(jsonData, RawLetterConfigJson::class.java)

        var letterValuesCount = 0
        var distributionCount = 0

        raw.letterValues
            ?.mapKeys { (k, _) -> k.trim().uppercase().first() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { customLetterValues = it; letterValuesCount = it.size }

        raw.letterDistribution
            ?.mapKeys { (k, _) -> k.trim().uppercase().first() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { customLetterDistribution = it; distributionCount = it.size }

        if (letterValuesCount == 0 && distributionCount == 0)
            throw IllegalArgumentException("No valid letterValues or letterDistribution found in JSON")

        return LetterConfigApplyResult(letterValuesCount, distributionCount)
    }

    // --- HELPERS ---

    /** Converts a [PlacedTile] (input type, no points) to a [BoardTile] (output type, with points). */
    private fun PlacedTile.toBoardTile() =
        BoardTile(letter = letter, points = effectiveLetterValues[letter] ?: 0, x = x, y = y)

    /** Collects all occupied cells as [BoardTile] list. */
    private fun buildBoardTiles(): List<BoardTile> {
        val tiles = mutableListOf<BoardTile>()
        for (y in 0..14) {
            for (x in 0..14) {
                val letter = board[y][x] ?: continue
                tiles.add(BoardTile(letter, effectiveLetterValues[letter] ?: 0, x, y))
            }
        }
        return tiles
    }

    // --- INITIALIZATION ---

    /**
     * Fills the pouch using [effectiveLetterDistribution] scaled by [gameLengthMultiplier].
     * When multiplier == 1.0 counts are used verbatim.  Otherwise each count is rounded to
     * the nearest integer and clamped to ≥ 1 so no letter disappears entirely.
     */
    private fun initializeLetterPouch() {
        letterPouch.clear()
        effectiveLetterDistribution.forEach { (letter, base) ->
            val count = if (gameLengthMultiplier == 1.0) base
            else max(1, (base * gameLengthMultiplier).roundToInt())
            repeat(count) { letterPouch.add(letter) }
        }
        letterPouch.shuffle()
    }

    private fun generateBoardData(): BoardData {
        val specials = mutableListOf<SpecialSquare>()
        fun add(coords: List<Pair<Int, Int>>, wm: Int, lm: Int) =
            coords.forEach { (x, y) ->
                specials.add(SpecialSquare(x = x, y = y, wordMultiplier = wm, letterMultiplier = lm))
            }

        add(listOf(0 to 0, 0 to 7, 0 to 14, 7 to 0, 7 to 14, 14 to 0, 14 to 7, 14 to 14), 3, 1)
        add(listOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 10 to 10, 11 to 11, 12 to 12, 13 to 13,
            1 to 13, 2 to 12, 3 to 11, 4 to 10, 13 to 1, 12 to 2, 11 to 3, 10 to 4, 7 to 7), 2, 1)
        add(listOf(1 to 5, 1 to 9, 5 to 1, 5 to 5, 5 to 9, 5 to 13,
            9 to 1, 9 to 5, 9 to 9, 9 to 13, 13 to 5, 13 to 9), 1, 3)
        add(listOf(0 to 3, 0 to 11, 2 to 6, 2 to 8, 3 to 0, 3 to 7, 3 to 14,
            6 to 2, 6 to 6, 6 to 8, 6 to 12, 7 to 3, 7 to 11,
            8 to 2, 8 to 6, 8 to 8, 8 to 12, 11 to 0, 11 to 7, 11 to 14,
            12 to 6, 12 to 8, 14 to 3, 14 to 11), 1, 2)

        activeSpecials = specials.associateBy { Pair(it.x, it.y) }
        return BoardData(15, 15, Coordinates(7, 7), specials)
    }

    private fun refillTray(playerId: UUID) {
        val tray = playerTrays.getOrPut(playerId) { mutableListOf() }
        while (tray.size < 7 && letterPouch.isNotEmpty()) tray.add(letterPouch.removeAt(0))
    }

    private fun getTrayLetters(playerId: UUID): List<Letter> =
        (playerTrays[playerId] ?: emptyList()).map { Letter(it, effectiveLetterValues[it] ?: 0) }

    /** Sends personalised tray_update to every player — used only at game start. */
    private fun sendTrayUpdate(accessCode: String, server: SocketIOServer) =
        players.forEach { sendToUser(it, server, "tray_update", TrayUpdate(getTrayLetters(it.userId))) }

    // --- GAME LOOP ---

    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        val isNewPlayer = !isGameStarted && !players.any { it.userId == user.userId }

        if (isNewPlayer) {
            players.add(user)
            if (host == null) {
                host = user
                // Broadcast new host info to everyone
                broadcastEvent(accessCode, server, "host_assigned", PlayerInfoDTO(user))
            }
        }

        val role = if (isNewPlayer) PlayerRole.PLAYER else PlayerRole.OBSERVER

        // Registers in connectedMembers, broadcasts player_joined to all,
        // and sends room_state snapshot to the newcomer.
        trackAndBroadcastJoin(user, role, accessCode, server)

        // Late-joining observer gets a board snapshot directly since turn_start
        // won't fire again just for them.
        if (!isNewPlayer && isGameStarted) {
            sendToUser(user, server, "board_state", BoardStatePayload(buildBoardTiles()))
        }
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        players.removeIf { it.userId == user.userId }

        if (user == host) {
            host = players.firstOrNull()
            host?.let {
                broadcastEvent(accessCode, server, "host_assigned", PlayerInfoDTO(it))
                sendSysMsg(accessCode, server, "${it.nickname} jest teraz hostem.")
            }
        }

        // Removes from connectedMembers and broadcasts player_left to all.
        trackAndBroadcastLeave(user, accessCode, server)

        if (players.isEmpty()) isGameStarted = false
    }

    override fun shouldTerminate(): Boolean = players.isEmpty()

    override fun getTypeForEventName(eventName: String): Class<*>? = when (eventName) {
        "configure_game"  -> ConfigureGamePayload::class.java
        "start_game"      -> Any::class.java
        "submit_move"     -> SubmitMovePayload::class.java
        "swap_tiles"      -> SwapTilesPayload::class.java
        "pass"            -> Any::class.java
        "check_word"      -> CheckWordPayload::class.java
        "get_pouch_info"  -> Any::class.java
        else              -> null
    }

    override fun <T> handleEvent(
        eventName: String, eventData: T,
        user: User, accessCode: String, server: SocketIOServer, ack: AckRequest
    ) {
        when (eventName) {
            "configure_game"  -> handleConfigureGame(eventData as ConfigureGamePayload, user, accessCode, server, ack)
            "start_game"      -> handleStartGame(accessCode, server, user, ack)
            "submit_move"     -> handleSubmitMove(eventData as SubmitMovePayload, user, accessCode, server, ack)
            "swap_tiles"      -> handleSwapTiles(eventData as SwapTilesPayload, user, accessCode, server, ack)
            "pass"            -> handlePass(user, accessCode, server, ack)
            "check_word"      -> handleCheckWord(eventData as CheckWordPayload, ack)
            "get_pouch_info"  -> handleGetPouchInfo(ack)
        }
    }

    // --- EVENT HANDLERS ---

    /**
     * Sets language (loads the dictionary via [dictionaryProvider]) and game-length multiplier.
     * Must be called by the host before [handleStartGame].
     *
     * For "custom" language the host uploads a dictionary via the top-level `dictionary-upload`
     * event and letter values via the top-level `upload-letter-values` event separately —
     * both of those call back into this class through [setDictionary] / [applyLetterConfig].
     */
    private fun handleConfigureGame(
        payload: ConfigureGamePayload,
        user: User, accessCode: String, server: SocketIOServer, ack: AckRequest
    ) {
        fun ackError(msg: String) {
            if (ack.isAckRequested) ack.sendAckData(mapOf("status" to "error", "message" to msg))
        }

        if (user.userId != host?.userId) return ackError("Only the host can configure the game")
        if (isGameStarted)               return ackError("Cannot configure a game that has already started")

        gameLengthMultiplier = payload.gameLengthMultiplier.coerceAtLeast(0.1)

        when (val lang = payload.language.lowercase()) {
            "en", "pl" -> {
                val dict = dictionaryProvider(lang)
                    ?: return ackError("Dictionary '$lang' is not available on the server")
                activeDictionary = dict
                sendSysMsg(accessCode, server,
                    "Język: ${lang.uppercase()}, mnożnik długości gry: ${payload.gameLengthMultiplier}×.")
            }
            "custom" -> {
                // Dictionary arrives via dictionary-upload; letter values via upload-letter-values.
                sendSysMsg(accessCode, server,
                    "Tryb własny – prześlij słownik i plik wartości liter przed startem. " +
                            "Mnożnik: ${payload.gameLengthMultiplier}×.")
            }
            else -> return ackError("Unknown language: ${payload.language}")
        }

        if (ack.isAckRequested) ack.sendAckData(mapOf(
            "status"               to "ok",
            "language"             to payload.language,
            "gameLengthMultiplier" to payload.gameLengthMultiplier
        ))
    }

    private fun handleStartGame(accessCode: String, server: SocketIOServer, user: User, ack: AckRequest) {
        fun ackError(msg: String) { if (ack.isAckRequested) ack.sendAckData(StartGameAckResponse("error", msg)) }

        if (user.userId != host?.userId) return ackError("Only the host can start the game")
        if (isGameStarted)               return ackError("Game has already started")
        if (players.isEmpty())           return ackError("No players in the room")

        isGameStarted = true
        consecutivePasses = 0
        lastRoundTurnsLeft = -1
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

    private fun handleCheckWord(payload: CheckWordPayload, ack: AckRequest) {
        if (!ack.isAckRequested) return
        if (!isGameStarted) { ack.sendAckData(CheckWordResponse("invalid_placement")); return }

        val result = validateMove(board, payload.placedTiles, isFirstMove,
            activeDictionary, effectiveLetterValues, activeSpecials)
        ack.sendAckData(CheckWordResponse(result.status, if (result.isValid) result.points else null))
    }

    private fun handleGetPouchInfo(ack: AckRequest) {
        if (!ack.isAckRequested) return
        ack.sendAckData(PouchInfoResponse(count = letterPouch.size, letters = letterPouch.sorted()))
    }

    /**
     * Broadcast at the start of every turn.
     */
    private fun broadcastTurnStart(accessCode: String, server: SocketIOServer) =
        broadcastEvent(accessCode, server, "turn_start", ScrabbleTurnStartPayload(
            activePlayerId = players[currentPlayerIndex].userId,
            lettersInPouch = letterPouch.size,
            scores         = scores.toMap(),
            board          = buildBoardTiles(),
            isLastRound     = lastRoundTurnsLeft >= 0,
            lastRoundTurnsLeft = if (lastRoundTurnsLeft >= 0) lastRoundTurnsLeft else null
        ))

    private fun handleSubmitMove(
        move: SubmitMovePayload, user: User,
        accessCode: String, server: SocketIOServer, ack: AckRequest
    ) {
        fun ackError(msg: String) { if (ack.isAckRequested) ack.sendAckData(MoveAckResponse("error", message = msg)) }

        if (!isGameStarted || players[currentPlayerIndex].userId != user.userId) return ackError("Not your turn")
        val tray     = playerTrays[user.userId] ?: return ackError("No tray found")
        val tempTray = tray.toMutableList()

        for (letter in move.placedTiles.map { it.letter }) {
            if (!tempTray.remove(letter)) return ackError("Nie masz odpowiednich liter na tacce!")
        }

        val result = PlacementValidator.validateMove(
            board, move.placedTiles, isFirstMove, activeDictionary, effectiveLetterValues, activeSpecials)

        if (result.isValid) {
            isFirstMove = false
            consecutivePasses = 0
            scores[user.userId] = (scores[user.userId] ?: 0) + result.points
            move.placedTiles.forEach { board[it.y][it.x] = it.letter }
            playerTrays[user.userId] = tempTray
            refillTray(user.userId)

            // If the pouch just ran out, start the last round
            if (letterPouch.isEmpty() && lastRoundTurnsLeft < 0) {
                lastRoundTurnsLeft = players.size
                sendSysMsg(accessCode, server,
                    "Worek z literami jest pusty! Każdy gracz otrzymuje jeszcze jedną turę.")
            }

            if (ack.isAckRequested) ack.sendAckData(MoveAckResponse(
                status         = "accepted",
                points         = result.points,
                updatedScores  = scores.toMap(),
                newTray        = getTrayLetters(user.userId),
                lettersInPouch = letterPouch.size
            ))
            broadcastEvent(accessCode, server, "move_accepted", MoveResultPayload(
                playerId         = user.userId,
                newlyPlacedTiles = move.placedTiles.map { it.toBoardTile() },
                pointsGained     = result.points,
                updatedScores    = scores.toMap()
            ))
            sendSysMsg(accessCode, server, "${user.nickname} ułożył słowo za ${result.points} pkt.")
            advanceTurn(accessCode, server)
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

        // Swapping tiles is only allowed while the pouch still has tiles
        if (letterPouch.isEmpty()) return ackError("Nie można wymieniać liter gdy worek jest pusty!")

        val tray = playerTrays[user.userId] ?: return ackError("No tray found")

        payload.lettersToSwap.forEach { tray.remove(it) }
        letterPouch.addAll(payload.lettersToSwap)
        letterPouch.shuffle()
        refillTray(user.userId)
        consecutivePasses = 0

        if (ack.isAckRequested) ack.sendAckData(SwapAckResponse(
            status         = "ok",
            newTray        = getTrayLetters(user.userId),
            lettersInPouch = letterPouch.size
        ))
        sendSysMsg(accessCode, server, "${user.nickname} wymienił ${payload.lettersToSwap.size} liter.")
        advanceTurn(accessCode, server)
    }

    private fun handlePass(user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        if (!isGameStarted || players[currentPlayerIndex].userId != user.userId) {
            if (ack.isAckRequested) ack.sendAckData(PassAckResponse("error", "Not your turn"))
            return
        }
        consecutivePasses++
        if (ack.isAckRequested) ack.sendAckData(PassAckResponse("ok"))
        sendSysMsg(accessCode, server, "${user.nickname} pasuje.")
        advanceTurn(accessCode, server)
    }

    /**
     * Advances the turn pointer and checks end conditions.
     *
     * End conditions (checked in order):
     * 1. All players passed [players.size * 2] times in a row (deadlock — no one can play).
     * 2. Last round is in progress and all last-round turns have been taken.
     * 3. Otherwise — move to the next player normally.
     */
    private fun advanceTurn(accessCode: String, server: SocketIOServer) {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size

        // Decrement last-round counter if applicable
        if (lastRoundTurnsLeft > 0) lastRoundTurnsLeft--

        when {
            consecutivePasses >= players.size * 2 -> {
                // Everyone passed repeatedly — total deadlock, end immediately
                endGame(accessCode, server, reason = "deadlock")
            }
            lastRoundTurnsLeft == 0 -> {
                // Last round finished — every player had their final turn
                endGame(accessCode, server, reason = "pouch_empty")
            }
            else -> broadcastTurnStart(accessCode, server)
        }
    }

    /**
     * Ends the game.
     *
     * Standard Scrabble scoring adjustment:
     * - Each player's remaining tray value is subtracted from their score.
     * - If one player emptied their tray, the sum of all other players' remaining
     *   tiles is added to that player's score.
     *
     * [reason] is forwarded to the frontend so it can show a context-appropriate message.
     */
    private fun endGame(accessCode: String, server: SocketIOServer, reason: String) {
        // Calculate tray penalties / bonuses
        val trayValues = players.associate { player ->
            player.userId to (playerTrays[player.userId] ?: emptyList())
                .sumOf { effectiveLetterValues[it] ?: 0 }
        }

        val emptyTrayPlayer = players.find { (playerTrays[it.userId]?.isEmpty() == true) }

        if (emptyTrayPlayer != null) {
            // That player gains the sum of everyone else's remaining tiles
            val bonus = trayValues.filterKeys { it != emptyTrayPlayer.userId }.values.sum()
            scores[emptyTrayPlayer.userId] = (scores[emptyTrayPlayer.userId] ?: 0) + bonus
            // Everyone else loses their tray value
            players.filter { it.userId != emptyTrayPlayer.userId }.forEach { player ->
                scores[player.userId] = (scores[player.userId] ?: 0) - (trayValues[player.userId] ?: 0)
            }
        } else {
            // No one emptied their tray — everyone just loses their remaining tile values
            players.forEach { player ->
                scores[player.userId] = (scores[player.userId] ?: 0) - (trayValues[player.userId] ?: 0)
            }
        }

        val finalScores = scores.toMap()
        val winnerId = finalScores.maxByOrNull { it.value }?.key

        broadcastEvent(accessCode, server, "game_over", GameOverPayload(
            winnerId     = winnerId,
            finalScores  = finalScores,
            trayPenalties = trayValues,
            reason        = reason    // "pouch_empty" | "deadlock"
        ))

        sendSysMsg(accessCode, server,
            "Gra zakończona! Wygrywa: ${players.find { it.userId == winnerId }?.nickname}")

        isGameStarted = false
    }
}

// ─── INTERNAL JSON MODEL (used only by applyLetterConfig) ─────────────────────

/** Raw Jackson target — String keys, normalised to Char after parsing. */
private data class RawLetterConfigJson(
    val letterValues: Map<String, Int>? = null,
    val letterDistribution: Map<String, Int>? = null
)

/** Returned by [ScrabbleGame.applyLetterConfig] so [SocketIOConfig] can build a typed ACK. */
data class LetterConfigApplyResult(
    val letterValuesCount: Int,
    val distributionCount: Int
)