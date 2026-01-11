package io.github.mrcabbagestick.scrambbled.game

typealias GameSupplier = () -> GameTemplate

enum class Games(val gameId: String, val gameSupplier: GameSupplier) {
    ;

    fun toGameDTO() = GameDTO(gameId)

    companion object{
        val identifierGameMap: HashMap<String, Games> = HashMap()

        init {
            // Populate identifierGameMap
            entries.forEach { game -> identifierGameMap[game.gameId] = game }
        }

        fun getGameByIdentifier(identifier: String): Games?{
            return identifierGameMap[identifier]
        }
    }


}