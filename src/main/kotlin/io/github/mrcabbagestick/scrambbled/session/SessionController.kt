package io.github.mrcabbagestick.scrambbled.session

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/session")
class SessionController {

    @PostMapping("/create")
    // For some reason ResponseEntity<SessionCode> does not work
    fun createSession(@RequestBody sessionData: SessionDTO): ResponseEntity<String>{

        val session = Session.fromDTO(sessionData) ?:
            // Send 404 if game does not exist
            return ResponseEntity("No game with id: ${sessionData.gameId}", HttpStatus.NOT_FOUND)

        val accessCode = SessionRegistry.registerSession(session)

        return ResponseEntity(accessCode, HttpStatus.OK)
    }
}