package org.koitharu.kotatsu.core.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import androidx.annotation.WorkerThread
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * A minimal streaming PDF writer that puts each image on a separate page.
 *
 * Pages are appended one by one and written to the disk immediately, so the memory footprint does not depend
 * on the total pages count. Baseline JPEG images are embedded as is (`DCTDecode`) without any re-encoding,
 * images of other formats are converted to JPEG first.
 */
class PdfWriter(
	val file: File,
	private val title: String?,
) : Closeable {

	private val output = CountingOutputStream(BufferedOutputStream(FileOutputStream(file)))

	/**
	 * Byte offsets of the objects: an item at the index `i` is the offset of the object number `i + 1`.
	 * The catalog and the page tree are written at the very end, so their offsets are patched by [finish].
	 */
	private val offsets = ArrayList<Long>(INITIAL_CAPACITY)
	private val pageObjects = ArrayList<Int>(INITIAL_CAPACITY)
	private var isFinished = false

	val pagesCount: Int
		get() = pageObjects.size

	init {
		offsets.add(0L) // OBJ_CATALOG
		offsets.add(0L) // OBJ_PAGES
		output.writeAscii("%PDF-1.4\n")
		// a comment with high bytes that marks the file as a binary one
		output.write(byteArrayOf('%'.code.toByte(), -30, -29, -49, -45, '\n'.code.toByte()))
	}

	@Blocking
	@WorkerThread
	fun addPage(imageFile: File): Boolean {
		check(!isFinished) { "PdfWriter is already finished" }
		val image = JpegImage.from(imageFile) ?: return false
		// scale the image down to fit an A4 page, keeping the aspect ratio. Smaller images are not upscaled
		val scale = minOf(PAGE_WIDTH / image.width, PAGE_HEIGHT / image.height, 1f)
		val pageWidth = image.width * scale
		val pageHeight = image.height * scale

		val imageObject = beginObject()
		output.writeAscii(
			"<</Type/XObject/Subtype/Image/Width ${image.width}/Height ${image.height}" +
				"/ColorSpace/${image.colorSpace}/BitsPerComponent 8/Filter/DCTDecode/Length ${image.length}>>\n",
		)
		output.writeAscii("stream\n")
		image.writeTo(output)
		output.writeAscii("\nendstream\n")
		endObject()

		val content = "q\n${pageWidth.format()} 0 0 ${pageHeight.format()} 0 0 cm\n/$NAME_IMAGE Do\nQ\n"
		val contentObject = beginObject()
		output.writeAscii("<</Length ${content.length}>>\nstream\n")
		output.writeAscii(content)
		output.writeAscii("endstream\n")
		endObject()

		val pageObject = beginObject()
		output.writeAscii(
			"<</Type/Page/Parent $OBJ_PAGES 0 R/MediaBox[0 0 ${pageWidth.format()} ${pageHeight.format()}]" +
				"/Resources<</XObject<</$NAME_IMAGE $imageObject 0 R>>>>/Contents $contentObject 0 R>>\n",
		)
		endObject()
		pageObjects.add(pageObject)
		return true
	}

	@Blocking
	@WorkerThread
	fun finish() {
		check(!isFinished) { "PdfWriter is already finished" }
		check(pageObjects.isNotEmpty()) { "Cannot write a PDF file without pages" }
		isFinished = true
		val infoObject = title?.let { writeInfoObject(it) }

		offsets[OBJ_PAGES - 1] = output.count
		output.writeAscii(
			pageObjects.joinToString(
				separator = " ",
				prefix = "$OBJ_PAGES 0 obj\n<</Type/Pages/Count ${pageObjects.size}/Kids[",
				postfix = "]>>\nendobj\n",
			) { "$it 0 R" },
		)

		offsets[OBJ_CATALOG - 1] = output.count
		output.writeAscii("$OBJ_CATALOG 0 obj\n<</Type/Catalog/Pages $OBJ_PAGES 0 R>>\nendobj\n")

		val xrefOffset = output.count
		output.writeAscii("xref\n0 ${offsets.size + 1}\n")
		output.writeAscii("0000000000 65535 f \n")
		for (offset in offsets) {
			output.writeAscii(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
		}
		output.writeAscii("trailer\n<</Size ${offsets.size + 1}/Root $OBJ_CATALOG 0 R")
		if (infoObject != null) {
			output.writeAscii("/Info $infoObject 0 R")
		}
		output.writeAscii(">>\nstartxref\n$xrefOffset\n%%EOF\n")
		output.flush()
	}

	override fun close() {
		output.close()
	}

	private fun writeInfoObject(value: String): Int {
		val objectNumber = beginObject()
		output.writeAscii("<</Title ${value.toPdfTextString()}>>\n")
		endObject()
		return objectNumber
	}

	private fun beginObject(): Int {
		val number = offsets.size + 1
		offsets.add(output.count)
		output.writeAscii("$number 0 obj\n")
		return number
	}

	private fun endObject() {
		output.writeAscii("endobj\n")
	}

	private class JpegImage(
		val width: Int,
		val height: Int,
		val colorSpace: String,
		val length: Long,
		private val file: File?,
		private val bytes: ByteArray?,
	) {

		fun writeTo(output: OutputStream) {
			if (file != null) {
				file.inputStream().use { it.copyTo(output) }
			} else if (bytes != null) {
				output.write(bytes)
			}
		}

		companion object {

			fun from(imageFile: File): JpegImage? = readBaseline(imageFile) ?: reEncode(imageFile)

			/**
			 * Baseline JPEG data can be embedded into a PDF as is, without re-encoding and quality loss.
			 */
			private fun readBaseline(imageFile: File): JpegImage? = try {
				imageFile.inputStream().buffered().use { input ->
					val header = JpegHeader.read(input)
					val colorSpace = when (header?.components) {
						1 -> COLOR_SPACE_GRAY
						3 -> COLOR_SPACE_RGB
						else -> null
					}
					if (header != null && colorSpace != null) {
						JpegImage(
							width = header.width,
							height = header.height,
							colorSpace = colorSpace,
							length = imageFile.length(),
							file = imageFile,
							bytes = null,
						)
					} else {
						null
					}
				}
			} catch (e: IOException) {
				null
			}

			private fun reEncode(imageFile: File): JpegImage? {
				val bitmap = decodeSoftware(imageFile)
				try {
					// JPEG has no alpha channel, so the transparent parts are put on white instead of black.
					// A bitmap that cannot be drawn on is encoded as is rather than failing the whole download.
					if (bitmap.hasAlpha() && bitmap.isMutable && bitmap.config != CONFIG_HARDWARE) {
						Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
					}
					val buffer = ByteArrayOutputStream(DEFAULT_JPEG_BUFFER_SIZE)
					if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) {
						return null
					}
					val bytes = buffer.toByteArray()
					return JpegImage(
						width = bitmap.width,
						height = bitmap.height,
						colorSpace = COLOR_SPACE_RGB,
						length = bytes.size.toLong(),
						file = null,
						bytes = bytes,
					)
				} finally {
					bitmap.recycle()
				}
			}

			/**
			 * Decoding through [android.graphics.ImageDecoder], as [BitmapDecoderCompat] does, may produce a
			 * hardware bitmap, which can neither be drawn on a software canvas nor read back by the encoder.
			 * [BitmapFactory] never does, so it is preferred here, and the generic decoder is kept only as a
			 * fallback for the formats it cannot read, such as AVIF.
			 */
			private fun decodeSoftware(imageFile: File): Bitmap {
				val type = BitmapDecoderCompat.probeMimeType(imageFile)
				if (type?.subtype != FORMAT_AVIF) {
					val options = BitmapFactory.Options()
					options.inMutable = true
					options.inPreferredConfig = Bitmap.Config.ARGB_8888
					runCatchingCancellable {
						BitmapFactory.decodeFile(imageFile.absolutePath, options)
					}.onFailure {
						it.printStackTraceDebug()
					}.getOrNull()?.let { return it }
				}
				return BitmapDecoderCompat.decode(imageFile)
			}

			private const val FORMAT_AVIF = "avif"

			/** `null` below Android 8, where hardware bitmaps do not exist yet */
			private val CONFIG_HARDWARE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				Bitmap.Config.HARDWARE
			} else {
				null
			}

			private const val COLOR_SPACE_RGB = "DeviceRGB"
			private const val COLOR_SPACE_GRAY = "DeviceGray"
			private const val JPEG_QUALITY = 92
			private const val DEFAULT_JPEG_BUFFER_SIZE = 512 * 1024
		}
	}

	/**
	 * A `SOF0`/`SOF1` (baseline) segment of a JPEG file. Reading returns `null` for anything else,
	 * including progressive JPEGs that are not supported by the PDF `DCTDecode` filter.
	 */
	private class JpegHeader(
		val width: Int,
		val height: Int,
		val components: Int,
	) {

		companion object {

			fun read(input: InputStream): JpegHeader? {
				if (input.readUShort() != MARKER_SOI) {
					return null
				}
				while (true) {
					var marker = input.readUByte()
					while (marker != MARKER_PREFIX) { // resynchronize on a marker boundary
						marker = input.readUByte()
					}
					while (marker == MARKER_PREFIX) { // skip the fill bytes
						marker = input.readUByte()
					}
					when (marker) {
						SOF_BASELINE, SOF_EXTENDED -> {
							input.readUShort() // segment length
							input.readUByte() // sample precision
							val height = input.readUShort()
							val width = input.readUShort()
							return JpegHeader(width, height, input.readUByte())
						}

						MARKER_EOI, MARKER_SOS -> return null // no baseline SOF segment found

						in MARKER_RST_FIRST..MARKER_SOI_BYTE, MARKER_TEM -> Unit // no payload

						else -> input.skipFully(input.readUShort() - 2L)
					}
				}
			}

			private const val MARKER_SOI = 0xFFD8
			private const val MARKER_PREFIX = 0xFF
			private const val SOF_BASELINE = 0xC0
			private const val SOF_EXTENDED = 0xC1
			private const val MARKER_SOS = 0xDA
			private const val MARKER_EOI = 0xD9
			private const val MARKER_RST_FIRST = 0xD0
			private const val MARKER_SOI_BYTE = 0xD8
			private const val MARKER_TEM = 0x01

			private fun InputStream.readUByte(): Int = read().also {
				if (it == -1) throw IOException("Unexpected end of a JPEG file")
			}

			private fun InputStream.readUShort(): Int = (readUByte() shl 8) or readUByte()

			private fun InputStream.skipFully(count: Long) {
				var remaining = count
				while (remaining > 0) {
					val skipped = skip(remaining)
					if (skipped <= 0L) {
						readUByte()
						remaining--
					} else {
						remaining -= skipped
					}
				}
			}
		}
	}

	private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {

		var count: Long = 0L
			private set

		override fun write(b: Int) {
			delegate.write(b)
			count++
		}

		override fun write(b: ByteArray, off: Int, len: Int) {
			delegate.write(b, off, len)
			count += len
		}

		override fun flush() = delegate.flush()

		override fun close() {
			try {
				delegate.flush()
			} finally {
				delegate.close()
			}
		}
	}

	private companion object {

		const val OBJ_CATALOG = 1
		const val OBJ_PAGES = 2
		const val NAME_IMAGE = "Im0"
		const val INITIAL_CAPACITY = 64

		/** An A4 page size in the PDF user space units (1/72 inch) */
		const val PAGE_WIDTH = 595f
		const val PAGE_HEIGHT = 842f

		fun OutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.ISO_8859_1))

		fun Float.format(): String = String.format(Locale.ROOT, "%.2f", this)

		fun String.toPdfTextString(): String = buildString(length * 4 + 6) {
			append("<FEFF")
			for (char in this@toPdfTextString) {
				append(String.format(Locale.ROOT, "%04X", char.code))
			}
			append('>')
		}
	}
}
