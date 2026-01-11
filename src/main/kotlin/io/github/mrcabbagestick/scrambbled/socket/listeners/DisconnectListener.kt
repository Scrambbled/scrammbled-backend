package io.github.mrcabbagestick.scrambbled.socket.listeners

import com.corundumstudio.socketio.SocketIOClient
import io.github.mrcabbagestick.scrambbled.user.UserRegistry

class DisconnectListener: (SocketIOClient) -> Unit {
    override fun invoke(listener: SocketIOClient) {
        UserRegistry.getUser(listener.sessionId)?.session?.removeUser(listener.sessionId)
        UserRegistry.removeUser(listener.sessionId)
    }
}