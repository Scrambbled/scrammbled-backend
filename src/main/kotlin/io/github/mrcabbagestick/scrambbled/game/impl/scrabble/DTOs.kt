package io.github.mrcabbagestick.scrambbled.game.impl.scrabble

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

data class PlayerInfoDTO(
    val id: UUID,
    val nickname: String,
    val iconUrl: String
) {
    constructor(user: User) : this(
        id = user.userId,
        nickname = user.nickname,
        iconUrl = "/static/user_icons/${user.icon}.png"
    )
}

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

// --- INPUT PAYLOADS ---

data class PlacedTile(
    @JsonProperty("letter") val letter: Char,
    @JsonProperty("x") val x: Int,
    @JsonProperty("y") val y: Int
)

data class SubmitMovePayload @JsonCreator constructor(
    @JsonProperty("placedTiles") val placedTiles: List<PlacedTile>
)

data class SwapTilesPayload @JsonCreator constructor(
    @JsonProperty("lettersToSwap") val lettersToSwap: List<Char>
)

data class CheckWordPayload(
    @JsonProperty("placedTiles") val placedTiles: List<PlacedTile>
)

// --- BROADCAST PAYLOADS ---

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

/**
 * Broadcast to all players when it's a new turn.
 * Contains all info the frontend needs to update the UI.
 */
data class ScrabbleTurnStartPayload(
    val activePlayerId: UUID,
    val lettersInPouch: Int,
    val scores: Map<UUID, Int>
)

// --- ACK RESPONSE PAYLOADS ---

/**
 * ACK for start_game (sent only to host).
 * Broadcasts start_game and tray_update events go separately to all players.
 */
data class StartGameAckResponse(
    val status: String,           // "ok" | "error"
    val message: String? = null
)

/**
 * ACK for submit_move.
 * On "accepted" includes the player's refreshed tray and updated scores.
 * On "error" includes the reason.
 */
data class MoveAckResponse(
    val status: String,                       // "accepted" | "error"
    val message: String? = null,
    val points: Int? = null,
    val updatedScores: Map<UUID, Int>? = null,
    val newTray: List<Letter>? = null,
    val lettersInPouch: Int? = null
)

/**
 * ACK for swap_tiles.
 */
data class SwapAckResponse(
    val status: String,           // "ok" | "error"
    val message: String? = null,
    val newTray: List<Letter>? = null,
    val lettersInPouch: Int? = null
)

/**
 * ACK for pass.
 */
data class PassAckResponse(
    val status: String,           // "ok" | "error"
    val message: String? = null
)

/**
 * ACK for check_word.
 */
data class CheckWordResponse(
    val status: String,           // "good" | "bad" | "invalid_placement" | "must_contain_starting_square"
    val points: Int? = null
)

/**
 * ACK for get_pouch_info.
 * Lists remaining letter counts so the frontend can show what's left in the bag.
 */
data class PouchInfoResponse(
    val count: Int,
    /** Remaining letters sorted alphabetically (standard Scrabble rules allow viewing remaining letters). */
    val letters: List<Char>
)

// --- INTERNALS ---

data class ValidationResult(
    val isValid: Boolean,
    val status: String,           // "good" | "bad" | "invalid_placement" | "must_contain_starting_square"
    val points: Int = 0
)

data class TileCoordinate(val x: Int, val y: Int, val letter: Char, val isNew: Boolean)

data class ScoredWord(
    val word: String,
    val tiles: List<TileCoordinate>
)

data class Letter(
    val letter: Char,
    val points: Int
)

data class TrayUpdate(
    val tray: List<Letter>
)