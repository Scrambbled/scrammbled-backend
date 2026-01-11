package io.github.mrcabbagestick.scrambbled.session

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/session")
class SessionController {

    @PostMapping("/create")
    fun createSession(@RequestBody sessionData: SessionDTO): SessionCode{
        val session = Session.fromDTO(sessionData)
        val accessCode = SessionRegistry.registerSession(session)
        return accessCode
    }
}