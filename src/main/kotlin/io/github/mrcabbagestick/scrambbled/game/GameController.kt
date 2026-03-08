package io.github.mrcabbagestick.scrambbled.game

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/game")
class GameController(private val gameService: GameService) {

    @GetMapping("/all")
    fun getAllGames() = gameService.getAllGamesDTOs()
}