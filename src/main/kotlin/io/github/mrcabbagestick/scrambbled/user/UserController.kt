package io.github.mrcabbagestick.scrambbled.user

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/user")
class UserController(private val userService: UserService) {

    @GetMapping("/icon/all")
    fun getUserIcons() = userService.getAvailableIcons()
}