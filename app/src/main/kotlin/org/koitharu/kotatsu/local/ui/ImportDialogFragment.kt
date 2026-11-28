package org.koitharu.kotatsu.local.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.expressiveGroupItemShape
import org.koitharu.kotatsu.core.util.ext.resolveName
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.input.TxtNovelEncoding
import org.koitharu.kotatsu.local.data.isSupportedArchive
import javax.inject.Inject

@AndroidEntryPoint
class ImportDialogFragment : ComposeAlertDialogFragment() {

	@Inject
	lateinit var storageManager: LocalStorageManager

	private val importFileCall = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
		startImport(it)
	}
	private val importDirCall = OpenDocumentTreeHelper(this) {
		startImport(listOfNotNull(it))
	}

	private val importTxtDirCall = OpenDocumentTreeHelper(this) {
		startImport(listOfNotNull(it), textFolder = true)
	}

	@Composable
	override fun Content() {
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_file_zip),
			title = stringResource(R.string._import),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = ::dismiss,
			),
		) {
			ImportChoice(
				index = 0,
				count = 3,
				icon = R.drawable.ic_file_zip,
				title = R.string.tsubaki_import_books,
				subtitle = R.string.tsubaki_import_books_description,
			) {
				launch(
					importFileCall.tryLaunch(
						arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook-rar", "application/x-cbr", "application/pdf", "application/epub+zip", "text/plain", "*/*"),
					),
				)
			}
			Spacer(Modifier.height(2.dp))
			ImportChoice(
				index = 1,
				count = 3,
				icon = R.drawable.ic_folder_file,
				title = R.string.folder_with_images,
				subtitle = R.string.folder_with_images_import_description,
			) {
				launch(importDirCall.tryLaunch(null))
			}
			Spacer(Modifier.height(2.dp))
			ImportChoice(
				index = 2,
				count = 3,
				icon = R.drawable.ic_folder_file,
				title = R.string.tsubaki_import_txt_folder,
				subtitle = R.string.tsubaki_import_txt_folder_description,
			) {
				launch(importTxtDirCall.tryLaunch(null))
			}
		}
	}

	@Composable
	private fun ImportChoice(
		index: Int,
		count: Int,
		@DrawableRes icon: Int,
		@StringRes title: Int,
		@StringRes subtitle: Int,
		onClick: () -> Unit,
	) {
		val colors = MaterialTheme.colorScheme
		Surface(
			onClick = onClick,
			shape = expressiveGroupItemShape(index, count),
			color = colors.background,
			contentColor = colors.onSurface,
			modifier = Modifier.fillMaxWidth(),
		) {
			Row(
				modifier = Modifier.padding(16.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Box(
					modifier = Modifier
						.size(48.dp)
						.background(colors.secondaryContainer, RoundedCornerShape(14.dp)),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(icon),
						contentDescription = null,
						tint = colors.onSecondaryContainer,
						modifier = Modifier.size(26.dp),
					)
				}
				Spacer(Modifier.width(16.dp))
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(title),
						style = MaterialTheme.typography.titleMedium,
						color = colors.onSurface,
					)
					Text(
						text = stringResource(subtitle),
						style = MaterialTheme.typography.bodySmall,
						color = colors.onSurfaceVariant,
					)
				}
			}
		}
	}

	private fun launch(result: Boolean) {
		if (!result) {
			Toast.makeText(requireContext(), R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}

	private fun startImport(uris: Collection<Uri>, textFolder: Boolean = false) {
		if (uris.isEmpty()) {
			return
		}
		if (uris.any { !it.isDirectoryUri() && !it.isSupportedArchiveFile() }) {
			Toast.makeText(requireContext(), R.string.text_file_not_supported, Toast.LENGTH_LONG).show()
			return
		}
		if (textFolder || uris.any { it.isTxtFile() }) {
			showTxtEncodingPicker(uris, textFolder)
		} else {
			enqueueImport(uris, textFolder, null)
		}
	}

	private fun showTxtEncodingPicker(uris: Collection<Uri>, textFolder: Boolean) {
		val encodings = TxtNovelEncoding.values()
		val labels = arrayOf(
			getString(R.string.tsubaki_txt_encoding_auto),
			getString(R.string.tsubaki_txt_encoding_utf8),
			getString(R.string.tsubaki_txt_encoding_utf16),
			getString(R.string.tsubaki_txt_encoding_gb18030),
			getString(R.string.tsubaki_txt_encoding_shift_jis),
			getString(R.string.tsubaki_txt_encoding_windows31j),
		)
		var selectedIndex = 0
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.tsubaki_txt_encoding_title)
			.setMessage(R.string.tsubaki_txt_encoding_message)
			.setSingleChoiceItems(labels, selectedIndex) { _, which ->
				selectedIndex = which
			}
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.tsubaki_txt_encoding_import) { _, _ ->
				enqueueImport(uris, textFolder, encodings[selectedIndex].intentValue)
			}
			.show()
	}

	private fun enqueueImport(uris: Collection<Uri>, textFolder: Boolean, charsetName: String?) {
		uris.forEach {
			storageManager.takePermissions(it)
		}
		val ctx = requireContext()
		val msg = if (ImportService.start(ctx, uris, textFolder, charsetName)) {
			R.string.import_will_start_soon
		} else {
			R.string.error_occurred
		}
		Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
		dismiss()
	}

	private fun Uri.isTxtFile(): Boolean = runCatching {
		storageManager.contentResolver.resolveName(this)?.endsWith(".txt", ignoreCase = true) == true
	}.getOrDefault(false)

	private fun Uri.isDirectoryUri(): Boolean {
		val ctx = context ?: return false
		return runCatching {
			DocumentFile.fromTreeUri(ctx, this)?.isDirectory == true
		}.getOrDefault(false)
	}

	private fun Uri.isSupportedArchiveFile(): Boolean {
		return runCatching {
			val name = storageManager.contentResolver.resolveName(this)
			!name.isNullOrBlank() && (isSupportedArchive(name) || name.endsWith(".txt", ignoreCase = true))
		}.getOrDefault(false)
	}
}
