package io.github.mrcabbagestick.scrambbled.user

import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class UserService {
    private val users = ConcurrentHashMap<UUID, User>()

    fun registerUser(socketClientId: UUID, user: User) {
        users[socketClientId] = user
    }

    fun removeUser(socketClientId: UUID) {
        users.remove(socketClientId)
    }

    fun getUser(socketClientId: UUID): User? = users[socketClientId]

    fun getAvailableIcons(): List<UserIcon> {
        return listOf(
            UserIcon("sock_puppet_blue"),
            UserIcon("sock_puppet_green"),
            UserIcon("sock_puppet_pink"),
            UserIcon("sock_puppet_purple"),
            UserIcon("sock_puppet_yellow"),
            )
    }

    data class UserIcon(val name: String) {
        val path: String = "static/user_icons/$name.png"
    }

    fun isNicknameTaken(accessCode: String, nickname: String): Boolean {
        return users.values.any {
            it.accessCode == accessCode && it.nickname == nickname
        }
    }
}