package io.github.mrcabbagestick.scrambbled.game

import org.springframework.stereotype.Service

@Service
class GameService {
    fun getAllGamesDTOs(): List<GameDTO> = Games.entries.map { it.toGameDTO() }

    fun getGameInstance(gameId: GameId): GameTemplate? {
        return Games.getGameByIdentifier(gameId)?.gameSupplier?.invoke()
    }
}