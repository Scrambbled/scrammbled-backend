package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.game.GameService
import io.github.mrcabbagestick.scrambbled.game.impl.TestGame
import io.github.mrcabbagestick.scrambbled.tools.dictionary.DictionaryService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.scheduling.TaskScheduler
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class SessionServiceTest {
    @Mock
    private lateinit var gameService: GameService
    @Mock
    private lateinit var taskScheduler: TaskScheduler
    @Mock
    private lateinit var dictionaryService: DictionaryService
    @InjectMocks
    private lateinit var sessionService: SessionService

    @Test
    fun `should create session and schedule timeout`() {
        // given
        val gameId = "test_game"
        val mockGame = TestGame()

        `when`(gameService.getGameInstance(gameId)).thenReturn(mockGame)

        // when
        val accessCode = sessionService.createSession(gameId)

        // then
        assertNotNull(accessCode)
        assertEquals(6, accessCode?.length)

        val session = sessionService.getSession(accessCode!!)
        assertNotNull(session)
        assertEquals(mockGame, session?.game)
//        assertFalse(session!!.isStarted)

        verify(taskScheduler, times(1)).schedule(any(Runnable::class.java), any(Instant::class.java))
    }

    @Test
    fun `should return null when creating session for non-existent game`() {
        // given
        `when`(gameService.getGameInstance("unknown_game")).thenReturn(null)

        // when
        val accessCode = sessionService.createSession("unknown_game")

        // then
        assertNull(accessCode)
        verify(taskScheduler, never()).schedule(any(Runnable::class.java), any(Instant::class.java))
    }
}