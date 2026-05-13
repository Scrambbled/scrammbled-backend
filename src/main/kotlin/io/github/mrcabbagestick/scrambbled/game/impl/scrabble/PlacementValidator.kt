package io.github.mrcabbagestick.scrambbled.game.impl.scrabble

import io.github.mrcabbagestick.scrambbled.tools.dictionary.WordDictionary

object PlacementValidator {

    fun validateMove(
        board: Array<Array<Char?>>,
        placedTiles: List<PlacedTile>,
        isFirstMove: Boolean,
        dictionary: WordDictionary?,
        letterValues: Map<Char, Int>,
        specials: Map<Pair<Int, Int>, SpecialSquare>
    ): ValidationResult {

        // 1. Odrzucenie pustego ruchu
        if (placedTiles.isEmpty()) {
            return ValidationResult(isValid = false, status = "invalid_placement", points = 0)
        }

        // 2. Walidacja pierwszej tury (musi przechodzić przez pole 7,7)
        if (isFirstMove) {
            val hasCenter = placedTiles.any { it.x == 7 && it.y == 7 }
            if (!hasCenter) {
                return ValidationResult(isValid = false, status = "must_contain_starting_square", points = 0)
            }
        }

        // 3. Weryfikacja geometrii układu (czy wszystkie położone litery są w jednej kolumnie lub rzędzie)
        val isHorizontal = placedTiles.all { it.y == placedTiles.first().y }
        val isVertical = placedTiles.all { it.x == placedTiles.first().x }

        if (!isHorizontal && !isVertical) {
            return ValidationResult(isValid = false, status = "invalid_placement", points = 0)
        }

        // 3.5. Sprawdzenie ciągłości liter
        val minX = placedTiles.minOf { it.x }
        val maxX = placedTiles.maxOf { it.x }
        val minY = placedTiles.minOf { it.y }
        val maxY = placedTiles.maxOf { it.y }

        val range = if (isHorizontal) minX..maxX else minY..maxY
        val fixedCoord = if (isHorizontal) placedTiles.first().y else placedTiles.first().x

        for (i in range) {
            val currentX = if (isHorizontal) i else fixedCoord
            val currentY = if (isHorizontal) fixedCoord else i

            val isTileOnBoard = board[currentY][currentX] != null
            val isTilePlaced = placedTiles.any { it.x == currentX && it.y == currentY }

            if (!isTileOnBoard && !isTilePlaced) {
                return ValidationResult(isValid = false, status = "invalid_placement", points = 0)
            }
        }

        // 4. Ekstrakcja wszystkich powstałych słów (głównego i krzyżujących się) z informacjami o koordynatach
        val formedWordsInfo = extractAllWords(board, placedTiles)

        // Zabezpieczenie przed ruchem "w próżnię" - w kolejnych turach słowo musi się łączyć z planszą
        // (jeśli isFirstMove jest false, a długość formedWordsInfo to tylko 1 słowo o długości ilości rzuconych liter,
        // to znaczy, że nie połączyło się z niczym na planszy).
        if (formedWordsInfo.isEmpty()) {
            return ValidationResult(isValid = false, status = "invalid_placement", points = 0)
        }

        // Dodatkowe sprawdzenie ciągłości i połączenia dla ruchów po pierwszej turze
        if (!isFirstMove) {
            val usesExistingTile = formedWordsInfo.any { scoredWord ->
                scoredWord.tiles.any { !it.isNew }
            }
            if (!usesExistingTile) {
                return ValidationResult(isValid = false, status = "invalid_placement", points = 0)
            }
        }

        // 5. Weryfikacja słownika
        val allValid = formedWordsInfo.all { info ->
            dictionary?.isValidWord(info.word) == true
        }

        if (!allValid) {
            return ValidationResult(isValid = false, status = "bad", points = 0)
        }

        // 6. Kalkulacja ostatecznych punktów
        val calculatedPoints = calculatePoints(
            formedWords = formedWordsInfo,
            letterValues = letterValues,
            specials = specials,
            tilesPlayedCount = placedTiles.size
        )

        return ValidationResult(isValid = true, status = "good", points = calculatedPoints)
    }

    private fun extractAllWords(
        board: Array<Array<Char?>>,
        placedTiles: List<PlacedTile>
    ): List<ScoredWord> { // Zmieniony typ zwracany z List<String> na List<ScoredWord>
        val words = mutableListOf<ScoredWord>()

        // 1. Tworzymy wirtualną planszę do łatwego czytania
        val virtualBoard = Array(15) { y ->
            Array(15) { x -> board[y][x] }
        }
        placedTiles.forEach { tile ->
            virtualBoard[tile.y][tile.x] = tile.letter
        }

        val isHorizontal = placedTiles.size > 1 && placedTiles[0].y == placedTiles[1].y

        // Jeśli położono 1 kafelek, sprawdzamy sąsiadów na wirtualnej planszy
        val mainAxisHorizontal = if (placedTiles.size == 1) {
            val tile = placedTiles[0]
            hasNeighbor(virtualBoard, tile.x, tile.y)
        } else {
            isHorizontal
        }

        // 2. Odczyt głównego słowa
        val firstTile = placedTiles.first()
        val mainWord = readWordAt(virtualBoard, firstTile.x, firstTile.y, mainAxisHorizontal, placedTiles)
        if (mainWord.word.length > 1) { // Odwołujemy się do .word
            words.add(mainWord)
        }

        // 3. Odczyt słów poprzecznych
        placedTiles.forEach { tile ->
            val crossWord = readWordAt(virtualBoard, tile.x, tile.y, !mainAxisHorizontal, placedTiles)
            if (crossWord.word.length > 1) { // Odwołujemy się do .word
                words.add(crossWord)
            }
        }

        return words
    }

    private fun readWordAt(
        virtualBoard: Array<Array<Char?>>,
        startX: Int,
        startY: Int,
        horizontal: Boolean,
        placedTiles: List<PlacedTile>
    ): ScoredWord {
        var minPos = if (horizontal) startX else startY
        val fixedPos = if (horizontal) startY else startX

        while (minPos > 0) {
            val charBefore = if (horizontal) virtualBoard[fixedPos][minPos - 1] else virtualBoard[minPos - 1][fixedPos]
            if (charBefore == null) break
            minPos--
        }

        val sb = java.lang.StringBuilder()
        val tiles = mutableListOf<TileCoordinate>()
        var currentPos = minPos

        while (currentPos < 15) {
            val charAt = if (horizontal) virtualBoard[fixedPos][currentPos] else virtualBoard[currentPos][fixedPos]
            if (charAt == null) break
            sb.append(charAt)

            val x = if (horizontal) currentPos else fixedPos
            val y = if (horizontal) fixedPos else currentPos
            val isNew = placedTiles.any { it.x == x && it.y == y }

            tiles.add(TileCoordinate(x, y, charAt, isNew))
            currentPos++
        }

        return ScoredWord(sb.toString(), tiles)
    }

    private fun hasNeighbor(virtualBoard: Array<Array<Char?>>, x: Int, y: Int): Boolean {
        return (x > 0 && virtualBoard[y][x - 1] != null) || (x < 14 && virtualBoard[y][x + 1] != null)

    }

    private fun calculatePoints(
        formedWords: List<ScoredWord>,
        letterValues: Map<Char, Int>,
        specials: Map<Pair<Int, Int>, SpecialSquare>,
        tilesPlayedCount: Int
    ): Int {
        var totalScore = 0

        for (scoredWord in formedWords) {
            var wordBaseScore = 0
            var wordMultiplier = 1

            for (tile in scoredWord.tiles) {
                val baseLetterValue = letterValues[tile.letter] ?: 0

                if (tile.isNew) {
                    val special = specials[Pair(tile.x, tile.y)]
                    val letterMult = special?.letterMultiplier ?: 1
                    val wMult = special?.wordMultiplier ?: 1

                    wordBaseScore += (baseLetterValue * letterMult)

                    wordMultiplier *= wMult
                } else {
                    wordBaseScore += baseLetterValue
                }

                val special = specials[Pair(tile.x, tile.y)]
                println("Letter: ${tile.letter} base: $baseLetterValue letMult: ${special?.letterMultiplier ?: 1}, wMult: ${special?.wordMultiplier ?: 1}")
            }

            totalScore += (wordBaseScore * wordMultiplier)

            println("points: $totalScore, base: $wordBaseScore mult: $wordMultiplier")
        }

        return totalScore
    }
}
