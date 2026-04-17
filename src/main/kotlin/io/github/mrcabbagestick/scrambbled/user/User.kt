package io.github.mrcabbagestick.scrambbled.user

import java.util.UUID

data class User(val userId: UUID, val accessCode: String, val nickname: String, val icon: String)