package io.github.mrcabbagestick.scrambbled.game

import io.github.mrcabbagestick.scrambbled.game.impl.RpsGame
import io.github.mrcabbagestick.scrambbled.game.impl.TestGame
import io.github.mrcabbagestick.scrambbled.game.impl.WordsInWordsGame

typealias GameId = String
typealias GameSupplier = () -> GameTemplate

enum class Games(val gameId: GameId, val gameSupplier: GameSupplier) {
    TEST_GAME("test_game", ::TestGame),
    RPS_GAME("rps_game", ::RpsGame),
    WORDS_IN_WORDS("Words_in_words_game", ::WordsInWordsGame);

    fun toGameDTO() = GameDTO(gameId)

    companion object {
        fun getGameByIdentifier(identifier: GameId): Games? {
            return entries.find { it.gameId == identifier }
        }
    }
}