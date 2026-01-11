package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.user.User

class Session {
    private val users = ArrayList<User>()

    fun addUser(user: User){
        users.add(user)
    }

    companion object{
        fun fromDTO(dto: SessionDTO): Session{
            return Session()
        }
    }
}