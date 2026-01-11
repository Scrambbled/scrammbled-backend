package io.github.mrcabbagestick.scrambbled.user

import io.github.mrcabbagestick.scrambbled.session.Session

class User(val session: Session){
    // This is possibly stupid, remove if so
    init {
        // Add user to their session
        session.addUser(this)
    }
}