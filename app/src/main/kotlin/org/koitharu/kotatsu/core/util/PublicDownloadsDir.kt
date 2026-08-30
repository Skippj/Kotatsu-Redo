package org.koitharu.kotatsu.core.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import org.jetbrains.annotations.Blocking
import java.io.File
import java.io.IOException

/**
 * Puts files into the shared `Download` directory of the device, where they stay visible to a file manager
 * and to any other app, unlike everything an app writes into its own storage.
 */
object PublicDownloadsDir {

	/**
	 * A subdirectory of `Download` that all the files are put into.
	 */
	const val DIR_NAME = "Kotatsu"

	/**
	 * Copies [source] into `Download/[DIR_NAME]/[subDir]` and returns the uri of the resulting file.
	 * An existing file with the same name is never overwritten: a numbered suffix is added instead.
	 */
	@Blocking
	fun publish(
		context: Context,
		source: File,
		displayName: String,
		mimeType: String,
		subDir: String? = null,
	): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		publishToMediaStore(context, source, displayName, mimeType, subDir)
	} else {
		publishToFile(context, source, displayName, mimeType, subDir)
	}

	@Blocking
	@RequiresApi(Build.VERSION_CODES.Q)
	private fun publishToMediaStore(
		context: Context,
		source: File,
		displayName: String,
		mimeType: String,
		subDir: String?,
	): Uri {
		val resolver = context.contentResolver
		val values = ContentValues(4)
		values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
		values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
		values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(subDir))
		// hide the file from other apps until it is fully written
		values.put(MediaStore.MediaColumns.IS_PENDING, 1)
		val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
			?: throw IOException("Cannot create \"$displayName\" in the Download directory")
		try {
			val output = resolver.openOutputStream(uri)
				?: throw IOException("Cannot open \"$displayName\" for writing")
			output.use { source.inputStream().use { input -> input.copyTo(output) } }
		} catch (e: Throwable) {
			runCatching { resolver.delete(uri, null, null) }
			throw e
		}
		values.clear()
		values.put(MediaStore.MediaColumns.IS_PENDING, 0)
		resolver.update(uri, values, null, null)
		return uri
	}

	@Blocking
	private fun publishToFile(
		context: Context,
		source: File,
		displayName: String,
		mimeType: String,
		subDir: String?,
	): Uri {
		val dir = File(Environment.getExternalStorageDirectory(), relativePath(subDir))
		if (!dir.isDirectory && !dir.mkdirs()) {
			throw IOException("Cannot create the ${dir.path} directory")
		}
		val target = freeFile(dir, displayName)
		source.copyTo(target, overwrite = false)
		// without this the file is not listed by the gallery and by file managers until the next media scan
		MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mimeType), null)
		return target.toUri()
	}

	private fun relativePath(subDir: String?): String = buildString {
		append(Environment.DIRECTORY_DOWNLOADS)
		append(File.separatorChar)
		append(DIR_NAME)
		if (!subDir.isNullOrEmpty()) {
			append(File.separatorChar)
			append(subDir)
		}
	}

	private fun freeFile(dir: File, displayName: String): File {
		val baseName = displayName.substringBeforeLast('.')
		val extension = displayName.substringAfterLast('.', "")
		var i = 0
		while (true) {
			val name = buildString {
				append(baseName)
				if (i != 0) {
					append(" (")
					append(i)
					append(')')
				}
				if (extension.isNotEmpty()) {
					append('.')
					append(extension)
				}
			}
			val file = File(dir, name)
			if (!file.exists()) {
				return file
			}
			i++
		}
	}
}
