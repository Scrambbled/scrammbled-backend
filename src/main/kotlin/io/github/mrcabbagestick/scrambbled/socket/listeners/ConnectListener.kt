package io.github.mrcabbagestick.scrambbled.socket.listeners

import com.corundumstudio.socketio.SocketIOClient
import com.corundumstudio.socketio.listener.ConnectListener
import io.github.mrcabbagestick.scrambbled.session.SessionService
import io.github.mrcabbagestick.scrambbled.user.User
import io.github.mrcabbagestick.scrambbled.user.UserService
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Lazy

@Component
class SocketConnectListener(
    private val sessionService: SessionService,
    private val userService: UserService,
    @Lazy private val serverProvider: com.corundumstudio.socketio.SocketIOServer
) : ConnectListener {

    override fun onConnect(client: SocketIOClient) {
        val accessCode = client.handshakeData.urlParams["accessCode"]?.get(0)

        if(accessCode == null) {
            client.disconnect()
            return
        }

        val session = sessionService.getSession(accessCode)
        if(session == null) {
            client.disconnect()
            return
        }

        val user = User(client.sessionId, accessCode)
        userService.registerUser(client.sessionId, user)

        client.joinRoom(accessCode)

        session.game.onUserJoin(user, accessCode, serverProvider)
    }
}