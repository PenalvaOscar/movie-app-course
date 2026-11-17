package com.encore.app.core.data.catalog

import com.encore.app.testing.summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTextTest {
    @Test
    fun `normalize lowercases and strips accents`() {
        assertEquals("amelie", SearchText.normalize("Amélie"))
        assertEquals("pedro almodovar", SearchText.normalize("  Pedro ALMODÓVAR "))
        assertEquals("sao paulo", SearchText.normalize("São Paulo"))
        assertEquals("munchen", SearchText.normalize("München"))
    }

    @Test
    fun `blank text means no pattern`() {
        assertNull(SearchText.likePattern(""))
        assertNull(SearchText.likePattern("   "))
    }

    @Test
    fun `pattern is a contains match on the normalized text`() {
        assertEquals("%amelie%", SearchText.likePattern(" AMÉLIE "))
    }

    @Test
    fun `like wildcards typed by the user are escaped`() {
        assertEquals("%100\\%%", SearchText.likePattern("100%"))
        assertEquals("%a\\_b%", SearchText.likePattern("a_b"))
        assertEquals("%c\\\\d%", SearchText.likePattern("c\\d"))
    }
}

class FeaturedPickTest {
    private val candidates = listOf(summary("a"), summary("b"), summary("c"))

    @Test
    fun `same day gives the same movie`() {
        assertEquals(pickFeatured(candidates, 20_000), pickFeatured(candidates, 20_000))
    }

    @Test
    fun `the pick rotates day by day through the candidates`() {
        val picks = (20_000L until 20_003L).map { pickFeatured(candidates, it)!!.id }.toSet()
        assertEquals(setOf("a", "b", "c"), picks)
    }

    @Test
    fun `no candidates means no featured movie`() {
        assertNull(pickFeatured(emptyList(), 20_000))
    }

    @Test
    fun `days before the epoch still pick a valid index`() {
        assertEquals(true, pickFeatured(candidates, -1) in candidates)
    }
}
