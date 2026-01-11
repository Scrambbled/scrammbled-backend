package io.github.mrcabbagestick.scrambbled.session

object SessionRegistry {
    private val activeSessions: HashMap<String, Session> = HashMap();

    fun getSession(accessCode: String) = activeSessions[accessCode]
    fun removeSession(accessCode: String) = activeSessions.remove(accessCode)
    fun addSession(accessCode: String, session: Session) = activeSessions.put(accessCode, session)
}