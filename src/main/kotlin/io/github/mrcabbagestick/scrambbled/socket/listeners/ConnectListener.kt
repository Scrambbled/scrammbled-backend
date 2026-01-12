package io.github.mrcabbagestick.scrambbled.socket.listeners

import com.corundumstudio.socketio.SocketIOClient
import io.github.mrcabbagestick.scrambbled.session.SessionRegistry
import io.github.mrcabbagestick.scrambbled.user.User
import io.github.mrcabbagestick.scrambbled.user.UserRegistry

class ConnectListener : (SocketIOClient) -> Unit {
    override fun invoke(listener: SocketIOClient) {
        val data = listener.handshakeData.urlParams

        val accessCode = data["accessCode"]?.get(0) ?: return listener.disconnect()

        val session = SessionRegistry.getSession(accessCode)?: return listener.disconnect()

        val user = User(session, listener.sessionId)
        UserRegistry.registerUser(listener.sessionId, user)

        session.game.onUserJoin(user)
    }
}