package io.github.mrcabbagestick.scrambbled.game.impl.scrabble

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

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

data class ValidationResult(
    val isValid: Boolean,
    val status: String, // "good", "bad", "invalid_placement", "must_contain_starting_square"
    val points: Int = 0
)

data class CheckWordPayload(
    val placedTiles: List<PlacedTile> // Użyj swojej klasy PlacedTile
)

data class CheckWordResponse(
    val status: String, // "good", "bad", "invalid_placement", "must_contain_starting_square"
    val points: Int? = null
)

// Dodaj klasy pomocnicze na górze pliku
data class TileCoordinate(val x: Int, val y: Int, val letter: Char, val isNew: Boolean)

data class ScoredWord(
    val word: String,
    val tiles: List<TileCoordinate>
)