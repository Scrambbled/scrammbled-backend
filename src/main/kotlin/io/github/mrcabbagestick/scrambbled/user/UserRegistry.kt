package io.github.mrcabbagestick.scrambbled.user

import java.util.UUID

object UserRegistry {
    val users: HashMap<UUID, User> = HashMap()

    fun registerUser(socketClientId: UUID, user: User) = users.put(socketClientId, user)
    fun removeUser(socketClientId: UUID) = users.remove(socketClientId)
    fun getUser(socketClientId: UUID) = users.get(socketClientId)
}