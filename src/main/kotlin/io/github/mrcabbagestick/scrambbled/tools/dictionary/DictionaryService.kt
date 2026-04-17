package io.github.mrcabbagestick.scrambbled.tools.dictionary

import jakarta.annotation.PostConstruct
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@Service
class DictionaryService {
    private val globalDictionaries = ConcurrentHashMap<String, WordDictionary>()

    @PostConstruct
    fun loadDefaults() {
        println("Loading default dictionaries...")
        loadDictionaryFromFile("pl", "dictionary/pl.txt")
        loadDictionaryFromFile("en", "dictionary/en.txt")
    }

    /**
     * Loads a dictionary from a file.
     * @param languageCode The language code of the dictionary (e.g., "en", "pl").
     * @param path The path to the dictionary file.
     */
    fun loadDictionaryFromFile(languageCode: String, path: String) {
        println("Loading dictionary...")
        val resource = ClassPathResource(path)

        if(!resource.exists()) {
            println("Dictionary for language '$languageCode' not found.")
            return
        }

        val dictionary = WordDictionary(languageCode)
        resource.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { dictionary.addWord(it) }
        }
        globalDictionaries[languageCode] = dictionary

        println("Loaded dictionary [$languageCode]. Word count: ${dictionary.wordCount}")
    }

    /**
     * Gets the global dictionary for a given language code.
     * @param languageCode The language code of the dictionary (e.g., "en", "pl").
     * @return The global dictionary for the given language code, or null if not found.
     */
    fun getGlobalDictionary(languageCode: String): WordDictionary? = globalDictionaries[languageCode]

    /**
     * Parses a custom dictionary from an input stream.
     * @param name The name of the custom dictionary.
     * @param inputStream The input stream containing the custom dictionary.
     * @return The parsed WordDictionary.
     */
    fun parseCustomDictionary(name: String, inputStream: InputStream): WordDictionary {
        val customDict = WordDictionary(name)
        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { customDict.addWord(it) }
        }
        return customDict
    }
}