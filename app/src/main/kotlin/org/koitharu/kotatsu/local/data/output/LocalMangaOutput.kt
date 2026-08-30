package org.koitharu.kotatsu.local.data.output

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Closeable
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toFileNameSafe
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File

sealed class LocalMangaOutput(
	val rootFile: File,
) : Closeable {

	abstract suspend fun mergeWithExisting()

	abstract suspend fun addCover(file: File, type: MimeType?)

	abstract suspend fun addPage(chapter: IndexedValue<MangaChapter>, file: File, pageNumber: Int, type: MimeType?)

	abstract suspend fun flushChapter(chapter: MangaChapter): Boolean

	abstract suspend fun finish()

	abstract suspend fun cleanup()

	/**
	 * A local manga that has been written by this output, or `null` if the resulting files
	 * are not a part of the local library, as it is for the PDF formats.
	 */
	open suspend fun getLocalManga(): LocalManga? = LocalMangaParser(rootFile).getManga(withDetails = false)

	companion object {

		const val ENTRY_NAME_INDEX = "index.json"
		const val SUFFIX_TMP = ".tmp"
		private val mutex = Mutex()

		suspend fun getOrCreate(
			context: Context,
			root: File,
			manga: Manga,
			format: DownloadFormat,
			pdfDestination: Uri? = null,
		): LocalMangaOutput = withContext(Dispatchers.IO) {
			val targetFormat = if (format == DownloadFormat.AUTOMATIC) {
				if (manga.chapters.let { it != null && it.size <= 3 }) {
					DownloadFormat.SINGLE_CBZ
				} else {
					DownloadFormat.MULTIPLE_CBZ
				}
			} else {
				format
			}
			checkNotNull(getImpl(context, root, manga, false, targetFormat, pdfDestination))
		}

		suspend fun get(root: File, manga: Manga): LocalMangaOutput? = withContext(Dispatchers.IO) {
			getImpl(null, root, manga, onlyIfExists = true, format = DownloadFormat.AUTOMATIC, pdfDestination = null)
		}

		private suspend fun getImpl(
			context: Context?,
			root: File,
			manga: Manga,
			onlyIfExists: Boolean,
			format: DownloadFormat,
			pdfDestination: Uri?,
		): LocalMangaOutput? {
			mutex.withLock {
				if (!onlyIfExists && format.isPdf) {
					// a PDF is written into a temporary directory and then published to the shared Download one,
					// so it never reuses nor conflicts with anything inside the manga storage
					return LocalMangaPdfOutput.create(
						context = checkNotNull(context) { "Context is required to write a PDF" },
						manga = manga,
						isSplitByChapters = format == DownloadFormat.MULTIPLE_PDF,
						destination = pdfDestination,
					)
				}
				var i = 0
				val baseName = manga.title.toFileNameSafe()
				while (true) {
					val fileName = if (i == 0) baseName else baseName + "_$i"
					val dir = File(root, fileName)
					val zip = File(root, "$fileName.cbz")
					i++
					return when {
						dir.isDirectory -> {
							if (canWriteTo(dir, manga)) {
								LocalMangaDirOutput(dir, manga)
							} else {
								continue
							}
						}

						zip.isFile -> if (canWriteTo(zip, manga)) {
							LocalMangaZipOutput(zip, manga)
						} else {
							continue
						}

						!onlyIfExists -> when (format) {
							DownloadFormat.SINGLE_CBZ -> LocalMangaZipOutput(zip, manga)
							DownloadFormat.MULTIPLE_CBZ -> LocalMangaDirOutput(dir, manga)
							DownloadFormat.AUTOMATIC,
							DownloadFormat.SINGLE_PDF,
							DownloadFormat.MULTIPLE_PDF -> null
						}

						else -> null
					}
				}
			}
		}

		private suspend fun canWriteTo(file: File, manga: Manga): Boolean {
			val info = runCatchingCancellable {
				LocalMangaParser(file).getMangaInfo()
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull() ?: return false
			return info.id == manga.id
		}
	}
}
