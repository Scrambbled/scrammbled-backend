package io.github.mrcabbagestick.scrambbled.user

import java.util.UUID

data class PlayerInfoDTO(
    val id: UUID,
    val nickname: String,
    val iconUrl: String
) {
    constructor(user: User) : this(
        id       = user.userId,
        nickname = user.nickname,
        iconUrl  = "/static/user_icons/${user.icon}.png"
    )
}