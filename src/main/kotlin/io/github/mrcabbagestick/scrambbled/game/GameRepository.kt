package io.github.mrcabbagestick.scrambbled.game

object GameRepository {
    fun getAllGamesDTOs() = Games.entries.map(Games::toGameDTO)

    fun getGameInstance(identifier: GameId) = Games.getGameByIdentifier(identifier)?.gameSupplier()

}