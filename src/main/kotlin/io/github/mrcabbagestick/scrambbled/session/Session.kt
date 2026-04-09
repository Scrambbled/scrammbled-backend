package io.github.mrcabbagestick.scrambbled.session

import io.github.mrcabbagestick.scrambbled.game.GameTemplate
import io.github.mrcabbagestick.scrambbled.tools.dictionary.DictionaryService
import io.github.mrcabbagestick.scrambbled.tools.dictionary.WordDictionary

data class Session(
    val accessCode: String,
    val game: GameTemplate,
    var languageCode: String = "pl",
    var customDictionary: WordDictionary? = null
) {
    /**
     * Returns the active dictionary for this session.
     * If a custom dictionary is set, it will be returned.
     * Otherwise, the global dictionary for the language code will be returned (default: "pl").
     */
    fun getActiveDictionary(dictionaryService: DictionaryService): WordDictionary? {
        return customDictionary ?: dictionaryService.getGlobalDictionary(languageCode)
    }
}