package org.koitharu.kotatsu.local.data.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class TxtNovelConverterTest {

    @Test
    fun decodesUtf8BomAndNormalizesNewlines() {
        val original = "第一章\r\n你好\r再见"
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            original.toByteArray(Charsets.UTF_8)
        assertEquals("第一章\n你好\n再见", TxtNovelConverter.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun decodesGb18030AndUtf16Bom() {
        val text = "第一章 春天\n小说正文"
        assertEquals(text, TxtNovelConverter.decode(text.toByteArray(Charset.forName("GB18030"))))
        assertEquals(text, TxtNovelConverter.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun decodesShiftJisWhenExplicitlySelected() {
        val original = "プロローグ\n第１話 夜\n本文"
        val shiftJis = Charset.forName("Shift_JIS")
        assertEquals(original, TxtNovelConverter.decode(original.toByteArray(shiftJis), shiftJis))
    }

    @Test(expected = CharacterCodingException::class)
    fun explicitCharsetRejectsMalformedInputInsteadOfFallingBack() {
        TxtNovelConverter.decode(byteArrayOf(0x82.toByte()), Charset.forName("Shift_JIS"))
    }

    @Test
    fun unicodeBomTakesPrecedenceOverExplicitCharset() {
        val original = "第一章\n正文"
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            original.toByteArray(Charsets.UTF_8)
        assertEquals(original, TxtNovelConverter.decode(bytes, Charset.forName("Shift_JIS")))
    }

    @Test
    fun txtEncodingIntentValuesAreExplicitlyAllowlisted() {
        assertEquals(TxtNovelEncoding.AUTO, TxtNovelEncoding.fromIntentValue(null))
        assertEquals(TxtNovelEncoding.SHIFT_JIS, TxtNovelEncoding.fromIntentValue("Shift_JIS"))
        assertEquals(Charset.forName("GB18030"), TxtNovelEncoding.fromIntentValue("GB18030").charsetOrNull())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownTxtEncodingIntentValue() {
        TxtNovelEncoding.fromIntentValue("x-user-defined")
    }

    @Test
    fun detectsChaptersAndPreservesPreface() {
        val chapters = TxtNovelConverter.chapters("前置文字\n第一章 春天\n正文一\n\n第二章：夏天\n正文二")
        assertEquals(listOf("正文", "第一章 春天", "第二章：夏天"), chapters.map { it.title })
        assertEquals(listOf("前置文字", "正文一\n", "正文二"), chapters.map { it.body })
    }

    @Test
    fun splitsLongUnchapteredTextWithoutBreakingSurrogatePair() {
        val original = "字".repeat(31999) + "😀" + "文".repeat(40000)
        val sections = TxtNovelConverter.chapters(original)
        assertTrue(sections.size > 1)
        assertEquals(original, sections.joinToString("") { it.body })
    }

    @Test
    fun preservesConsecutiveChapterHeadings() {
        val chapters = TxtNovelConverter.chapters("第一章\n第二章\n正文")
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })
        assertEquals("", chapters.first().body)
    }

    @Test
    fun detectsCompactChineseHeadingAndKeepsNarrativeSentence() {
        val chapters = TxtNovelConverter.chapters("第一章初见\n\n第一章里发生了很多事情。\n第二章重逢\n正文")
        assertEquals(listOf("第一章初见", "第二章重逢"), chapters.map { it.title })
        assertEquals("\n第一章里发生了很多事情。", chapters.first().body)
    }

    @Test
    fun preservesLeadingAsciiIndentationInXhtml() {
        val out = ByteArrayOutputStream()
        TxtNovelConverter.writeEpub("书", listOf(
            TxtNovelConverter.Chapter("正文", "  第一段\n\t第二段\n　　第三段"),
        ), out)
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("Missing chapter")
                if (entry.name != "EPUB/ch1.xhtml") continue
                val xhtml = zip.readBytes().toString(Charsets.UTF_8)
                assertTrue(xhtml.contains("<p>&#160;&#160;第一段</p>"))
                assertTrue(xhtml.contains("<p>&#160;&#160;&#160;&#160;第二段</p>"))
                assertTrue(xhtml.contains("<p>　　第三段</p>"))
                break
            }
        }
    }

    @Test(expected = IOException::class)
    fun rejectsEmptyText() {
        TxtNovelConverter.decode("  \n".toByteArray())
    }

    @Test
    fun generatesValidXmlAndStoredMimetypeAsFirstEntry() {
        val output = ByteArrayOutputStream()
        TxtNovelConverter.writeEpub("书 & 名", listOf(
            TxtNovelConverter.Chapter("第一章 <题>", "　　<&> 😀\n\n下一行"),
        ), output)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            val first = requireNotNull(zip.nextEntry)
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.UTF_8))
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                entries[entry.name] = bytes.toString(Charsets.UTF_8)
                DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            }
        }
        assertEquals(setOf("META-INF/container.xml", "EPUB/ch1.xhtml", "EPUB/nav.xhtml", "EPUB/book.opf"), entries.keys)
        assertTrue(entries.getValue("EPUB/ch1.xhtml").contains("&lt;&amp;&gt; 😀"))
        assertTrue(entries.getValue("EPUB/book.opf").contains("书 &amp; 名"))
        assertTrue(entries.getValue("EPUB/nav.xhtml").contains("ch1.xhtml"))
    }
}
