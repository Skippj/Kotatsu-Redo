package org.koitharu.kotatsu.local.data.output

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.core.pdf.PdfWriter
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.PublicDownloadsDir
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.toFileNameSafe
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.io.File
import java.io.IOException

/**
 * Writes downloaded manga into PDF files.
 *
 * A PDF is an export format rather than a library one: the resulting files are put into the shared
 * `Download` directory of the device so that they can be opened by any PDF viewer, and they are not
 * indexed by the local library nor readable by the app itself, so [getLocalManga] returns `null`.
 *
 * [rootFile] is only a temporary working directory inside the app storage; everything in it is removed
 * once the files have been published by [finish].
 */
class LocalMangaPdfOutput(
	private val context: Context,
	rootFile: File,
	private val manga: Manga,
	private val isSplitByChapters: Boolean,
) : LocalMangaOutput(rootFile) {

	private val mutex = Mutex()

	/**
	 * Pages are downloaded in parallel and may arrive out of order, while a PDF file is written sequentially,
	 * so pages are staged here first and flushed chapter by chapter.
	 */
	private val stagingDir = File(rootFile, DIR_PAGES)
	private val outputDir = File(rootFile, DIR_OUTPUT)
	private val pendingChapters = LinkedHashMap<Long, PendingChapter>()
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
				dir = File(stagingDir, PATTERN_ORDER.format(pendingChapters.size)),
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
			closeWriter()?.let { publish(it) }
			rootFile.deleteRecursively()
		}
		Unit
	}

	override suspend fun cleanup() = mutex.withLock {
		runInterruptible(Dispatchers.IO) {
			rootFile.deleteRecursively()
		}
		Unit
	}

	override suspend fun getLocalManga(): LocalManga? = null

	override fun close() {
		writer?.closeQuietly()
		writer = null
	}

	/**
	 * Moves a produced file from the app storage into the shared `Download` directory of the device.
	 */
	private fun publish(file: File) {
		PublicDownloadsDir.publish(
			context = context,
			source = file,
			displayName = file.name,
			mimeType = MIME_TYPE_PDF,
			subDir = if (isSplitByChapters) manga.title.toFileNameSafe() else null,
		)
		file.delete()
	}

	private fun writeChapter(pending: PendingChapter) {
		val pages = pending.dir.listFiles()?.sortedBy { it.name }
		if (pages.isNullOrEmpty()) {
			pending.dir.deleteRecursively()
			return
		}
		if (isSplitByChapters) {
			val target = File(outputDir, chapterFileName(pending))
			val pdf = createWriter(target, pending.chapter.title ?: manga.title)
			try {
				pages.forEach { pdf.addPage(it) }
				pdf.finish()
				pdf.close()
			} catch (e: Throwable) {
				pdf.closeQuietly()
				target.delete()
				throw e
			}
			// published right away so that only one chapter at a time is kept in the app storage
			publish(target)
		} else {
			val pdf = requireWriter()
			pages.forEach { pdf.addPage(it) }
		}
		pending.dir.deleteRecursively()
	}

	private fun requireWriter(): PdfWriter = writer ?: createWriter(
		File(outputDir, manga.title.toFileNameSafe() + EXTENSION_PDF),
		manga.title,
	).also { writer = it }

	private fun createWriter(target: File, title: String): PdfWriter {
		outputDir.mkdirs()
		return PdfWriter(target, title)
	}

	/**
	 * Finalizes the single-file writer, if any, and returns the resulting PDF file.
	 */
	private fun closeWriter(): File? {
		val pdf = writer ?: return null
		writer = null
		if (pdf.pagesCount == 0) { // nothing has been downloaded
			pdf.closeQuietly()
			pdf.file.delete()
			return null
		}
		pdf.finish()
		pdf.close()
		return pdf.file
	}

	private fun chapterFileName(pending: PendingChapter): String = buildString {
		append(PATTERN_ORDER.format(pending.order))
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

		private const val EXTENSION_PDF = ".pdf"
		private const val MIME_TYPE_PDF = "application/pdf"
		private const val DIR_WORK = "pdf"
		private const val DIR_PAGES = "pages"
		private const val DIR_OUTPUT = "out"
		private const val PATTERN_ORDER = "%04d"
		private const val PATTERN_PAGE_FILE = "%05d"
		private const val MAX_TITLE_LENGTH = 32

		fun create(context: Context, manga: Manga, isSplitByChapters: Boolean): LocalMangaPdfOutput {
			checkCanWrite(context)
			// the app own external storage: big enough for a whole manga and not scanned by the local library
			val base = context.getExternalFilesDir(DIR_WORK) ?: File(context.cacheDir, DIR_WORK)
			val workDir = File(base, manga.id.toString())
			workDir.deleteRecursively() // leftovers of a previously interrupted download
			return LocalMangaPdfOutput(
				context = context,
				rootFile = workDir,
				manga = manga,
				isSplitByChapters = isSplitByChapters,
			)
		}

		/**
		 * Below Android 10 there is no [android.provider.MediaStore] entry point for the `Download` directory,
		 * so writing into it requires a runtime permission that the user may have not granted. It is checked
		 * upfront, to fail before anything is downloaded rather than after.
		 */
		private fun checkCanWrite(context: Context) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				return
			}
			val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
			if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
				throw IOException("Storage permission is required to save PDF files into the Download directory")
			}
		}
	}
}
