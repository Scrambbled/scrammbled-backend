package io.github.mrcabbagestick.scrambbled.socket.listeners

import com.corundumstudio.socketio.SocketIOClient
import io.github.mrcabbagestick.scrambbled.session.SessionRegistry
import io.github.mrcabbagestick.scrambbled.user.User
import io.github.mrcabbagestick.scrambbled.user.UserRegistry

class ConnectListener : (SocketIOClient) -> Unit {
    override fun invoke(listener: SocketIOClient) {
        val data = listener.handshakeData.urlParams

        val accessCode = data["accessCode"]?.get(0)

        if(accessCode == null){
            listener.disconnect()
            return
        }

        val session = SessionRegistry.getSession(accessCode)

        if(session == null){
            listener.disconnect()
            return
        }

        UserRegistry.registerUser(listener.sessionId, User(session, listener.sessionId))
    }
}