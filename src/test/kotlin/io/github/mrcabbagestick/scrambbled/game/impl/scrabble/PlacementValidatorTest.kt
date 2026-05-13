package io.github.mrcabbagestick.scrambbled.game.impl.scrabble

import io.github.mrcabbagestick.scrambbled.tools.dictionary.WordDictionary
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class PlacementValidatorTest {

    private lateinit var mockDictionary: WordDictionary

    // Angielskie wartości punktowe do testów
    private val defaultLetterValues = mapOf(
        'C' to 3, 'A' to 1, 'T' to 1,
        'D' to 2, 'O' to 1, 'G' to 2,
        'Z' to 10
    )

    @BeforeEach
    fun setup() {
        mockDictionary = mock(WordDictionary::class.java)
        // Domyślnie akceptuj wszystkie słowa, żeby skupić się na geometrii
        `when`(mockDictionary.isValidWord(anyString())).thenReturn(true)
    }

    private fun createEmptyBoard(): Array<Array<Char?>> {
        return Array(15) { Array(15) { null } }
    }

    // --- ETAP 2: Reguły podstawowe i pierwsza tura ---

    @Test
    fun `should reject empty move`() {
        val board = createEmptyBoard()
        val result = PlacementValidator.validateMove(board, emptyList(), true, mockDictionary, defaultLetterValues, emptyMap())

        assertFalse(result.isValid)
        assertEquals("invalid_placement", result.status)
    }

    @Test
    fun `should reject first move if it does not cover center`() {
        val board = createEmptyBoard()
        val tiles = listOf(
            PlacedTile('C', 0, 0),
            PlacedTile('A', 1, 0),
            PlacedTile('T', 2, 0)
        )

        val result = PlacementValidator.validateMove(board, tiles, true, mockDictionary, defaultLetterValues, emptyMap())

        assertFalse(result.isValid)
        assertEquals("must_contain_starting_square", result.status)
    }

    @Test
    fun `should accept valid first move through center`() {
        val board = createEmptyBoard()
        val tiles = listOf(
            PlacedTile('C', 6, 7),
            PlacedTile('A', 7, 7), // Przechodzi przez środek (7,7)
            PlacedTile('T', 8, 7)
        )

        val result = PlacementValidator.validateMove(board, tiles, true, mockDictionary, defaultLetterValues, emptyMap())

        assertTrue(result.isValid)
        assertEquals("good", result.status)
        // Punkty: C(3) + A(1) + T(1) = 5
        assertEquals(5, result.points)
    }

    // --- ETAP 3: Geometria i ciągłość ---

    @Test
    fun `should reject diagonal placement`() {
        val board = createEmptyBoard()
        val tiles = listOf(
            PlacedTile('D', 7, 7),
            PlacedTile('O', 8, 8) // Ułożenie po skosie
        )

        val result = PlacementValidator.validateMove(board, tiles, true, mockDictionary, defaultLetterValues, emptyMap())

        assertFalse(result.isValid)
        assertEquals("invalid_placement", result.status)
    }

    @Test
    fun `should reject placement with gaps`() {
        val board = createEmptyBoard()
        // Próba ułożenia C_T z luką w środku
        val tiles = listOf(
            PlacedTile('C', 7, 7),
            PlacedTile('T', 9, 7)
        )

        val result = PlacementValidator.validateMove(board, tiles, true, mockDictionary, defaultLetterValues, emptyMap())

        assertFalse(result.isValid)
        assertEquals("invalid_placement", result.status)
    }

    @Test
    fun `should reject move not connected to existing tiles`() {
        val board = createEmptyBoard()
        // Kładziemy słowo "CAT" na planszy (środek)
        board[7][7] = 'C'
        board[7][8] = 'A'
        board[7][9] = 'T'

        // Próba ułożenia nowego słowa "DOG" w rogu, bez styku z "CAT"
        val tiles = listOf(
            PlacedTile('D', 0, 0),
            PlacedTile('O', 1, 0),
            PlacedTile('G', 2, 0)
        )

        val result = PlacementValidator.validateMove(board, tiles, false, mockDictionary, defaultLetterValues, emptyMap())

        assertFalse(result.isValid)
        assertEquals("invalid_placement", result.status)
    }

    // --- ETAP 4: Słownik ---

    @Test
    fun `should reject move if word is not in dictionary`() {
        val board = createEmptyBoard()
        val tiles = listOf(
            PlacedTile('Z', 7, 7),
            PlacedTile('Z', 8, 7),
            PlacedTile('Z', 9, 7)
        )

        // Konfigurujemy mocka, aby odrzucał to konkretne słowo
        `when`(mockDictionary.isValidWord("ZZZ")).thenReturn(false)

        val result = PlacementValidator.validateMove(board, tiles, true, mockDictionary, defaultLetterValues, emptyMap())

        assertFalse(result.isValid)
        assertEquals("bad", result.status)
    }

    // --- ETAP 5: Punkty i Premie ---

    @Test
    fun `should calculate multipliers correctly`() {
        val board = createEmptyBoard()

        // Układamy słowo CAT zgodnie z formatem PlacedTile(letter, x, y)
        val tiles = listOf(
            PlacedTile('C', 7, 7),
            PlacedTile('A', 8, 7),
            PlacedTile('T', 9, 7)
        )

        // Używamy argumentów nazwanych dla WSZYSTKICH pól, aby uniknąć błędów kompilacji
        val specials = mapOf(
            Pair(7, 7) to SpecialSquare(
                x = 7,
                y = 7,
                wordMultiplier = 2,
                letterMultiplier = 1
            ), // Double Word dla 'C'
            Pair(9, 7) to SpecialSquare(
                x = 9,
                y = 7,
                wordMultiplier = 1,
                letterMultiplier = 2
            )  // Double Letter dla 'T'
        )

        val result = PlacementValidator.validateMove(
            board = board,
            placedTiles = tiles,
            isFirstMove = true,
            dictionary = mockDictionary,
            letterValues = defaultLetterValues,
            specials = specials
        )

        assertTrue(result.isValid)

        // Oczekiwane obliczenia (English Scrabble values):
        // C (3 pkt) na Double Word = 3 * 1 (literowy) = 3
        // A (1 pkt) na zwykłym polu = 1
        // T (1 pkt) na Double Letter = 1 * 2 = 2
        // Baza słowa (3 + 1 + 2) = 6
        // Premia słowna (Double Word) = 6 * 2 = 12
        assertEquals(12, result.points)
    }
}