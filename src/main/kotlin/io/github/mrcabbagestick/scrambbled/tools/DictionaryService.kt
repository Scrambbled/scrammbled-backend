package io.github.mrcabbagestick.scrambbled.tools

import jakarta.annotation.PostConstruct
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.io.FileNotFoundException
import java.util.Dictionary

@Service
class DictionaryService {
    private val validWords = HashSet<String>()

    @PostConstruct
    fun loadDictionary() {
        println("Loading dictionary...")
        val resource = ClassPathResource("dictionary/words.txt")

        if(!resource.exists()) {
            throw FileNotFoundException("Dictionary file not found")
        }

        resource.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { words ->
                val cleanWord = words.trim().lowercase()
                if(cleanWord.isNotEmpty()) {
                    validWords.add(cleanWord)
                }
            }
        }

        println("Dictionary loaded: ${validWords.size} words")
    }

    /**
     * Sprawdza, czy słowo jest poprawne (istnieje w słowniku).
     */
    fun isValidWord(word: String): Boolean {
        return validWords.contains(word.trim().lowercase())
    }

    /**
     * Zwraca losowe słowo ze słownika.
     */
    fun getRandomWord(): String {
        if(validWords.isEmpty()) return "fallback"
        return validWords.random()
    }
}