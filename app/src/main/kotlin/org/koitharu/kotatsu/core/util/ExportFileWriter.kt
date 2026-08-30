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
import androidx.documentfile.provider.DocumentFile
import org.jetbrains.annotations.Blocking
import java.io.File
import java.io.IOException

/**
 * Puts files the user is supposed to open outside of the app into a directory that is visible to a file manager,
 * unlike everything the app writes into its own storage.
 */
object ExportFileWriter {

	/**
	 * A subdirectory that files are grouped into when no directory has been chosen by the user.
	 */
	const val DEFAULT_DIR_NAME = "Kotatsu"

	/**
	 * Copies [source] into [treeUri], or into `Download/[DEFAULT_DIR_NAME]` if it is `null`,
	 * and returns the uri of the resulting file. An existing file is never overwritten.
	 *
	 * @param treeUri a directory picked by the user through the storage access framework
	 * @param subDir an optional subdirectory to group several related files into
	 */
	@Blocking
	fun write(
		context: Context,
		source: File,
		displayName: String,
		mimeType: String,
		subDir: String? = null,
		treeUri: Uri? = null,
	): Uri = when {
		treeUri != null -> writeToTree(context, treeUri, source, displayName, mimeType, subDir)
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
			writeToMediaStore(context, source, displayName, mimeType, subDir)

		else -> writeToPublicDir(context, source, displayName, mimeType, subDir)
	}

	@Blocking
	private fun writeToTree(
		context: Context,
		treeUri: Uri,
		source: File,
		displayName: String,
		mimeType: String,
		subDir: String?,
	): Uri {
		var dir = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.canWrite() }
			?: throw IOException("The selected directory is not writeable, please choose it again in the settings")
		if (!subDir.isNullOrEmpty()) {
			dir = dir.findFile(subDir)?.takeIf { it.isDirectory }
				?: dir.createDirectory(subDir)
				?: throw IOException("Cannot create the \"$subDir\" directory")
		}
		// the storage access framework adds a numbered suffix on its own if the name is already taken
		val file = dir.createFile(mimeType, displayName)
			?: throw IOException("Cannot create \"$displayName\" in ${dir.name}")
		copyTo(context, source, file.uri)
		return file.uri
	}

	@Blocking
	@RequiresApi(Build.VERSION_CODES.Q)
	private fun writeToMediaStore(
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
		values.put(MediaStore.MediaColumns.RELATIVE_PATH, defaultRelativePath(subDir))
		// hide the file from other apps until it is fully written
		values.put(MediaStore.MediaColumns.IS_PENDING, 1)
		val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
		val uri = resolver.insert(collection, values)
			?: throw IOException("Cannot create \"$displayName\" in the Download directory")
		try {
			copyTo(context, source, uri)
			values.clear()
			values.put(MediaStore.MediaColumns.IS_PENDING, 0)
			// a file left pending stays invisible to the user, so this must not be allowed to fail silently
			if (resolver.update(uri, values, null, null) <= 0) {
				throw IOException("Cannot publish \"$displayName\" into the Download directory")
			}
		} catch (e: Throwable) {
			runCatching { resolver.delete(uri, null, null) }
			throw e
		}
		return uri
	}

	@Blocking
	private fun writeToPublicDir(
		context: Context,
		source: File,
		displayName: String,
		mimeType: String,
		subDir: String?,
	): Uri {
		val dir = File(Environment.getExternalStorageDirectory(), defaultRelativePath(subDir))
		if (!dir.isDirectory && !dir.mkdirs()) {
			throw IOException("Cannot create the ${dir.path} directory")
		}
		val target = freeFile(dir, displayName)
		source.copyTo(target, overwrite = false)
		// without this the file is not listed by file managers until the next media scan
		MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mimeType), null)
		return target.toUri()
	}

	@Blocking
	private fun copyTo(context: Context, source: File, destination: Uri) {
		val output = context.contentResolver.openOutputStream(destination)
			?: throw IOException("Cannot open $destination for writing")
		output.use { source.inputStream().use { input -> input.copyTo(output) } }
	}

	private fun defaultRelativePath(subDir: String?): String = buildString {
		append(Environment.DIRECTORY_DOWNLOADS)
		append(File.separatorChar)
		append(DEFAULT_DIR_NAME)
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
