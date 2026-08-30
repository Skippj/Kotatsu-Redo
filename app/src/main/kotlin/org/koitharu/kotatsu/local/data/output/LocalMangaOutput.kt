package org.koitharu.kotatsu.local.data.output

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
		const val FILENAME_SKIP = ".notamanga"
		private val mutex = Mutex()

		suspend fun getOrCreate(
			root: File,
			manga: Manga,
			format: DownloadFormat,
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
			checkNotNull(getImpl(root, manga, onlyIfExists = false, format = targetFormat))
		}

		suspend fun get(root: File, manga: Manga): LocalMangaOutput? = withContext(Dispatchers.IO) {
			getImpl(root, manga, onlyIfExists = true, format = DownloadFormat.AUTOMATIC)
		}

		private suspend fun getImpl(
			root: File,
			manga: Manga,
			onlyIfExists: Boolean,
			format: DownloadFormat,
		): LocalMangaOutput? {
			mutex.withLock {
				if (!onlyIfExists && format.isPdf) {
					// PDF files are never reused or merged into, so a fresh name is always taken
					return LocalMangaPdfOutput(
						rootFile = LocalMangaPdfOutput.findFreeFile(root, manga, format == DownloadFormat.MULTIPLE_PDF),
						manga = manga,
						isSplitByChapters = format == DownloadFormat.MULTIPLE_PDF,
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
