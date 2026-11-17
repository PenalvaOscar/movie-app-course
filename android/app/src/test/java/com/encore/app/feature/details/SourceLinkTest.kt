package com.encore.app.feature.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceLinkTest {
    @Test
    fun `wikipedia links are labelled as wikipedia with the share-alike licence`() {
        val link = SourceLink.from("https://en.wikipedia.org/wiki/Some_Film")!!
        assertEquals(SourceKind.WIKIPEDIA, link.kind)
        assertEquals("https://en.wikipedia.org/wiki/Some_Film", link.url)
    }

    @Test
    fun `kaggle links are labelled as the dataset`() {
        assertEquals(SourceKind.KAGGLE, SourceLink.from("https://www.kaggle.com/datasets/x/y")!!.kind)
    }

    @Test
    fun `other https hosts are kept as a generic source`() {
        assertEquals(SourceKind.OTHER, SourceLink.from("https://example.org/film")!!.kind)
    }

    @Test
    fun `missing or unsafe links are dropped`() {
        assertNull(SourceLink.from(null))
        assertNull(SourceLink.from(""))
        assertNull(SourceLink.from("javascript:alert(1)"))
        assertNull(SourceLink.from("file:///sdcard/x"))
        assertNull(SourceLink.from("intent://scan/#Intent;end"))
        assertNull(SourceLink.from("not a url"))
    }

    @Test
    fun `sources for a movie skip duplicates and keep the record order`() {
        val wiki = "https://en.wikipedia.org/wiki/A"
        val kaggle = "https://www.kaggle.com/datasets/x/y"
        assertEquals(listOf(wiki, kaggle), SourceLink.forMovie(wiki, kaggle).map { it.url })
        assertEquals(listOf(kaggle), SourceLink.forMovie(kaggle, kaggle).map { it.url })
        assertEquals(listOf(kaggle), SourceLink.forMovie(null, kaggle).map { it.url })
    }
}
