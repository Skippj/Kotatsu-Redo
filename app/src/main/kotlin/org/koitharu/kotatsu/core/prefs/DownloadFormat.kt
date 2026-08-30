package org.koitharu.kotatsu.core.prefs

import androidx.annotation.Keep

@Keep
enum class DownloadFormat {

	AUTOMATIC,
	SINGLE_CBZ,
	MULTIPLE_CBZ,
	SINGLE_PDF,
	MULTIPLE_PDF,
	;

	/**
	 * PDF is an export-only format: such files are not indexed by the local library
	 */
	val isPdf: Boolean
		get() = this == SINGLE_PDF || this == MULTIPLE_PDF
}
