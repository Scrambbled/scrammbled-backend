package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/session")
class SessionController(private val sessionService: SessionService, private val userService: UserService) {

    @PostMapping("/create")
    fun createSession(@RequestBody sessionData: SessionDTO): ResponseEntity<String> {
        val accessCode = sessionService.createSession(sessionData.gameId)
            ?: return ResponseEntity("No game with id: ${sessionData.gameId}", HttpStatus.NOT_FOUND)

        return ResponseEntity(accessCode, HttpStatus.OK)
    }

    @GetMapping("/check-join")
    fun checkJoinAbility(
        @RequestParam accessCode: String,
        @RequestParam nickname: String
    ): ResponseEntity<String> {
        // 1. Check if access code exists
        val session = sessionService.getSession(accessCode)
            ?: return ResponseEntity("Room '${accessCode}' does not exist", HttpStatus.NOT_FOUND)

        // 2. Check if nickname is available
        if(userService.isNicknameTaken(accessCode, nickname)) {
            return ResponseEntity("Nickname '${nickname}' is already taken", HttpStatus.CONFLICT)
        }

        // Everything is fine
        return ResponseEntity("OK", HttpStatus.OK)
    }
}