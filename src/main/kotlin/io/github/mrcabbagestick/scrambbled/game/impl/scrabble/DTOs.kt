package io.github.mrcabbagestick.scrambbled.game.impl.scrabble

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.user.PlayerInfoDTO
import java.util.UUID


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

// ─── INPUT PAYLOADS ───────────────────────────────────────────────────────────

/**
 * Payload for the `configure_game` game-specific event (sent before `start_game`).
 *
 * @property language             "en" | "pl" | "custom"
 * @property gameLengthMultiplier scale factor for letter counts in the pouch.
 *   0.5 = short, 1.0 = normal (default), 1.5 = long, 2.0 = extended.
 */
data class ConfigureGamePayload @JsonCreator constructor(
    @JsonProperty("language")             val language: String = "en",
    @JsonProperty("gameLengthMultiplier") val gameLengthMultiplier: Double = 1.0
)

data class PlacedTile @JsonCreator constructor(
    @JsonProperty("letter") val letter: Char,
    @JsonProperty("x")      val x: Int,
    @JsonProperty("y")      val y: Int
)

data class SubmitMovePayload @JsonCreator constructor(
    @JsonProperty("placedTiles") val placedTiles: List<PlacedTile>
)

data class SwapTilesPayload @JsonCreator constructor(
    @JsonProperty("lettersToSwap") val lettersToSwap: List<Char>
)

data class CheckWordPayload @JsonCreator constructor(
    @JsonProperty("placedTiles") val placedTiles: List<PlacedTile>
)

// ─── BROADCAST PAYLOADS ───────────────────────────────────────────────────────

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
 * Broadcast to all players at the start of every turn.
 */
data class ScrabbleTurnStartPayload(
    val activePlayerId: UUID,
    val lettersInPouch: Int,
    val scores: Map<UUID, Int>
)

// ─── ACK RESPONSE PAYLOADS ────────────────────────────────────────────────────

data class StartGameAckResponse(
    val status: String,           // "ok" | "error"
    val message: String? = null
)

data class MoveAckResponse(
    val status: String,           // "accepted" | "error"
    val message: String? = null,
    val points: Int? = null,
    val updatedScores: Map<UUID, Int>? = null,
    val newTray: List<Letter>? = null,
    val lettersInPouch: Int? = null
)

data class SwapAckResponse(
    val status: String,           // "ok" | "error"
    val message: String? = null,
    val newTray: List<Letter>? = null,
    val lettersInPouch: Int? = null
)

data class PassAckResponse(
    val status: String,           // "ok" | "error"
    val message: String? = null
)

data class CheckWordResponse(
    val status: String,           // "good" | "bad" | "invalid_placement" | "must_contain_starting_square"
    val points: Int? = null
)

/** ACK for `get_pouch_info`. Letters are sorted alphabetically. */
data class PouchInfoResponse(
    val count: Int,
    val letters: List<Char>
)

// ─── INTERNALS ────────────────────────────────────────────────────────────────

data class ValidationResult(
    val isValid: Boolean,
    val status: String,
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