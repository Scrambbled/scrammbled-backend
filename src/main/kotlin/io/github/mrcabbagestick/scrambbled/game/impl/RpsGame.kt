package io.github.mrcabbagestick.scrambbled.game.impl

import com.corundumstudio.socketio.AckRequest
import com.corundumstudio.socketio.SocketIOServer
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.user.User
import java.util.UUID

class RpsGame : GameTemplate(Games.RPS_GAME) {
    private val players = mutableListOf<UUID>()
    private val choices = mutableMapOf<UUID, String>()

    override fun onUserJoin(user: User, accessCode: String, server: SocketIOServer) {
        if(players.size < 2 && !players.contains(user.userId)) {
            players.add(user.userId)
            server.getRoomOperations(accessCode).sendEvent("chat message", "Gracz ${user.userId.toString().substring(0,5)} dołączył jako Gracz ${players.size}.")
        } else {
            server.getRoomOperations(accessCode).sendEvent("chat message", "Gracz ${user.userId.toString().substring(0,5)} dołączył jako Obserwator.")
        }

        if(players.size == 2 && choices.isEmpty()) {
            server.getRoomOperations(accessCode).sendEvent("game_state", "Gra gotowa! Wyślijcie swój ruch (ROCK, PAPER, SCISSORS).")
        }
    }

    override fun onUserLeft(user: User, accessCode: String, server: SocketIOServer) {
        players.remove(user.userId)
        choices.remove(user.userId)
        server.getRoomOperations(accessCode).sendEvent("chat message", "Gracz opuścił grę. Czekamy na przeciwnika...")
    }

    override fun getTypeForEventName(eventName: String): Class<*>? = when (eventName) {
        "make_move" -> RpsMoveEvent::class.java
        else -> null
    }

    override fun <T> handleEvent(eventName: String, eventData: T, user: User, accessCode: String, server: SocketIOServer, ack: AckRequest) {
        if(eventName == "make_move") {
            val moveEvent = eventData as RpsMoveEvent
            val move = moveEvent.choice.uppercase()

            if(!players.contains(user.userId)) {
                ack.sendAckData("Nie jesteś graczem, możesz tylko obserwować!")
                return
            }
            if(move !in listOf("ROCK", "PAPER", "SCISSORS")) {
                ack.sendAckData("Nieprawidłowy ruch! Użyj: ROCK, PAPER lub SCISSORS")
                return
            }
            if(choices.containsKey(user.userId)) {
                ack.sendAckData("Już wykonałeś ruch! Czekaj na przeciwnika.")
                return
            }

            choices[user.userId] = move
            ack.sendAckData("Ruch zaakceptowany!")

            server.getRoomOperations(accessCode).sendEvent("chat message", "Gracz ${user.userId.toString().substring(0,5)} wykonał swój ruch.")

            if(choices.size == 2) {
                val p1Id = players[0]
                val p2Id = players[1]
                val p1Move = choices[p1Id]!!
                val p2Move = choices[p2Id]!!

                val result = resolveWinner(p1Move, p2Move)
                val finalMessage = """
                    ====== WYNIK RUNDY ======
                    Gracz 1: $p1Move
                    Gracz 2: $p2Move
                    Wynik: $result
                    =========================
                """.trimIndent()

                server.getRoomOperations(accessCode).sendEvent("game_result", finalMessage)

                choices.clear()
                server.getRoomOperations(accessCode).sendEvent("game_state", "Nowa runda! Wybierzcie swój ruch.")
            }
        }


    }

    override fun shouldTerminate(): Boolean {
        return players.isEmpty()
    }

    private fun resolveWinner(move1: String, move2: String): String {
        if(move1 == move2) return "REMIS!"
        return when (move1) {
            "ROCK" -> if (move2 == "SCISSORS") "Gracz 1 Wygrywa!" else "Gracz 2 Wygrywa!"
            "PAPER" -> if (move2 == "ROCK") "Gracz 1 Wygrywa!" else "Gracz 2 Wygrywa!"
            "SCISSORS" -> if (move2 == "PAPER") "Gracz 1 Wygrywa!" else "Gracz 2 Wygrywa!"
            else -> "Błąd"
        }
    }
}

data class RpsMoveEvent(@JsonProperty("choice") val choice: String)