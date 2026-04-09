package io.github.mrcabbagestick.scrambbled.game

import io.github.mrcabbagestick.scrambbled.tools.dictionary.WordDictionary

/**
 * Interface for games that require a dictionary.
 */
interface DictionaryAware {
    fun setDictionary(dictionary: WordDictionary?)
}