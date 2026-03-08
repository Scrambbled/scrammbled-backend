package io.github.mrcabbagestick.scrambbled.session

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/session")
class SessionController(private val sessionService: SessionService) {

    @PostMapping("/create")
    fun createSession(@RequestBody sessionData: SessionDTO): ResponseEntity<String> {
        val accessCode = sessionService.createSession(sessionData.gameId)
            ?: return ResponseEntity("No game with id: ${sessionData.gameId}", HttpStatus.NOT_FOUND)

        return ResponseEntity(accessCode, HttpStatus.OK)
    }
}