package org.koitharu.kotatsu.parsers.site.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ITHINKILIKEYOU", "IThinkILikeYou", "en", ContentType.COMICS)
internal class IThinkILikeYou(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.ITHINKILIKEYOU) {

	override val configKeyDomain = ConfigKey.Domain("ithinkilikeyou.net")

	override val availableSortOrders: Set<SortOrder> = Collections.singleton(SortOrder.NEWEST)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities()

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		// Each /series/ entry is a separate comic. The homepage grid carries titles + covers,
		// but a few series (e.g. the Spanish editions) only appear as plain menu links, so we
		// gather every distinct /series/ slug and enrich it from the grid where possible.
		val result = LinkedHashMap<String, Manga>()
		doc.select("#series-grid span.series-thumbnail-wrapper").forEach { item ->
			val href = item.selectFirst("a[href*=/series/]")?.attrAsRelativeUrlOrNull("href") ?: return@forEach
			val slug = href.seriesSlug() ?: return@forEach
			result[slug] = buildSeries(
				slug = slug,
				title = item.selectFirst(".series-rollover h3")?.textOrNull(),
				cover = item.selectFirst("img")?.src(),
			)
		}
		doc.select("a[href*=/series/]").forEach { a ->
			val slug = a.attrAsRelativeUrlOrNull("href")?.seriesSlug() ?: return@forEach
			if (slug !in result) {
				result[slug] = buildSeries(slug = slug, title = a.textOrNull(), cover = null)
			}
		}
		return result.values.toList()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val firstPage = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val lastPage = firstPage.select("nav.pagination a.page-numbers")
			.mapNotNull { it.text().trim().toIntOrNull() }
			.maxOrNull() ?: 1
		val chapters = ArrayList<MangaChapter>()
		var firstCover: String? = null
		for (page in 1..lastPage) {
			val doc = if (page == 1) {
				firstPage
			} else {
				webClient.httpGet("${manga.url.toAbsoluteUrl(domain)}?comics_paged=$page").parseHtml()
			}
			doc.select("ul#comic-list > li.comic").forEach { li ->
				// "future-comic" without the "d-none" modifier marks a Patreon-locked / early-access
				// episode whose pages are not publicly readable, so we skip those.
				val locked = li.selectFirst(".future-comic")?.let { !it.hasClass("d-none") } ?: false
				if (locked) return@forEach
				val href = li.selectFirst("a[href*=/comic/]")?.attrAsRelativeUrlOrNull("href") ?: return@forEach
				if (firstCover == null) {
					firstCover = li.selectFirst(".thmb img")?.src()
				}
				chapters.add(
					MangaChapter(
						id = generateUid(href),
						title = li.selectFirst(".comic-title")?.textOrNull(),
						number = 0f,
						volume = 0,
						url = href,
						scanlator = null,
						branch = null,
						uploadDate = chapterDateFormat.parseSafe(li.selectFirst(".comic-post-date")?.textOrNull()),
						source = source,
					),
				)
			}
		}
		// Pages are listed newest-first; reverse so the first chapter is the oldest episode.
		chapters.reverse()
		return manga.copy(
			coverUrl = manga.coverUrl ?: firstCover,
			chapters = chapters.mapIndexed { i, chapter -> chapter.copy(number = i + 1f) },
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("#spliced-comic .default-lang img")
			.mapNotNull { it.src() }
			.filter { it.contains("/wp-content/uploads/") }
			.map { url ->
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
	}

	private fun buildSeries(slug: String, title: String?, cover: String?): Manga {
		val url = "/series/$slug/"
		return Manga(
			id = generateUid(url),
			title = title?.takeUnless { it.isBlank() } ?: slug.toTitle(),
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			coverUrl = cover,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun String.seriesSlug(): String? = substringAfter("/series/", "")
		.substringBefore('?')
		.substringBefore('#')
		.trim('/')
		.nullIfEmpty()

	private fun String.toTitle(): String = replace('-', ' ')
		.split(' ')
		.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}
