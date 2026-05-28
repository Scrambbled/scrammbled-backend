package io.github.mrcabbagestick.scrambbled.game.impl

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.game.PlayerRole
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

class RpsGame : GameTemplate(Games.RPS_GAME) {

    private val choices = mutableMapOf<UUID, String>()

    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        val isNewPlayer = players.size < 2 && !players.contains(user)
        if (isNewPlayer) players.add(user)

        val role = if (isNewPlayer) PlayerRole.PLAYER else PlayerRole.OBSERVER
        trackAndBroadcastJoin(user, role, accessCode, server)

        if (players.size < 2) {
            sendSysMsg(accessCode, server, "Gracz ${user.nickname} dołączył jako Gracz ${players.size}. Czekamy na drugiego gracza.")
        } else {
            if (isNewPlayer) {
                sendSysMsg(accessCode, server, "Gra gotowa! ${players[0].nickname} vs ${players[1].nickname}. Wyślijcie swój ruch (ROCK, PAPER, SCISSORS).")
            } else {
                sendSysMsg(accessCode, server, "${user.nickname} dołączył jako Obserwator.")
            }
        }
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        players.remove(user)
        choices.remove(user.userId)
        trackAndBroadcastLeave(user, accessCode, server)
        sendSysMsg(accessCode, server, "${user.nickname} opuścił grę. Czekamy na przeciwnika...")
    }

    override fun getTypeForEventName(eventName: String): Class<*>? = when (eventName) {
        "make_move" -> RpsMoveEvent::class.java
        else        -> null
    }

    override fun <T> handleEvent(
        eventName: String, eventData: T,
        user: User, accessCode: String, server: SocketIOServer, ack: AckRequest
    ) {
        if (eventName != "make_move") return

        val moveEvent = eventData as RpsMoveEvent
        val move = moveEvent.choice.uppercase()

        if (!players.contains(user)) { ack.sendAckData("Nie jesteś graczem, możesz tylko obserwować!"); return }
        if (move !in listOf("ROCK", "PAPER", "SCISSORS")) { ack.sendAckData("Nieprawidłowy ruch! Użyj: ROCK, PAPER lub SCISSORS"); return }
        if (choices.containsKey(user.userId)) { ack.sendAckData("Już wykonałeś ruch! Czekaj na przeciwnika."); return }

        choices[user.userId] = move
        ack.sendAckData("Ruch zaakceptowany!")
        sendSysMsg(accessCode, server, "${user.nickname} wykonał swój ruch.")

        if (choices.size == 2) {
            val p1 = players[0]; val p2 = players[1]
            val p1Move = choices[p1.userId]!!; val p2Move = choices[p2.userId]!!
            val result = resolveWinner(p1Move, p2Move)

            broadcastEvent(accessCode, server, "game_result", RpsResultPayload(
                player1 = p1.nickname, move1 = p1Move,
                player2 = p2.nickname, move2 = p2Move,
                result  = result
            ))
            choices.clear()
            sendSysMsg(accessCode, server, "Nowa runda! Wybierzcie swój ruch.")
        }
    }

    override fun shouldTerminate(): Boolean = players.isEmpty()

    private fun resolveWinner(move1: String, move2: String): String {
        if (move1 == move2) return "DRAW"
        return when (move1) {
            "ROCK"     -> if (move2 == "SCISSORS") "PLAYER_1" else "PLAYER_2"
            "PAPER"    -> if (move2 == "ROCK")     "PLAYER_1" else "PLAYER_2"
            "SCISSORS" -> if (move2 == "PAPER")    "PLAYER_1" else "PLAYER_2"
            else       -> "ERROR"
        }
    }
}

data class RpsMoveEvent(@JsonProperty("choice") val choice: String)

/** Structured result payload — replaces the raw trimmed-string that was sent before. */
data class RpsResultPayload(
    val player1: String,
    val move1: String,
    val player2: String,
    val move2: String,
    val result: String   // "PLAYER_1" | "PLAYER_2" | "DRAW"
)