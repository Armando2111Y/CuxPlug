package com.ycngmn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimesamaProvider : MainAPI() {

    override var mainUrl = "https://anime-sama.si"
    override var name = "Anime-sama"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "fr"

    // On désactive ce qui est cassé
    override val hasMainPage = false
    override val hasQuickSearch = false

    // -------- UTILS --------

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.attr("href")
        val title = this.text()
        if (link.isBlank() || title.isBlank()) return null

        return newAnimeSearchResponse(
            title,
            link,
            TvType.Anime
        )
    }

    // -------- LOAD ANIME --------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, h2, h3")?.text()
            ?: throw ErrorLoadingException("Titre introuvable")

        val poster = doc.selectFirst("img")?.attr("src")

        val episodes = mutableListOf<Episode>()

        // Anime-sama.si → liens vers pages épisodes
        doc.select("a[href*=\"episode\"]").forEachIndexed { index, el ->
            val epUrl = el.attr("href")
            episodes.add(
                newEpisode(epUrl) {
                    name = "Épisode ${index + 1}"
                    episode = index + 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // -------- LOAD LINKS --------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        // Anime-sama.si → iframes
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http")) {
                loadExtractor(
                    src,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}        val rawSeasonData =
            doc.selectFirst("div.flex.flex-wrap.overflow-y-hidden.justify-start.bg-slate-900.bg-opacity-70.rounded.mt-2.h-auto script")
                ?.toString() ?: ""
        val extractedData = rawSeasonData.split("/*", "*/")[2]
        val pattern = Regex("""panneauAnime\("([^"]+)",\s*"([^"]+)"\);""")
        val panneauMap = mutableMapOf<String, String>()
        pattern.findAll(extractedData).forEach {
            val season = it.groupValues[1]
            val alias = it.groupValues[2]
            panneauMap[season] = alias
        }

        val seasonList = mutableListOf<SeasonData>()
        var index = 1
        panneauMap.forEach { (season, _) ->
            seasonList.add(SeasonData(index, season))
            index++
        }

        var season = 1
        val episodeList = mutableListOf<Episode>()
        val vfEpisodeList = mutableListOf<Episode>()

        panneauMap.forEach { (seasonName, alias) ->
            val streamPage = "$url/$alias"
            val urlTransforme = streamPage.removeSuffix("/").split("/").toMutableList()

            var asSources : Map<String, List<String>> = mapOf()
            val asSourcesVF : Map<String, List<String>>

            if (urlTransforme[urlTransforme.size - 1] != "vf") {
                asSources = retreiveSrcs(streamPage)
                urlTransforme[urlTransforme.size - 1] = "vf"
                val vfPage = urlTransforme.joinToString("/")
                asSourcesVF = retreiveSrcs(vfPage)

            } else {
                asSourcesVF = retreiveSrcs(streamPage)
            }

            val maxNbEpisodes = asSources.values.maxOfOrNull { it.size }
                ?: asSourcesVF.values.maxOfOrNull { it.size } ?: 0


            for (i in 0 until maxNbEpisodes) {

                val nom = when {
                    seasonName.contains("Saison") -> "$title S${season}EP${(i + 1).toString().padStart(2, '0')}"
                    seasonName in listOf("Film", "OAV", "Films") -> "$title $seasonName ${i + 1}"
                    else -> "$title ${i + 1}"
                }

                var datas = ""
                for ((_,link) in asSources) {
                    if (i < link.size)
                        datas += " " + link[i]
                }

                if (datas!="") {

                    episodeList.add(
                        newEpisode(datas) {
                            this.apply {
                                name = nom
                                episode = i + 1
                                posterUrl = image
                                this.season = season
                            }
                        }
                    )
                }


                datas = ""
                for ((_,link) in asSourcesVF) {
                    if (i < link.size){
                        datas += " " + link[i] }
                }
                if (datas!="") {
                    vfEpisodeList.add(

                        newEpisode(datas) {
                            this.apply {
                                name = nom
                                episode = i + 1
                                posterUrl = image
                                this.season = season
                            }
                        }
                    )
                }
            }
            season++
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {

            this.posterUrl = image
            this.plot = synopsis
            this.tags = tags
            this.synonyms = otherTitles
            addSeasonNames(seasonList)
            addEpisodes(DubStatus.Subbed, episodeList)
            if (vfEpisodeList.isNotEmpty())
                addEpisodes(DubStatus.Dubbed, vfEpisodeList)

        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val links = data.removePrefix(" ").split(" ")

        for (link in links)
            loadExtractor(link, subtitleCallback, callback)

        return true
    }

    /**
     * Extracts the stream host and episode URLs from AnimeSama stream pages.
     *
     * @param streamPage The AnimeSama URL to scrape from.
     * Example:
     * ```
     * https://anime-sama.eu/catalogue/anime-name/saison0/vostfr/
     * ```
     * @return A map containing pairs of sources and their corresponding lists of stream links,
     *         or an empty list if no match is found.
     */

    private suspend fun retreiveSrcs(streamPage: String): Map<String, List<String>> {

        val request = app.get(streamPage)

        if (request.isSuccessful) {

            val doc = request.document
            val epiKey = doc.selectFirst("#sousBlocMiddle script").toString()
            val re = Regex("""<script[^>]*src=['"]([^'"]*episodes\.js\?filever=\d+)['"][^>]*>""")
            val episodeKey = re.find(epiKey)?.groupValues?.get(1)
            val rawLinks = app.get("$streamPage/$episodeKey").text
            val reURL = """['"]https?://[^\s'"]+['"]""".toRegex()
            val urls = reURL.findAll(rawLinks)
                .map { it.value.trim('\'', '"') }
                .toList()

            return urls.groupBy { url ->
                when {
                    // here I listed the providers I crossed in AS.

                    url.contains("sibnet.ru") -> "Sibnet"
                    url.contains("vidmoly.to") -> "Vidmoly"
                    url.contains("oneupload.to") -> "Oneupload"
                    url.contains("sendvid.com") -> "Sendvid"
                    url.contains("vk.com") -> "Vk"


                    else -> "Other"
                }
            }

        }

        return mapOf()

    }
}


