package io.github.mrcabbagestick.scrambbled.game

import io.github.mrcabbagestick.scrambbled.user.User

abstract class GameTemplate(val game: Games){
    abstract fun onUserJoin(user: User);
    abstract fun onUserLeft(user: User);
}