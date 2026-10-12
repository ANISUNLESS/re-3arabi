package com.witanime

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val cfKiller = CloudflareKiller()
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36"

    companion object {
        @Volatile var isWebViewOpen = false
        @Volatile var lastWebViewOpenTime = 0L
    }

    private val rateLimiter = AdvancedRateLimiter()
    private val sessionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Session>>()
    private val linkCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ExtractorLink>>>()
    private val failedServers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ==================== ADVANCED RATE LIMITER ====================
    private class AdvancedRateLimiter {
        private val lock = Mutex()
        @Volatile private var nextRequestTime = 0L
        @Volatile private var cooldownExpiry = 0L
        @Volatile private var backoffLevel = 0
        @Volatile private var requestsInWindow = 0
        @Volatile private var windowStart = 0L

        suspend fun acquire() {
            lock.withLock {
                val now = System.currentTimeMillis()
                val requestTime = maxOf(now, nextRequestTime, cooldownExpiry)
                val dynamicGap = 1100L + (400L * minOf(backoffLevel, 6))
                nextRequestTime = requestTime + dynamicGap
                if (requestTime > now) delay(requestTime - now)
                requestsInWindow++
            }
        }

        fun recordThrottle(ms: Long) {
            backoffLevel = minOf(backoffLevel + 1, 8)
            cooldownExpiry = maxOf(cooldownExpiry, System.currentTimeMillis() + maxOf(ms, 3500L))
        }

        suspend fun awaitCooldown() {
            val remaining = cooldownExpiry - System.currentTimeMillis()
            if (remaining > 0L) {
                delay(remaining.coerceAtMost(60000L))
                cooldownExpiry = 0L
                backoffLevel = 0
                requestsInWindow = 0
            }
        }

        fun reset() {
            backoffLevel = 0
            cooldownExpiry = 0L
            nextRequestTime = 0L
            requestsInWindow = 0
        }

        fun isRateLimited(code: Int, text: String): Boolean =
            code == 429 || code == 503 || (text.length < 12000 && 
            (text.contains("طلبات", ignoreCase = true) || 
             text.contains("rate limit", ignoreCase = true) ||
             text.contains("too many", ignoreCase = true)))
    }

    private data class Session(
        val html: String,
        val document: org.jsoup.nodes.Document,
        val csrf: String,
        val cookies: MutableMap<String, String>
    ) {
        val xsrf: String?
            get() = cookies["XSRF-TOKEN"]?.let {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }
    }

    private data class SourceEntry(
        val quality: String,
        val token: String?,
        val direct: String?,
        val label: String?,
        val version: String?,
        val isDownload: Boolean = false
    )

    private data class LinkResult(
        val code: Int,
        val body: String
    )

    // ==================== QUALITY PARSING ====================
    private fun parseQuality(label: String?): Int {
        val norm = label?.trim()?.uppercase().orEmpty()
        return when {
            norm.isBlank() -> Qualities.Unknown.value
            norm == "4K" || norm == "UHD" || norm.contains("2160") -> 2160
            norm == "FHD" || norm.contains("1080") -> 1080
            norm == "HD" || norm.contains("720") -> 720
            norm == "SD" || norm.contains("480") -> 480
            else -> Regex("""\d{3,4}""").find(norm)?.value?.toIntOrNull()
                ?.takeIf { it in 144..4320 } ?: Qualities.Unknown.value
        }
    }

    private fun qualityRank(q: Int): Int = when {
        q >= 1080 -> 0
        q >= 720 -> 1
        q >= 480 -> 2
        else -> 3
    }

    private fun extractQualityFromLink(url: String): Int? {
        Regex("""(?<![0-9A-Za-z])(2160|1440|1080|720|480|360|240)p?(?![0-9A-Za-z])""")
            .find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            url.uppercase().contains("2160") || url.uppercase().contains("4K") -> 2160
            url.uppercase().contains("1080") || url.uppercase().contains("FHD") -> 1080
            url.uppercase().contains("720") || url.uppercase().contains("HD") -> 720
            url.uppercase().contains("480") || url.uppercase().contains("SD") -> 480
            else -> null
        }
    }

    // ==================== URL UTILITIES ====================
    private fun normalizeUrl(raw: String): String? {
        val v = raw.trim().trim('"', '\'')
        if (v.isBlank() || v.startsWith("#") || v.startsWith("javascript:", true) || v.startsWith("data:", true)) return null
        return when {
            v.startsWith("//") -> "https:$v"
            v.startsWith("http", true) -> v
            v.startsWith("/") -> mainUrl + v
            else -> null
        }
    }

    private fun hostOf(url: String): String? = try {
        java.net.URI(url).host?.lowercase()
    } catch (_: Exception) { null }

    private fun unescape(s: String): String = s
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    private fun imgUrl(img: Element?): String? {
        if (img == null) return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-lazy-src") }
        return src.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private fun cardToResponse(card: Element, includeEpisode: Boolean): SearchResponse? {
        val href = card.attr("href").ifBlank { return null }
        val img = card.select("img").firstOrNull { it.attr("alt").isNotBlank() } ?: card.selectFirst("img")
        val title = card.selectFirst("h3")?.text()?.trim()
            ?: card.selectFirst("span[dir=ltr]")?.text()?.trim()
            ?: img?.attr("alt")
        if (title.isNullOrBlank()) return null

        val poster = imgUrl(img)
        val badge = if (includeEpisode && href.contains("/watch/")) {
            card.selectFirst("div.truncate")?.text()?.trim()
        } else null
        val display = if (!badge.isNullOrBlank()) "$title - $badge" else title
        val type = if (href.contains("/movie/")) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(display, fixUrl(href), type) {
            this.posterUrl = poster
        }
    }

    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "$mainUrl/movies" to "كل الأفلام",
        "$mainUrl/seasonal" to "كل أنميات الموسم",
        "$mainUrl/browse" to "قائمة الأنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "home") return getHome(page)
        val doc = app.get("${request.data}?page=$page", interceptor = cfKiller).document
        val items = doc.select("a[href*=/anime/], a[href*=/movie/]")
            .mapNotNull { cardToResponse(it, false) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private suspend fun getHome(page: Int): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), false)
        val doc = app.get(mainUrl, interceptor = cfKiller).document
        val sections = ArrayList<HomePageList>()

        val hero = doc.select("[data-hero-slide]").mapNotNull { slide ->
            slide.selectFirst("h2 a")?.let { a ->
                val href = a.attr("href").ifBlank { return@let null }
                val text = a.text().trim().ifBlank { return@let null }
                newAnimeSearchResponse(text, fixUrl(href), TvType.Anime) {
                    this.posterUrl = imgUrl(slide.selectFirst("img"))
                }
            }
        }.distinctBy { it.url }

        if (hero.isNotEmpty()) sections.add(HomePageList("المميز", hero))

        for (h2 in doc.select("h2")) {
            val title = h2.text().trim().ifBlank { continue }
            var parent = h2.parent()
            while (parent != null) {
                val cards = parent.select("a[href*=/watch/], a[href*=/anime/], a[href*=/movie/]")
                    .mapNotNull { cardToResponse(it, true) }
                    .distinctBy { it.url }
                if (cards.isNotEmpty()) {
                    sections.add(HomePageList(title, cards))
                    break
                }
                parent = parent.parent()
            }
        }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return try {
            val doc = app.get("$mainUrl/search?q=$q", headers = mapOf("User-Agent" to userAgent), interceptor = cfKiller).document
            doc.select("a[href*=/anime/], a[href*=/movie/]")
                .mapNotNull { cardToResponse(it, false) }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var pageUrl = url
        var response = app.get(pageUrl, interceptor = cfKiller)

        if (response.code == 404 && pageUrl.contains("/anime/")) {
            val alt = pageUrl.replace("/anime/", "/movie/")
            val altResp = try { app.get(alt, interceptor = cfKiller) } catch (_: Exception) { null }
            if (altResp != null && altResp.code in 200..299) {
                pageUrl = alt
                response = altResp
            }
        }

        return loadPage(pageUrl, response.document)
    }

    private suspend fun loadPage(url: String, document: org.jsoup.nodes.Document): LoadResponse {
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val infoBox = document.selectFirst("h1")?.parent()
        val poster = imgUrl(document.selectFirst("div.shrink-0 img"))
        val description = infoBox?.selectFirst("p.leading-relaxed")?.text()?.trim()
        val genres = document.select("span.rounded-full")
            .filter { it.className().contains("bg-white/10") }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val statusText = infoBox?.selectFirst("span.capitalize")?.text().orEmpty()
        val typeText = infoBox?.selectFirst("span.font-bold")?.text().orEmpty()
        val isCompleted = statusText.contains("مكتمل") || statusText.contains("منتهي")
        val tvType = if (typeText.contains("movie", ignoreCase = true) || typeText.contains("فيلم")) {
            TvType.AnimeMovie
        } else TvType.Anime

        val year = document.select("span.text-neutral-400")
            .firstOrNull { it.text().trim() == "السنة" }
            ?.parent()?.selectFirst("p")?.text()?.trim()?.toIntOrNull()

        val slug = url.trimEnd('/').substringAfterLast("/")
        val isMovie = tvType == TvType.AnimeMovie || url.contains("/movie", ignoreCase = true)

        fun getEpisodes(links: List<Element>): List<Episode> = links.mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val num = href.trimEnd('/').substringAfterLast("/").toIntOrNull()
            val label = a.selectFirst("span.text-sm")?.text()?.trim()

            newEpisode(fixUrl(href)) {
                this.name = label?.takeIf { it.isNotBlank() } ?: num?.let { "الحلقة $it" } ?: "الحلقة"
                this.episode = num
                this.posterUrl = imgUrl(a.selectFirst("img"))
            }
        }

        var episodes = getEpisodes(
            document.select("div[data-episode-list]").firstOrNull()?.select("a[href*=/watch/]")?.toList().orEmpty()
        )

        if (episodes.isEmpty() && isMovie) {
            val probes = listOf("$mainUrl/watch/$slug/1", "$mainUrl/watch/$slug", "$mainUrl/movie/$slug/watch")
            for (p in probes) {
                val ok = try {
                    val r = app.get(p, interceptor = cfKiller)
                    r.code in 200..299 && (r.text.contains("sourcesUrl") || r.text.contains("csrf-token"))
                } catch (_: Exception) { false }
                if (ok) {
                    episodes = listOf(newEpisode(p) { this.name = title; this.episode = 1 })
                    break
                }
            }
        }

        if (episodes.isEmpty() && isMovie) {
            episodes = listOf(newEpisode(url) { this.name = title; this.episode = 1 })
        }

        episodes = episodes.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }

        if (isMovie && episodes.size <= 1 && episodes.isNotEmpty()) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.year = year
            this.showStatus = if (isCompleted) ShowStatus.Completed else ShowStatus.Ongoing
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ==================== SESSION & SOURCES ====================
    private suspend fun openSession(url: String, forceRefresh: Boolean = false): Session {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            sessionCache[url]?.let { (time, sess) ->
                if (now - time < 90_000L) return sess
            }
        }

        rateLimiter.awaitCooldown()
        repeat(4) { attempt ->
            rateLimiter.acquire()
            val page = app.get(url, interceptor = cfKiller)

            when {
                page.code == 429 -> {
                    rateLimiter.recordThrottle(2000L * (attempt + 1))
                    return@repeat
                }
                page.code == 403 -> throw ErrorLoadingException("403")
                page.code in 500..599 -> {
                    rateLimiter.recordThrottle(4000L)
                    return@repeat
                }
                page.code in 200..299 -> {
                    val html = page.text
                    val csrf = page.document.selectFirst("meta[name=csrf-token]")?.attr("content")
                        ?.takeIf { it.isNotBlank() }
                        ?: Regex("""csrf[-_]?token["']?\s*[:=,]\s*["']([A-Za-z0-9]{20,})""", RegexOption.IGNORE_CASE)
                            .find(html)?.groupValues?.get(1).orEmpty()

                    val session = Session(html, page.document, csrf, page.cookies.toMutableMap())
                    sessionCache[url] = System.currentTimeMillis() to session
                    return session
                }
            }
        }

        throw ErrorLoadingException("429")
    }

    private suspend fun fetchSources(url: String, session: Session): LinkResult {
        val candidates = listOfNotNull(
            Regex("""sourcesUrl:\s*'([^']+)'""").find(session.html)?.groupValues?.get(1)?.replace("\\/", "/"),
            Regex("""(?i)\bsourcesUrl\s*[:=]\s*["']([^"']+)""").find(session.html)?.groupValues?.get(1)?.replace("\\/", "/"),
            java.net.URI(url).path.trimEnd('/') + "/sources"
        ).map { if (it.startsWith("http")) it else mainUrl + it }.distinct()

        val csrf = session.csrf.ifBlank { session.xsrf.orEmpty() }

        for ((idx, sourceUrl) in candidates.withIndex()) {
            val headers = linkedMapOf(
                "Accept" to "application/json",
                "X-CSRF-TOKEN" to csrf,
                "Origin" to mainUrl,
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest"
            )
            session.xsrf?.let { headers["X-XSRF-TOKEN"] = it }

            try {
                rateLimiter.acquire()
                val res = app.post(sourceUrl, headers = headers, cookies = session.cookies, interceptor = cfKiller)

                if (res.code == 429) {
                    rateLimiter.recordThrottle(2000L * (idx + 1))
                    continue
                }

                if (res.code in 200..299) {
                    try {
                        JSONObject(res.text)
                        return LinkResult(res.code, res.text)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        return LinkResult(-1, "")
    }

    private fun collectEntries(node: Any?, qualityKey: String, out: MutableList<SourceEntry>) {
        when (node) {
            is JSONObject -> {
                val token = node.optString("token").takeIf { it.matches(Regex("^[a-f0-9]{64}$")) }
                val direct = listOf("url", "link", "href", "src", "file")
                    .map { node.optString(it) }
                    .firstOrNull { it.startsWith("http") || it.startsWith("//") }

                if (token != null || direct != null) {
                    out.add(SourceEntry(
                        quality = node.optString("quality").ifBlank { qualityKey },
                        token = token,
                        direct = direct,
                        label = node.optString("label").takeIf { it.isNotBlank() } ?: node.optString("name").takeIf { it.isNotBlank() },
                        version = node.optString("version").takeIf { it.isNotBlank() }
                    ))
                    return
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        val nextKey = if (k.uppercase() in setOf("SD", "HD", "FHD", "UHD", "4K")) k else qualityKey
                        collectEntries(v, nextKey, out)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectEntries(node.opt(i), qualityKey, out)
        }
    }

    // ==================== RESOLVERS ====================
    private suspend fun resolveMedia(html: String, referer: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val regex = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4|mkv|webm)(?:\?[^\s"'<>]*)?""")
        val seen = HashSet<String>()
        var found = false

        for (match in regex.findAll(html)) {
            val media = match.value
            if (!seen.add(media) || media.contains("a-ads.com")) continue

            val q = extractQualityFromLink(media) ?: quality
            val isM3u8 = media.contains(".m3u8")

            if (isM3u8) {
                try {
                    M3u8Helper.generateM3u8(name, media, referer).forEach(callback)
                    found = true
                    continue
                } catch (_: Exception) {}
            }

            callback(newExtractorLink(source = name, name = name, url = media) {
                this.referer = referer
                this.quality = q
                this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            })
            found = true
        }

        return found
    }

    private suspend fun resolveMediaFire(link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(link, headers = mapOf("User-Agent" to userAgent)).text
        val direct = Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)""")
            .find(html)?.groupValues?.get(1) ?: return false

        callback(newExtractorLink(source = name, name = name, url = direct) {
            this.referer = "https://www.mediafire.com/"
            this.quality = extractQualityFromLink(direct) ?: quality
            this.type = ExtractorLinkType.VIDEO
        })
        return true
    }

    private suspend fun resolveGofile(link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""gofile\.io/(?:d|embed|download)/([A-Za-z0-9]+)""").find(link)?.groupValues?.get(1) ?: return false
        val acc = app.post("https://api.gofile.io/accounts", json = emptyMap<String, String>(), headers = mapOf("User-Agent" to userAgent))
        val token = try {
            JSONObject(acc.text).optJSONObject("data")?.optString("token")
        } catch (_: Exception) { null }?.takeIf { it.isNotBlank() } ?: return false

        val res = app.get("https://api.gofile.io/contents/$id?pageSize=500", headers = mapOf("Authorization" to "Bearer $token", "User-Agent" to userAgent))
        val children = try {
            JSONObject(res.text).optJSONObject("data")?.optJSONObject("children")
        } catch (_: Exception) { null } ?: return false

        val keys = children.keys()
        while (keys.hasNext()) {
            val file = children.optJSONObject(keys.next()) ?: continue
            if (file.optString("type") != "file") continue
            val dl = file.optString("link").takeIf { it.startsWith("http") } ?: continue
            if (!file.optString("mimetype").startsWith("video")) continue

            callback(newExtractorLink(source = name, name = name, url = dl) {
                this.referer = "https://gofile.io/"
                this.quality = extractQualityFromLink(dl) ?: quality
                this.type = ExtractorLinkType.VIDEO
                this.headers = mapOf("Cookie" to "accountToken=$token")
            })
            return true
        }
        return false
    }

    private suspend fun resolveFourShared(link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(link, headers = mapOf("User-Agent" to userAgent)).text
        return resolveMedia(html, "https://www.4shared.com/", name, quality, callback)
    }

    private suspend fun resolveOkRu(link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""(?:videoembed|video)/(\d+)""").find(link)?.groupValues?.get(1) ?: return false
        val html = app.get("https://ok.ru/videoembed/$id", headers = mapOf("User-Agent" to userAgent, "Referer" to "https://ok.ru/")).text
        return resolveMedia(html, "https://ok.ru/", name, quality, callback)
    }

    private suspend fun resolveWorkupload(link: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""workupload\.com/(?:file|start)/([A-Za-z0-9]+)""").find(link)?.groupValues?.get(1) ?: return false
        val first = app.get("https://workupload.com/file/$id", headers = mapOf("User-Agent" to userAgent))
        val api = app.get("https://workupload.com/api/file/getDownloadServer/$id", headers = mapOf("User-Agent" to userAgent, "Referer" to "https://workupload.com/file/$id"), cookies = first.cookies).text
        val direct = try {
            JSONObject(api).optJSONObject("data")?.optString("url")
        } catch (_: Exception) { null }?.takeIf { it?.startsWith("http") == true } ?: return false

        callback(newExtractorLink(source = name, name = name, url = direct) {
            this.referer = "https://workupload.com/"
            this.quality = quality
            this.type = ExtractorLinkType.VIDEO
        })
        return true
    }

    private suspend fun resolveExternal(link: String, referer: String, name: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val host = hostOf(link) ?: return false

        if (host.contains("mega.nz") || host.contains("mega.io")) {
            callback(newExtractorLink(source = name, name = name, url = "mega-webview://$link") {
                this.quality = quality
                this.type = ExtractorLinkType.VIDEO
            })
            return true
        }

        return try {
            when {
                host.contains("mediafire.com") -> resolveMediaFire(link, name, quality, callback)
                host.contains("gofile.io") -> resolveGofile(link, name, quality, callback)
                host.contains("4shared.com") -> resolveFourShared(link, name, quality, callback)
                host.contains("ok.ru") -> resolveOkRu(link, name, quality, callback)
                host.contains("workupload.com") -> resolveWorkupload(link, name, quality, callback)
                else -> false
            }
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    private suspend fun scanLinks(url: String, referer: String, name: String, quality: Int, cookies: Map<String, String>, depth: Int, callback: (ExtractorLink) -> Unit): Boolean {
        if (depth > 2) return false

        rateLimiter.acquire()
        val res = app.get(url, referer = referer, cookies = cookies, interceptor = cfKiller)

        if (rateLimiter.isRateLimited(res.code, res.text)) {
            rateLimiter.recordThrottle(1500L)
            return false
        }

        val html = unescape(res.text)
        if (resolveMedia(html, url, name, quality, callback)) return true

        val newHost = hostOf(res.url)
        if (newHost != hostOf(url)) {
            if (resolveExternal(res.url, referer, name, quality, callback)) return true
        }

        val links = LinkedHashSet<String>()
        res.document.select("iframe[src], iframe[data-src]").forEach {
            (it.attr("src").ifBlank { it.attr("data-src") }).takeIf { s -> s.isNotBlank() }?.let { s -> links.add(s) }
        }

        for (link in links) {
            normalizeUrl(link)?.let {
                if (it != url && !it.contains("a-ads.com")) {
                    if (scanLinks(it, url, name, quality, cookies, depth + 1, callback)) return true
                }
            }
        }

        return false
    }

    // ==================== MAIN LOADER ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        linkCache[data]?.let { (time, list) ->
            if (System.currentTimeMillis() - time < 5 * 60_000L && list.isNotEmpty()) {
                list.forEach(callback)
                return true
            }
        }

        rateLimiter.awaitCooldown()
        rateLimiter.reset()

        val session = try {
            openSession(data)
        } catch (e: Exception) {
            logError(e)
            return false
        }

        val sources = fetchSources(data, session)
        if (sources.code !in 200..299) return false

        val entries = mutableListOf<SourceEntry>()
        try {
            val root = JSONObject(sources.body)
            val players = root.optJSONObject("players")
            if (players != null) {
                collectEntries(players, "", entries)
            } else {
                collectEntries(root, "", entries)
            }
        } catch (_: Exception) {}

        if (entries.isEmpty()) return false

        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val queue = java.util.concurrent.ConcurrentLinkedQueue<ExtractorLink>()
        val semaphore = Semaphore(2)

        coroutineScope {
            val jobs = entries
                .sortedWith(compareBy<SourceEntry>(
                    { qualityRank(parseQuality(it.quality)) },
                    { if (it.isDownload) 1 else 0 }
                ))
                .distinctBy { "${it.token}|${it.direct}|${it.label}" }
                .map { entry ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val q = parseQuality(entry.quality).takeIf { it != Qualities.Unknown.value } ?: 720
                            val label = entry.label?.replaceFirstChar { it.uppercase() } ?: "Server"

                            if (label in failedServers) return@withPermit

                            try {
                                if (entry.token != null) {
                                    val targetUrl = "$mainUrl/watch/stream-gate/${entry.token}"
                                    val ok = scanLinks(targetUrl, data, label, q, session.cookies, 0) { link ->
                                        if (seen.add(link.url)) queue.add(link)
                                    }
                                    if (!ok) failedServers.add(label)
                                } else if (entry.direct != null) {
                                    normalizeUrl(entry.direct)?.let { url ->
                                        val ok = resolveExternal(url, data, label, q) { link ->
                                            if (seen.add(link.url)) queue.add(link)
                                        }
                                        if (!ok) failedServers.add(label)
                                    }
                                }
                            } catch (e: Exception) {
                                logError(e)
                                failedServers.add(label)
                            }
                        }
                    }
                }

            jobs.awaitAll()
        }

        val sorted = queue.toList()
            .sortedWith(compareBy<ExtractorLink>(
                { qualityRank(it.quality) },
                { -it.quality },
                { it.name }
            ))

        sorted.forEach(callback)

        if (sorted.isNotEmpty()) {
            linkCache[data] = System.currentTimeMillis() to sorted
        }

        return sorted.isNotEmpty()
    }
}
