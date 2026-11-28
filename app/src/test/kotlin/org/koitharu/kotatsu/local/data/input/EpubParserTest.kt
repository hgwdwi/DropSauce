package org.koitharu.kotatsu.local.data.input

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubParserTest {

	@Test
	fun `leading byte order mark is removed from xml input`() {
		val xml = "\uFEFF<?xml version=\"1.0\"?><container/>"

		assertEquals(
			"<?xml version=\"1.0\"?><container/>",
			EpubParser.normalizeXmlInput(xml),
		)
	}

	@Test
	fun `resolves relative spine hrefs against the opf directory`() {
		assertEquals("EPUB/Text/chapter-1.xhtml", EpubParser.resolveHref("EPUB", "Text/chapter-1.xhtml"))
		assertEquals("EPUB/nav.xhtml", EpubParser.resolveHref("EPUB/Text", "../nav.xhtml"))
	}

	@Test
	fun `decodes encoded spaces in epub entry paths`() {
		assertEquals("EPUB/Text/chapter one.xhtml", EpubParser.resolveHref("EPUB/Text", "chapter%20one.xhtml"))
	}

	@Test
	fun `xml input without byte order mark is unchanged`() {
		val xml = "<?xml version=\"1.0\"?><container/>"

		assertEquals(xml, EpubParser.normalizeXmlInput(xml))
	}
}
