package org.koitharu.kotatsu.alternatives.domain

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcePresetsRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import java.util.Locale
import javax.inject.Inject

private const val MAX_PARALLELISM = 8
private const val MAX_PER_SOURCE_PARALLELISM = 2
private const val SOURCE_REPLACEMENT_TAG = "SourceReplacement"

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
	private val presetsRepository: SourcePresetsRepository,
) {
	private val requestSemaphore = Semaphore(MAX_PARALLELISM)
	private val webViewSemaphore = Semaphore(1)
	private val sourceSemaphores = mutableMapOf<String, Semaphore>()
	private val webViewSources = mutableMapOf<String, Boolean>()

	suspend operator fun invoke(
		manga: Manga,
		throughDisabledSources: Boolean,
		query: String = manga.title,
		loadDetails: Boolean = true,
	): Flow<Manga> {
		val sources = getCandidateSources(manga.source, throughDisabledSources)
		return search(manga, sources, query, loadDetails)
	}

	suspend fun getCandidateSources(
		ref: MangaSource,
		throughDisabledSources: Boolean,
	): List<MangaSource> = getSources(ref, throughDisabledSources)

	suspend fun searchSource(
		manga: Manga,
		source: MangaSource,
		query: String = manga.title,
		loadDetails: Boolean = true,
		onFailure: (() -> Unit)? = null,
	): Flow<Manga> = if (source.name == manga.source.name) {
		emptyFlow()
	} else {
		search(
			manga = manga,
			sources = listOf(source),
			query = query,
			loadDetails = loadDetails,
			onSourceFailure = { onFailure?.invoke() },
		)
	}

	private fun search(
		manga: Manga,
		sources: List<MangaSource>,
		query: String,
		loadDetails: Boolean,
		onSourceFailure: (MangaSource) -> Unit = {},
	): Flow<Manga> {
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		return channelFlow {
			for (source in sources) {
				launch {
					val searchHelper = searchHelperFactory.create(source)
					val searchResult = runCatchingCancellable {
						withSourcePermit(source) {
							searchHelper(query, SearchKind.TITLE)?.manga
						}
					}
					searchResult.exceptionOrNull()?.let { error ->
						Log.w(
							SOURCE_REPLACEMENT_TAG,
							"Search failed: source=${source.name} query=\"${query.take(120)}\"",
							error,
						)
						onSourceFailure(source)
					}
					val list = searchResult.getOrNull()
					list?.forEach { m ->
						if (m.id != manga.id) {
							if (loadDetails) {
								launch {
									val detailsResult = runCatchingCancellable {
										withSourcePermit(source) {
											mangaRepositoryFactory.create(m.source).getDetails(m)
										}
									}
									detailsResult.exceptionOrNull()?.let { error ->
										Log.w(
											SOURCE_REPLACEMENT_TAG,
											"Details load failed; using search result: source=${source.name} title=\"${m.title}\" id=${m.id}",
											error,
										)
									}
									val details = detailsResult.getOrDefault(m)
									send(details)
								}
							} else {
								send(m)
							}
						}
					}
				}
			}
		}
	}

	private suspend fun <T> withSourcePermit(source: MangaSource, block: suspend () -> T): T {
		return source.requestSemaphore().withPermit {
			if (source.usesWebView()) {
				webViewSemaphore.withPermit {
					requestSemaphore.withPermit { block() }
				}
			} else {
				requestSemaphore.withPermit { block() }
			}
		}
	}

	fun isWebViewSource(source: MangaSource): Boolean = source.usesWebView()

	private fun MangaSource.requestSemaphore(): Semaphore = synchronized(sourceSemaphores) {
		sourceSemaphores.getOrPut(name) {
			Semaphore(if (usesWebView()) 1 else MAX_PER_SOURCE_PARALLELISM)
		}
	}

	private fun MangaSource.usesWebView(): Boolean = synchronized(webViewSources) {
		webViewSources.getOrPut(name) {
			val repository = mangaRepositoryFactory.create(this)
			((repository as? ParserMangaRepository)
				?.getConfigKeys()
				?.filterIsInstance<ConfigKey.DisableUpdateChecking>()
				?.any { it.defaultValue }
				== true)
		}
	}

	private suspend fun getSources(ref: MangaSource, disabled: Boolean): List<MangaSource> {
		val presetId = settings.activeSourcePresetId
		if (presetId != 0L) {
			val preset = presetsRepository.getById(presetId)
			if (preset != null) {
				if (preset.sources.isEmpty()) return emptyList()
				val skipNsfw = settings.isNsfwContentDisabled
				return sourcesRepository.allMangaSources.filter { source ->
					source.name != ref.name &&
						source.hasSameLanguageAs(ref) &&
						source.name in preset.sources &&
						(!skipNsfw || !source.isNsfw())
				}.sortedByDescending { it.priority(ref) }
			}
		}
		return (if (disabled) {
			sourcesRepository.getDisabledSources().toList()
		} else {
			sourcesRepository.getEnabledSources()
		}).filter { it.name != ref.name && it.hasSameLanguageAs(ref) }
			.sortedByDescending { it.priority(ref) }
	}

	private fun MangaSource.hasSameLanguageAs(ref: MangaSource): Boolean {
		val refLanguage = ref.getLocale()?.language?.takeIf(String::isNotBlank) ?: return true
		return getLocale()?.language?.takeIf(String::isNotBlank) == refLanguage
	}

	private fun MangaSource.priority(ref: MangaSource): Int {
		var res = 0
		if (this is MangaParserSource && ref is MangaParserSource) {
			if (locale == ref.locale) {
				res += 4
			} else if (locale.toLocale() == Locale.getDefault()) {
				res += 2
			}
			if (contentType == ref.contentType) {
				res++
			}
		}
		return res
	}
}
