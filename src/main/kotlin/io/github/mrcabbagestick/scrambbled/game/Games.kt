package io.github.mrcabbagestick.scrambbled.game

import io.github.mrcabbagestick.scrambbled.game.impl.TestGame.TestGame

typealias GameId = String
typealias GameSupplier = () -> GameTemplate

enum class Games(val gameId: GameId, val gameSupplier: GameSupplier) {
    TEST_GAME("test_game", ::TestGame);

    fun toGameDTO() = GameDTO(gameId)

    companion object{
        val identifierGameMap: HashMap<String, Games> = HashMap()

        init {
            // Populate identifierGameMap
            entries.forEach { game -> identifierGameMap[game.gameId] = game }
        }

        fun getGameByIdentifier(identifier: GameId): Games?{
            return identifierGameMap[identifier]
        }
    }


}