package io.github.mrcabbagestick.scrambbled.user

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/user")
class UserController {

    @GetMapping("/icon/all")
    fun getUserIcons() = listOf(
        UserIcon("sock_puppet_blue"),
        UserIcon("sock_puppet_green"),
        UserIcon("sock_puppet_pink"),
        UserIcon("sock_puppet_purple"),
        UserIcon("sock_puppet_yellow"),
    )
}

data class UserIcon(val name: String){
    val path: String = "static/user_icons/$name.png"
}