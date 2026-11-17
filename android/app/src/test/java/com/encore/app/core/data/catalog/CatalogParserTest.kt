package com.encore.app.core.data.catalog

import com.encore.app.core.model.Trivia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fixtures are fictional; real catalog data never enters the test sources. */
class CatalogParserTest {

    private fun parse(json: String) = CatalogParser.parse(json.byteInputStream())

    private fun movie(id: String, extra: String = "") =
        """{"id":"$id","title":"Film $id","year":2001,"synopsis":"S","genres":["Drama"],"director":"Ada Vale","cast":["Bo Lin"],"runtime":100,"rating":7.5$extra}"""

    private fun trivia(id: String, movieId: String, options: String = """["a","b","c","d"]""", answer: Int = 1, question: String = "Q?") =
        """{"id":"$id","movieId":"$movieId","kind":"director","question":"$question","options":$options,"answerIndex":$answer}"""

    private fun catalog(movies: List<String>, trivia: List<String> = emptyList()) =
        """{"movies":[${movies.joinToString(",")}],"trivia":[${trivia.joinToString(",")}]}"""

    private fun assertInvalid(reason: InvalidCatalogException.Reason, json: String) {
        val error = assertThrows(InvalidCatalogException::class.java) { parse(json) }
        assertEquals(reason, error.reason)
    }

    @Test
    fun `parses every movie field and trivia`() {
        val json = """
            {"movies":[{"id":"m1","title":"The Lantern Keeper","year":1999,"synopsis":"A keeper.",
              "genres":["Drama","Mystery"],"director":"Ada Vale","cast":["Bo Lin","Cy Moss"],
              "runtime":112,"rating":7.8,"posterUrl":"https://example.test/p.jpg",
              "backdropUrl":"https://example.test/b.jpg","sourceUrl":"https://example.test/m1",
              "releaseDate":"1999-05-01","voteCount":321,"popularity":12.5,"originalLanguage":"en",
              "datasetSourceUrl":"https://example.test/ds"}],
             "trivia":[{"id":"t1","movieId":"m1","kind":"plot","question":"Which film?",
              "options":["A","B","C","D"],"answerIndex":2,"clue":"Light"}]}
        """.trimIndent()

        val result = parse(json)

        val m = result.movies.single()
        assertEquals("m1", m.id)
        assertEquals("The Lantern Keeper", m.title)
        assertEquals(1999, m.year)
        assertEquals("A keeper.", m.synopsis)
        assertEquals(listOf("Drama", "Mystery"), m.genres)
        assertEquals("Ada Vale", m.director)
        assertEquals(listOf("Bo Lin", "Cy Moss"), m.cast)
        assertEquals(112, m.runtimeMinutes)
        assertEquals(7.8, m.rating!!, 0.0)
        assertEquals("https://example.test/p.jpg", m.posterUrl)
        assertEquals("https://example.test/b.jpg", m.backdropUrl)
        assertEquals("https://example.test/m1", m.sourceUrl)
        assertEquals("1999-05-01", m.releaseDate)
        assertEquals(321, m.voteCount)
        assertEquals(12.5, m.popularity!!, 0.0)
        assertEquals("en", m.originalLanguage)
        assertEquals("https://example.test/ds", m.datasetSourceUrl)

        val t = result.trivia.single()
        assertEquals(Trivia("t1", "m1", "plot", "Which film?", listOf("A", "B", "C", "D"), 2, "Light"), t)
        assertEquals(0, result.rejectedMovies)
        assertEquals(0, result.rejectedTrivia)
    }

    @Test
    fun `zero and blank values are treated as missing`() {
        val json = """{"movies":[{"id":"m1","title":"Quiet","year":0,"synopsis":"","genres":["", "Drama"],
            "director":"  ","cast":["", "Bo Lin", " "],"runtime":0,"rating":0,"posterUrl":"",
            "backdropUrl":" ","sourceUrl":"","releaseDate":"","voteCount":0,"popularity":0,"originalLanguage":""}],"trivia":[]}"""

        val m = parse(json).movies.single()

        assertNull(m.year)
        assertNull(m.director)
        assertNull(m.runtimeMinutes)
        assertNull(m.rating)
        assertNull(m.posterUrl)
        assertNull(m.backdropUrl)
        assertNull(m.sourceUrl)
        assertNull(m.releaseDate)
        assertNull(m.voteCount)
        assertNull(m.popularity)
        assertNull(m.originalLanguage)
        assertEquals(listOf("Drama"), m.genres)
        assertEquals(listOf("Bo Lin"), m.cast)
    }

    @Test
    fun `optional fields may be absent, explicit null or unknown keys`() {
        val json = """{"movies":[{"id":"m1","title":"Sparse","director":null,"futureField":{"x":1}}],"trivia":[],"meta":"v2"}"""

        val m = parse(json).movies.single()

        assertEquals("Sparse", m.title)
        assertEquals("", m.synopsis)
        assertEquals(emptyList<String>(), m.genres)
        assertNull(m.director)
        assertNull(m.year)
    }

    @Test
    fun `missing movies array is invalid`() = assertInvalid(InvalidCatalogException.Reason.MISSING_MOVIES, """{"trivia":[]}""")

    @Test
    fun `empty movies array is invalid`() = assertInvalid(InvalidCatalogException.Reason.EMPTY_MOVIES, """{"movies":[],"trivia":[]}""")

    @Test
    fun `missing trivia array is invalid`() = assertInvalid(InvalidCatalogException.Reason.MISSING_TRIVIA, """{"movies":[${movie("m1")}]}""")

    @Test
    fun `duplicate movie ids are invalid`() =
        assertInvalid(InvalidCatalogException.Reason.DUPLICATE_MOVIE_IDS, catalog(listOf(movie("m1"), movie("m2"), movie("m1"))))

    @Test
    fun `malformed json is invalid`() = assertInvalid(InvalidCatalogException.Reason.MALFORMED, """{"movies":[{"id":"m1",""")

    @Test
    fun `wrong field type is invalid rather than silently dropped`() =
        assertInvalid(InvalidCatalogException.Reason.MALFORMED, """{"movies":[{"id":"m1","title":"T","year":"soon"}],"trivia":[]}""")

    @Test
    fun `a root that is not an object is invalid`() = assertInvalid(InvalidCatalogException.Reason.MALFORMED, """[1,2,3]""")

    @Test
    fun `movies without id or title are rejected and counted`() {
        val json = catalog(
            listOf(
                movie("m1"),
                """{"id":"","title":"No id"}""",
                """{"id":"m3","title":"  "}""",
                """{"title":"Absent id"}""",
                movie("m5"),
            ),
        )

        val result = parse(json)

        assertEquals(listOf("m1", "m5"), result.movies.map { it.id })
        assertEquals(3, result.rejectedMovies)
    }

    @Test
    fun `only rejected movies leaves the catalog empty and invalid`() =
        assertInvalid(InvalidCatalogException.Reason.EMPTY_MOVIES, catalog(listOf("""{"id":"","title":""}""")))

    @Test
    fun `invalid trivia is rejected and counted`() {
        val json = catalog(
            movies = listOf(movie("m1")),
            trivia = listOf(
                trivia("t1", "m1"),
                trivia("t2", "unknown-movie"),
                trivia("t3", "m1", options = """["a","b","c"]"""),
                trivia("t4", "m1", answer = 4),
                trivia("t5", "m1", answer = -1),
                trivia("t6", "m1", question = " "),
                trivia("t1", "m1"),
                trivia("", "m1"),
                trivia("t9", "m1", options = """["a","","c","d"]"""),
            ),
        )

        val result = parse(json)

        assertEquals(listOf("t1"), result.trivia.map { it.id })
        assertEquals(8, result.rejectedTrivia)
    }

    @Test
    fun `ids are compared after trimming, for movies and trivia alike`() {
        val json = catalog(movies = listOf(movie(" m1 ")), trivia = listOf(trivia("t1", " m1")))

        val result = parse(json)

        assertEquals("m1", result.movies.single().id)
        assertEquals("m1", result.trivia.single().movieId)
        assertEquals(0, result.rejectedTrivia)
    }

    @Test
    fun `trivia pointing at a rejected movie is rejected`() {
        val json = catalog(
            movies = listOf(movie("m1"), """{"id":"m2","title":""}"""),
            trivia = listOf(trivia("t1", "m2")),
        )

        val result = parse(json)

        assertEquals(0, result.trivia.size)
        assertEquals(1, result.rejectedTrivia)
    }
}
