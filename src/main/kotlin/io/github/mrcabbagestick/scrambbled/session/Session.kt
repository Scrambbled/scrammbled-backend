package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.game.GameRepository
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

class Session(val game: GameTemplate) {
    private val users = HashMap<UUID, User>()

    fun addUser(userId: UUID, user: User) = users.put(userId, user)
    fun removeUser(userId: UUID) = users.remove(userId)
    fun getUse(userId: UUID) = users.get(userId)

    companion object{
        fun fromDTO(dto: SessionDTO): Session?{

            val game = Games.getGameByIdentifier(dto.gameId) ?: return null

            return Session(game.gameSupplier())
        }
    }
}