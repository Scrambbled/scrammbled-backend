package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.game.DictionaryAware
import io.github.mrcabbagestick.scrambbled.game.GameService
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.game.impl.scrabble.ScrabbleGame
import io.github.mrcabbagestick.scrambbled.tools.dictionary.DictionaryService
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

@Service
class SessionService(
    private val gameService: GameService,
    private val taskScheduler: TaskScheduler,
    private val dictionaryService: DictionaryService
) {
    private val activeSessions = ConcurrentHashMap<String, Session>()

    fun createSession(gameId: String): String? {
        val gameInstance = when (gameId) {
            Games.SCRABBLE_GAME.gameId -> {
                // ScrabbleGame manages its own dictionary via configure_game.
                // We inject a provider lambda so it can load built-in dictionaries
                // without depending on Spring directly.
                ScrabbleGame { lang -> dictionaryService.getGlobalDictionary(lang) }
            }
            else -> {
                // For all other games, delegate to GameService as before.
                val game = gameService.getGameInstance(gameId) ?: return null
                // Generic DictionaryAware games (e.g. WordsInWords) get English by default.
                if (game is DictionaryAware) {
                    game.setDictionary(dictionaryService.getGlobalDictionary("en"))
                }
                game
            }
        }

        val accessCode = generateAccessCode()
        val session = Session(accessCode, gameInstance)
        activeSessions[accessCode] = session

        taskScheduler.schedule(
            { checkAndRemoveEmptySession(accessCode) },
            Instant.now().plus(30, ChronoUnit.SECONDS)
        )

        return accessCode
    }

    fun getSession(accessCode: String): Session? = activeSessions[accessCode]

    fun removeSession(accessCode: String) {
        activeSessions.remove(accessCode)
    }

    private fun checkAndRemoveEmptySession(accessCode: String) {
        val session = activeSessions[accessCode]
        if (session != null && session.game.shouldTerminate()) {
            println("Timeout: Usuwam pustą sesję '$accessCode', nikt nie dołączył.")
            activeSessions.remove(accessCode)
        }
    }

    private fun generateAccessCode(): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..6).map { allowedChars.random() }.joinToString("")
    }
}