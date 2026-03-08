package io.github.mrcabbagestick.scrambbled.socket.listeners

import com.corundumstudio.socketio.SocketIOClient
import com.corundumstudio.socketio.listener.DisconnectListener
import io.github.mrcabbagestick.scrambbled.session.SessionService
import io.github.mrcabbagestick.scrambbled.user.UserService
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Lazy

@Component
class SocketDisconnectListener(
    private val userService: UserService,
    private val sessionService: SessionService,
    @Lazy private val serverProvider: com.corundumstudio.socketio.SocketIOServer
) : DisconnectListener {

    override fun onDisconnect(client: SocketIOClient) {
        val user = userService.getUser(client.sessionId) ?: return

        val session = sessionService.getSession(user.accessCode)
        session?.game?.onUserLeft(user, user.accessCode, serverProvider)

        if(session?.game?.shouldTerminate() == true) {
            sessionService.removeSession(user.accessCode)
        }

        userService.removeUser(client.sessionId)
    }
}