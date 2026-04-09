package io.github.mrcabbagestick.scrambbled.tools.dictionary

class TrieNode {
    val children = mutableMapOf<Char, TrieNode>()
    var isWord = false
}

class WordDictionary(val name: String) {
    private val wordsSet = HashSet<String>()
    val trieRoot = TrieNode()

    /**
     * Adds a word to the dictionary.
     * Set for fast lookup.
     * Trie for advance search.
     */
    fun addWord(word: String) {
        val cleanWord = word.trim().lowercase()
        if(cleanWord.isBlank()) return

        wordsSet.add(cleanWord)

        var current = trieRoot
        for(char in cleanWord) {
            current = current.children.computeIfAbsent(char) { TrieNode() }
        }
        current.isWord = true
    }

    /**
     * Checks if the given word is valid (exists in the dictionary).
     */
    fun isValidWord(word: String) = wordsSet.contains(word.trim().lowercase())

    /**
     * Returns a random word from the dictionary.
     */
    fun getRandomWord(): String {
        if(wordsSet.isEmpty()) return "fallback"
        return wordsSet.random()
    }

    /**
     * Returns the number of words in the dictionary.
     */
    val wordCount: Int get() = wordsSet.size

    /**
     * Clears the dictionary.
     */
    fun clear() {
        wordsSet.clear()
        trieRoot.children.clear()
    }

    /**
     * An example of trie search.
     * Checks if the given prefix is a valid prefix of any word in the dictionary.
     */
    fun hasPrefix(prefix: String): Boolean {
        var current = trieRoot
        for(char in prefix.trim().lowercase()) {
            current = current.children[char] ?: return false
        }
        return true
    }
}