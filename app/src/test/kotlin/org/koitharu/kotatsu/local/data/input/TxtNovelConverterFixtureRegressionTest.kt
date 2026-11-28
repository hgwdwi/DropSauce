package org.koitharu.kotatsu.local.data.input

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.Base64

@Serializable
private data class NovelFixture(
    val id: String,
    val expected: List<ExpectedHeading>,
    val charset: String,
    @SerialName("payload_base64") val payloadBase64: String,
)

@Serializable
private data class ExpectedHeading(
    val title: String,
    @SerialName("offset_cp") val offsetCp: Int,
    @SerialName("offset_utf16") val offsetUtf16: Int,
)

/**
 * Integration regression tests for the real TXT-to-EPUB converter.
 *
 * The V0.2 offsets document source-text coordinates. TxtNovelConverter currently
 * returns chapter title/body rather than source offsets, so parser assertions
 * compare chapter titles while a separate test checks fixture-offset integrity.
 */
class TxtNovelConverterFixtureRegressionTest {

    private val json = Json { ignoreUnknownKeys = false }

    private fun fixtures(): List<NovelFixture> {
        val source = requireNotNull(javaClass.getResource("/novel-fixtures/cases.json"))
        return json.decodeFromString(source.readText())
    }

    private fun bytes(fixture: NovelFixture): ByteArray =
        Base64.getDecoder().decode(fixture.payloadBase64)

    @Test
    fun allSharedFixturesMatchProductionChapterTitles() {
        for (fixture in fixtures()) {
            val sourceBytes = bytes(fixture)
            val charset = Charset.forName(fixture.charset)
            if (fixture.id == "empty") {
                assertThrows(IOException::class.java) {
                    TxtNovelConverter.read(ByteArrayInputStream(sourceBytes), charset)
                }
                continue
            }

            val decoded = TxtNovelConverter.read(ByteArrayInputStream(sourceBytes), charset)
            val chapters = TxtNovelConverter.chapters(decoded)
            val actualTitles = chapters.map { it.title }.filterNot { it == "正文" }
            assertEquals("fixture ${fixture.id}", fixture.expected.map { it.title }, actualTitles)
        }
    }

    @Test
    fun sharedFixtureOffsetsUseCodePointsAndUtf16CodeUnits() {
        for (fixture in fixtures().filter { it.expected.isNotEmpty() }) {
            val sourceBytes = bytes(fixture)
            val rawText = String(sourceBytes, Charset.forName(fixture.charset)).removePrefix("\uFEFF")
            for (heading in fixture.expected) {
                val offsetUtf16 = rawText.indexOf(heading.title)
                assertTrue("fixture heading exists", offsetUtf16 >= 0)
                assertEquals("fixture code point offset", heading.offsetCp,
                    rawText.codePointCount(0, offsetUtf16))
                assertEquals("fixture UTF-16 offset", heading.offsetUtf16, offsetUtf16)
            }
        }
    }

    @Test
    fun compactHeadingDoesNotCaptureNarrativeBeginningWithDe() {
        val chapters = TxtNovelConverter.chapters(
            "第一章初见\n第一章的内容很有趣\n正文",
        )
        assertEquals(listOf("第一章初见"), chapters.map { it.title }.filterNot { it == "正文" })
        assertEquals("第一章的内容很有趣\n正文", chapters.single().body)
    }
}
