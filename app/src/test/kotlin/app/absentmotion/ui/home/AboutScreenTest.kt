package app.absentmotion.ui.home

import app.absentmotion.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutScreenTest {
    private val asset get() = TestData.project.resolve("app/src/main/assets/$LICENCES_ASSET").readText()

    @Test fun assetHoldsTheLicenceAndTheThirdPartyNotices() {
        val text = asset
        assertTrue("licenses.txt is empty", text.isNotBlank())
        for (word in listOf("Apache License", "Skia", "whisper.cpp", "Lucide")) assertTrue("licenses.txt lacks \"$word\"", word in text)
    }

    @Test fun pageShowsEveryWordOfTheAssetOnceAndNoItemIsHuge() {
        val text = asset
        val parts = licenceParts(text)
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.all { it.text.lines().size <= 24 })
        val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.all { c -> c == '-' || c == '=' } } }
        assertEquals(words(text), parts.flatMap { words(it.text) })
    }

    @Test fun headingsAreOneLineWithoutTheirUnderline() {
        val parts = licenceParts("TITLE\n=====\n\nSection\n-------\n\nbody text\n")
        assertEquals(listOf("TITLE" to 2, "Section" to 1, "body text" to 0), parts.map { it.text to it.heading })
    }

    @Test fun aCentredTitleIsMovedLeftAndKeepsItsShape() {
        val parts = licenceParts("                  Apache License\n            Version 2.0, January 2004\n\n      body")
        assertEquals(listOf("          Apache License\n    Version 2.0, January 2004", "      body"), parts.map { it.text })
    }

    @Test fun aWrappedParagraphFlowsAndAListKeepsItsLines() {
        val wrapped = listOf(
            "Redistribution and use in source and binary forms, with or without",
            "modification, are permitted provided that the following conditions are",
            "met:",
        )
        assertEquals(listOf(wrapped.joinToString(" ")), reflow(wrapped))
        val bullets = listOf(
            "  * Redistributions of source code must retain the above copyright",
            "    notice, this list of conditions and the following disclaimer.",
            "  * Redistributions in binary form must reproduce the above copyright",
            "    notice, this list of conditions and the following disclaimer in",
        )
        assertEquals(
            listOf(
                "  * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.",
                "  * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in",
            ),
            reflow(bullets),
        )
        val copyrights = listOf("Copyright (c) 2017, Mozilla", "Copyright (c) 2007-2017, Jean-Marc Valin", "Copyright (c) 2003-2004, Mark Borgerding")
        assertEquals(copyrights, reflow(copyrights))
    }
}
