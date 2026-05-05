package io.github.mrcabbagestick.scrambbled.socket.listeners

import com.corundumstudio.socketio.SocketIOClient
import com.corundumstudio.socketio.listener.ConnectListener
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.session.SessionService
import io.github.mrcabbagestick.scrambbled.user.User
import io.github.mrcabbagestick.scrambbled.user.UserService
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Lazy
import java.util.Timer
import java.util.TimerTask

@Component
class SocketConnectListener(
    private val sessionService: SessionService,
    private val userService: UserService,
    @Lazy private val serverProvider: com.corundumstudio.socketio.SocketIOServer
) : ConnectListener {

    override fun onConnect(client: SocketIOClient) {
        val accessCode = client.handshakeData.urlParams["accessCode"]?.get(0)
        val nickname = client.handshakeData.urlParams["nickname"]?.get(0) ?: "Anonymous"
        val icon = client.handshakeData.urlParams["icon"]?.get(0) ?: "sock_puppet_blue"

        if(accessCode == null) {
            client.disconnect()
            return
        }

        if(userService.isNicknameTaken(accessCode, nickname)) {
            val errorPayload = GameTemplate.ServerMessagePayload("Nickname '${nickname}' is already taken")
            client.sendEvent("join error", errorPayload)

            Timer().schedule(object : TimerTask() {
                override fun run() {
                    client.disconnect()
                }
            }, 100)

            return
        }

        val session = sessionService.getSession(accessCode)
        if(session == null) {
            client.sendEvent("join error", GameTemplate.ServerMessagePayload("Room '$accessCode' does not exist."))
            Timer().schedule(object : TimerTask() { override fun run() { client.disconnect() } }, 100)
            return
        }

        val user = User(client.sessionId, accessCode, nickname, icon)
        userService.registerUser(client.sessionId, user)

        client.joinRoom(accessCode)

        session.game.onUserJoin(user, accessCode, serverProvider)
    }
}