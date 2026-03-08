package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.game.GameTemplate

data class Session(val accessCode: String, val game: GameTemplate)