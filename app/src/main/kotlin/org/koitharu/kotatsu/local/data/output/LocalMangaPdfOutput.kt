package org.koitharu.kotatsu.local.data.output

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.core.pdf.PdfWriter
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.toFileNameSafe
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.io.File

/**
 * Writes downloaded manga into PDF files.
 *
 * Unlike CBZ, a PDF file is an export-only format: it is not indexed by the local library and cannot be opened
 * in the reader, so [getLocalManga] returns `null`.
 *
 * Depending on [isSplitByChapters], [rootFile] is either a single `*.pdf` file that contains the whole manga
 * or a directory with a separate PDF file per chapter.
 */
class LocalMangaPdfOutput(
	rootFile: File,
	private val manga: Manga,
	private val isSplitByChapters: Boolean,
) : LocalMangaOutput(rootFile) {

	private val mutex = Mutex()

	/**
	 * Pages are downloaded in parallel and may arrive out of order, while a PDF file is written sequentially,
	 * so pages are staged here first and flushed chapter by chapter.
	 */
	private val stagingDir = File(checkNotNull(rootFile.parentFile), rootFile.name + SUFFIX_STAGING)
	private val pendingChapters = LinkedHashMap<Long, PendingChapter>()
	private val tempFile = File(checkNotNull(rootFile.parentFile), rootFile.name + SUFFIX_TMP)
	private var writer: PdfWriter? = null

	override suspend fun mergeWithExisting() = Unit

	override suspend fun addCover(file: File, type: MimeType?) = mutex.withLock {
		if (isSplitByChapters) {
			return@withLock // a cover page makes no sense in each of the per-chapter files
		}
		runInterruptible(Dispatchers.IO) {
			requireWriter().addPage(file)
		}
		Unit
	}

	override suspend fun addPage(
		chapter: IndexedValue<MangaChapter>,
		file: File,
		pageNumber: Int,
		type: MimeType?,
	) = mutex.withLock {
		val pending = pendingChapters.getOrPut(chapter.value.id) {
			PendingChapter(
				order = pendingChapters.size,
				chapter = chapter.value,
				dir = File(stagingDir, PATTERN_CHAPTER_DIR.format(pendingChapters.size)),
			)
		}
		val name = PATTERN_PAGE_FILE.format(pageNumber) + extensionOf(file, type)
		runInterruptible(Dispatchers.IO) {
			pending.dir.mkdirs()
			file.copyTo(File(pending.dir, name), overwrite = true)
		}
		Unit
	}

	override suspend fun flushChapter(chapter: MangaChapter): Boolean = mutex.withLock {
		val pending = pendingChapters.remove(chapter.id) ?: return@withLock false
		runInterruptible(Dispatchers.IO) {
			writeChapter(pending)
		}
		false // a PDF file is never a part of the local library
	}

	override suspend fun finish() = mutex.withLock {
		runInterruptible(Dispatchers.IO) {
			for (pending in pendingChapters.values.sortedBy { it.order }) {
				writeChapter(pending)
			}
			pendingChapters.clear()
			writer?.let { pdf ->
				if (pdf.pagesCount == 0) { // nothing has been downloaded
					pdf.closeQuietly()
					tempFile.delete()
				} else {
					pdf.finish()
					pdf.close()
					tempFile.renameTo(rootFile)
				}
				writer = null
			}
			stagingDir.deleteRecursively()
		}
		Unit
	}

	override suspend fun cleanup() = mutex.withLock {
		runInterruptible(Dispatchers.IO) {
			stagingDir.deleteRecursively()
			tempFile.delete()
		}
		Unit
	}

	override suspend fun getLocalManga(): LocalManga? = null

	override fun close() {
		writer?.closeQuietly()
		writer = null
	}

	private fun writeChapter(pending: PendingChapter) {
		val pages = pending.dir.listFiles()?.sortedBy { it.name }
		if (pages.isNullOrEmpty()) {
			pending.dir.deleteRecursively()
			return
		}
		if (isSplitByChapters) {
			rootFile.mkdirs()
			// prevent the local library from picking the directory up as a manga
			File(rootFile, FILENAME_SKIP).createNewFile()
			val chapterTempFile = File(rootFile, chapterFileName(pending) + SUFFIX_TMP)
			val pdf = PdfWriter(chapterTempFile, pending.chapter.title ?: manga.title)
			try {
				pages.forEach { pdf.addPage(it) }
				pdf.finish()
				pdf.close()
				chapterTempFile.renameTo(File(rootFile, chapterFileName(pending)))
			} catch (e: Throwable) {
				pdf.closeQuietly()
				chapterTempFile.delete()
				throw e
			}
		} else {
			val pdf = requireWriter()
			pages.forEach { pdf.addPage(it) }
		}
		pending.dir.deleteRecursively()
	}

	private fun requireWriter(): PdfWriter = writer ?: PdfWriter(tempFile, manga.title).also { writer = it }

	private fun chapterFileName(pending: PendingChapter): String = buildString {
		append(PATTERN_CHAPTER_DIR.format(pending.order))
		pending.chapter.title?.nullIfEmpty()?.toFileNameSafe()?.let {
			append('_')
			append(if (it.length > MAX_TITLE_LENGTH) it.substring(0, MAX_TITLE_LENGTH) else it)
		}
		append(EXTENSION_PDF)
	}

	private fun extensionOf(file: File, type: MimeType?): String {
		val extension = MimeTypes.getExtension(type)
			?: file.extension.takeUnless { it.isEmpty() || it.equals("tmp", ignoreCase = true) }
			?: return ""
		return ".$extension"
	}

	private class PendingChapter(
		val order: Int,
		val chapter: MangaChapter,
		val dir: File,
	)

	companion object {

		const val EXTENSION_PDF = ".pdf"

		private const val SUFFIX_STAGING = ".pages.tmp"
		private const val PATTERN_CHAPTER_DIR = "%04d"
		private const val PATTERN_PAGE_FILE = "%05d"
		private const val MAX_TITLE_LENGTH = 32

		fun findFreeFile(root: File, manga: Manga, isSplitByChapters: Boolean): File {
			val baseName = manga.title.toFileNameSafe()
			var i = 0
			while (true) {
				val name = if (i == 0) baseName else baseName + "_$i"
				val file = if (isSplitByChapters) File(root, name) else File(root, name + EXTENSION_PDF)
				if (!file.exists()) {
					return file
				}
				i++
			}
		}
	}
}
