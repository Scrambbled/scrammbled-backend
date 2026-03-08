package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.game.GameService
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

@Service
class SessionService(
    private val gameService: GameService,
    private val taskScheduler: TaskScheduler
) {
    private val activeSessions = ConcurrentHashMap<String, Session>()

    fun createSession(gameId: String): String? {
        val gameInstance = gameService.getGameInstance(gameId) ?: return null
        val accessCode = generateAccessCode()

        val session = Session(accessCode, gameInstance)
        activeSessions[accessCode] = session

        val timeoutTime = Instant.now().plus(30, ChronoUnit.SECONDS)
        taskScheduler.schedule(
            { checkAndRemoveEmptySession(accessCode) },
            timeoutTime
        )

        return accessCode
    }

    fun getSession(accessCode: String): Session? = activeSessions[accessCode]

    fun removeSession(accessCode: String) {
        activeSessions.remove(accessCode)
    }

    private fun checkAndRemoveEmptySession(accessCode: String) {
        val session = activeSessions[accessCode]

        if(session != null && session.game.shouldTerminate()) {
            println("Timeout: Usuwam pustą sesję '$accessCode', nikt nie dołączył.")
            activeSessions.remove(accessCode)
        }
    }

    private fun generateAccessCode(): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..6).map { allowedChars.random() }.joinToString("")
    }
}