package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

class Session {
    private val users = HashMap<UUID, User>()

    fun addUser(userId: UUID, user: User) = users.put(userId, user)
    fun removeUser(userId: UUID) = users.remove(userId)
    fun getUse(userId: UUID) = users.get(userId)

    companion object{
        fun fromDTO(dto: SessionDTO): Session{
            return Session()
        }
    }
}