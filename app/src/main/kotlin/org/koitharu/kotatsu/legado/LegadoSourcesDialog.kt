package org.koitharu.kotatsu.legado

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.local.data.importer.SingleMangaImporter
import org.koitharu.kotatsu.local.data.input.TxtNovelConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@AndroidEntryPoint
class LegadoSourcesDialog : ComposeAlertDialogFragment() {

    @Inject lateinit var importer: SingleMangaImporter

    private val cookieDirectory by lazy { File(requireContext().applicationContext.filesDir, "legado/cookies") }
    private fun httpReader(source: LegadoSourcePreflight.Inspection) = LegadoHttpReader(source, cookieDirectory)

    private val store by lazy {
        LegadoSourceStore(File(requireContext().applicationContext.filesDir, "legado"))
    }
    private var sources by mutableStateOf<List<LegadoSourcePreflight.Inspection>>(emptyList())
    private var pending by mutableStateOf<List<LegadoSourcePreflight.Inspection>>(emptyList())
    private var active by mutableStateOf<LegadoSourcePreflight.Inspection?>(null)
    private var results by mutableStateOf<List<LegadoCssReader.Book>>(emptyList())
    private var book by mutableStateOf<LegadoCssReader.Book?>(null)
    private var chapters by mutableStateOf<List<LegadoCssReader.Chapter>>(emptyList())
    private var preview by mutableStateOf<TxtNovelConverter.Chapter?>(null)
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task {
            val resolver = requireContext().contentResolver
            pending = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { input ->
                    val data = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(data.size() <= MAX_BYTES - n) { "JSON exceeds size limit" }
                        data.write(buffer, 0, n)
                    }
                    val json = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data.toByteArray())).toString()
                    LegadoSourcePreflight.inspect(json)
                } ?: error("Cannot open source file")
            }
        }
    }

    private fun task(action: suspend () -> Unit) {
        if (busy || view == null) return
        busy = true
        message = null
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message?.take(300) ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    @Composable
    override fun Content() {
        var query by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            task { sources = withContext(Dispatchers.IO) { store.list() } }
        }
        ExpressiveDialogCard(
            icon = null,
            title = stringResource(R.string.tsubaki_legado_sources),
            cornerAction = DialogCornerAction(
                icon = painterResource(R.drawable.ic_close),
                label = stringResource(android.R.string.cancel),
                onClick = ::dismiss,
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) Text(stringResource(R.string.tsubaki_legado_loading))
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val selected = active
                val selectedBook = book
                val chapterPreview = preview
                when {
                    chapterPreview != null -> {
                        TextButton(onClick = { preview = null }, enabled = !busy) {
                            Text(stringResource(R.string.tsubaki_legado_back))
                        }
                        Text(stringResource(R.string.tsubaki_legado_preview))
                        if (selectedBook != null) {
                            Button(onClick = {
                                task {
                                    saveOffline(selectedBook.title + " · " + chapterPreview.title, listOf(chapterPreview))
                                }
                            }, enabled = !busy) {
                                Text(stringResource(R.string.tsubaki_legado_save_chapter))
                            }
                        }
                        Text(chapterPreview.body.take(16000))
                    }
                    selectedBook != null && selected != null -> {
                        TextButton(onClick = { book = null; chapters = emptyList() }, enabled = !busy) {
                            Text(stringResource(R.string.tsubaki_legado_back))
                        }
                        Text(selectedBook.title, style = MaterialTheme.typography.titleMedium)
                        selectedBook.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        selectedBook.intro?.let { Text(it.take(500), style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.tsubaki_legado_chapter_count, chapters.size))
                        if (chapters.isNotEmpty()) {
                            val count = minOf(chapters.size, 10)
                            Button(onClick = {
                                val requested = chapters.take(count)
                                task {
                                    val reader = httpReader(selected)
                                    val downloaded = ArrayList<TxtNovelConverter.Chapter>()
                                    var chars = 0L
                                    for (chapter in requested) {
                                        val html = reader.content(chapter)
                                        val text = withContext(Dispatchers.Default) { chapterText(html) }
                                        chars += text.length
                                        require(chars <= 2_000_000L) { "Offline text exceeds size limit" }
                                        downloaded.add(TxtNovelConverter.Chapter(chapter.title, text))
                                    }
                                    saveOffline(selectedBook.title + " (1-$count)", downloaded)
                                }
                            }, enabled = !busy) {
                                Text(stringResource(R.string.tsubaki_legado_save_first, count))
                            }
                        }
                        chapters.take(200).forEach { chapter ->
                            Surface(onClick = {
                                task {
                                    val html = httpReader(selected).content(chapter)
                                    preview = withContext(Dispatchers.Default) {
                                        TxtNovelConverter.Chapter(chapter.title, chapterText(html))
                                    }
                                }
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface) {
                                Text(chapter.title, Modifier.padding(12.dp))
                            }
                        }
                        if (chapters.size > 200) Text(stringResource(R.string.tsubaki_legado_preview_limit))
                    }
                    selected != null -> {
                        TextButton(onClick = { active = null; results = emptyList() }, enabled = !busy) {
                            Text(stringResource(R.string.tsubaki_legado_back))
                        }
                        Text(selected.name, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = query, onValueChange = { query = it.take(256) },
                            label = { Text(stringResource(R.string.tsubaki_legado_query)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            val term = query.trim()
                            task { results = httpReader(selected).search(term) }
                        }, enabled = !busy && query.isNotBlank()) {
                            Text(stringResource(R.string.tsubaki_legado_search))
                        }
                        results.take(50).forEach { result ->
                            Surface(onClick = {
                                task {
                                    val directory = httpReader(selected).directory(result)
                                    chapters = directory.chapters
                                    book = directory.book
                                }
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(result.title, style = MaterialTheme.typography.titleMedium)
                                    result.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    result.lastChapter?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.tsubaki_legado_notice),
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            enabled = !busy) {
                            Text(stringResource(R.string.tsubaki_legado_import))
                        }
                        if (pending.isNotEmpty()) {
                            Text(stringResource(R.string.tsubaki_legado_review, pending.size))
                            pending.take(100).forEach { candidate ->
                                Surface(shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(candidate.name.ifBlank { candidate.url })
                                        Text(candidate.url, style = MaterialTheme.typography.bodySmall)
                                        if (candidate.supportedSubset) {
                                            TextButton(enabled = !busy, onClick = {
                                                task {
                                                    sources = withContext(Dispatchers.IO) {
                                                        store.save(candidate)
                                                        store.list()
                                                    }
                                                    pending = pending.filterNot { it === candidate }
                                                }
                                            }) { Text(stringResource(R.string.tsubaki_legado_save)) }
                                        } else {
                                            Text(candidate.issues.joinToString("\n").take(1000),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { pending = emptyList() }, enabled = !busy) {
                                Text(stringResource(R.string.tsubaki_legado_clear))
                            }
                        }
                        Text(stringResource(R.string.tsubaki_legado_saved, sources.size))
                        sources.forEach { source ->
                            Surface(shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                                    Text(source.url, style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton(enabled = !busy && source.enabled && source.supportedSubset,
                                            onClick = { active = source; results = emptyList() }) {
                                            Text(stringResource(R.string.tsubaki_legado_search))
                                        }
                                        TextButton(enabled = !busy, onClick = {
                                            task { withContext(Dispatchers.IO) { LegadoCookieJar.forSource(cookieDirectory, source.url).clear() } }
                                        }) { Text(stringResource(R.string.tsubaki_legado_cookies_clear)) }
                                        TextButton(enabled = !busy, onClick = {
                                            task {
                                                sources = withContext(Dispatchers.IO) {
                                                    store.remove(source.url)
                                                    LegadoCookieJar.forSource(cookieDirectory, source.url).clear()
                                                    store.list()
                                                }
                                            }
                                        }) { Text(stringResource(R.string.tsubaki_legado_remove)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun chapterText(html: String): String {
        return LegadoTextExtractor.extract(org.jsoup.Jsoup.parseBodyFragment(html).body()).trim().also {
            require(it.isNotBlank() && it.length <= 2_000_000) { "Empty or oversized chapter" }
        }
    }

    private suspend fun saveOffline(title: String, chapters: List<TxtNovelConverter.Chapter>) {
        val local = importer.importGeneratedNovel(title, chapters)
        startActivity(AppRouter.detailsIntent(requireContext(), local.manga))
        dismiss()
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
