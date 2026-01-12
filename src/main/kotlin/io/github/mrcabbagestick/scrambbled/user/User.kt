package io.github.mrcabbagestick.scrambbled.user

import io.github.mrcabbagestick.scrambbled.session.Session
import java.util.*

data class User(val session: Session, val userId: UUID){
    // This is possibly stupid, remove if so
    init {
        // Add user to their session
        session.addUser(userId, this)
    }
}