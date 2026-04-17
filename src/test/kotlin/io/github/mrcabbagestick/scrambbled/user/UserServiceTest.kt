package io.github.mrcabbagestick.scrambbled.user

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class UserServiceTest {
    private lateinit var userService: UserService

    @BeforeEach
    fun setUp() {
        userService = UserService()
    }

    @Test
    fun `should register and retrieve user successfully`() {
        // given
        val userId = UUID.randomUUID()
        val user = User(userId, "ROOM123", "Gracz 1", "default")

        // when
        userService.registerUser(userId, user)
        val retrievedUser = userService.getUser(userId)

        // then
        Assertions.assertNotNull(retrievedUser)
        Assertions.assertEquals(userId, retrievedUser?.userId)
        Assertions.assertEquals("ROOM123", retrievedUser?.accessCode)
    }

    @Test
    fun `should return null for non-existent user`() {
        val retrievedUser = userService.getUser(UUID.randomUUID())
        Assertions.assertNull(retrievedUser)
    }

    @Test
    fun `should remove user successfully`() {
        val userId = UUID.randomUUID()
        userService.registerUser(userId, User(userId, "ROOM123", "Gracz 1", "default"))

        userService.removeUser(userId)

        Assertions.assertNull(userService.getUser(userId))
    }
}