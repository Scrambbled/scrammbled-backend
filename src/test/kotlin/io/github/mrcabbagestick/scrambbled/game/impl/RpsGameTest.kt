package io.github.mrcabbagestick.scrambbled.game.impl

import com.corundumstudio.socketio.BroadcastOperations
import com.corundumstudio.socketio.SocketIOServer
import io.github.mrcabbagestick.scrambbled.user.User
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class RpsGameTest {
    private lateinit var game: RpsGame
    @Mock
    private lateinit var server: SocketIOServer
    @Mock
    private lateinit var roomOperations: BroadcastOperations
    private val roomCode = "ROOM_1"

    @BeforeEach
    fun setUp() {
        game = RpsGame()
        Mockito.`when`(server.getRoomOperations(roomCode)).thenReturn(roomOperations)
    }

    @Test
    fun `should notify room when first player joins`() {
        // given
        val user1 = User(UUID.randomUUID(), roomCode)

        // when
        game.onUserJoin(user1, roomCode, server)

        // then
        Mockito.verify(roomOperations).sendEvent(
            ArgumentMatchers.eq("chat message"),
            ArgumentMatchers.argThat<String> { it.contains("dołączył jako Gracz 1") }
        )
    }

    @Test
    fun `should start game when second player joins`() {
        // given
        val user1 = User(UUID.randomUUID(), roomCode)
        val user2 = User(UUID.randomUUID(), roomCode)

        // when
        game.onUserJoin(user1, roomCode, server)
        game.onUserJoin(user2, roomCode, server)

        // then
        Mockito.verify(roomOperations).sendEvent(
            ArgumentMatchers.eq("game_state"),
            ArgumentMatchers.argThat<String> { it.contains("Gra gotowa") }
        )
    }
}