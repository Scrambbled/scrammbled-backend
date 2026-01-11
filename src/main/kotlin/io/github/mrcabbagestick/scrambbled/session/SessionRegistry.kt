package io.github.mrcabbagestick.scrambbled.session

typealias SessionCode = String

object SessionRegistry {
    private val activeSessions: HashMap<SessionCode, Session> = HashMap();

    fun getSession(accessCode: String) = activeSessions[accessCode]

    fun removeSession(accessCode: String) = activeSessions.remove(accessCode)

    fun registerSession(session: Session): SessionCode{
        val code = generateAccessCode()
        activeSessions[code] = session
        return code
    }

    private fun generateAccessCode(): SessionCode {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val codeLength = 6

        return (1..codeLength).map { allowedChars.random() }.joinToString("")
    }
}