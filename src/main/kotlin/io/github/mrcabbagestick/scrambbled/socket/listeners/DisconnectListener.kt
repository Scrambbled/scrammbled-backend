package io.github.mrcabbagestick.scrambbled.socket.listeners

import com.corundumstudio.socketio.SocketIOClient
import io.github.mrcabbagestick.scrambbled.user.UserRegistry

class DisconnectListener: (SocketIOClient) -> Unit {
    override fun invoke(listener: SocketIOClient) {
        val userId = listener.sessionId
        val user = UserRegistry.getUser(userId)

        user?.session?.let {
            it.game.onUserLeft(user)
            it.removeUser(userId)
        }

        UserRegistry.removeUser(userId)

    }
}