package io.github.mrcabbagestick.scrambbled.game.impl.TestGame

import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.game.Games
import io.github.mrcabbagestick.scrambbled.user.User

class TestGame: GameTemplate(Games.TEST_GAME) {
    override fun onUserJoin(user: User) {
        println("User joined TestGame: ${user.userId}")
    }

    override fun onUserLeft(user: User) {
        println("User left TestGame: ${user.userId}")
    }
}