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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"

    private val cfKiller = com.lagradost.cloudstream3.network.CloudflareKiller()

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    companion object {
        @Volatile var isWebViewOpen = false
        @Volatile var lastWebViewOpenTime = 0L
    }

    // ======================= Advanced Player WebView Interceptor =======================
    object PlayerAccess {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isMonitoring = false
        private var lastHookedPlayer: Any? = null
        private var activeDialog: Dialog? = null
        private var isLoopStarted = false

        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                hookPlayerListener()
                handler.postDelayed(this, 1000L)
            }
        }

        fun startMonitoring() {
            if (isLoopStarted) return
            isLoopStarted = true
            isMonitoring = true
            handler.post(monitorRunnable)
        }

        private fun findFragmentRecursive(
            fragment: androidx.fragment.app.Fragment,
            packageName: String
        ): androidx.fragment.app.Fragment? {
            if (fragment.javaClass.name.startsWith(packageName)) return fragment
            try {
                for (child in fragment.childFragmentManager.fragments) {
                    if (child != null) {
                        findFragmentRecursive(child, packageName)?.let { return it }
                    }
                }
            } catch (e: Exception) {
            }
            return null
        }

        fun getActiveActivity(): Activity? {
            return try {
                val atClass = Class.forName("android.app.ActivityThread")
                val at = atClass.getMethod("currentActivityThread").invoke(null)
                val field = atClass.getDeclaredField("mActivities")
                field.isAccessible = true
                val activities = field.get(at) as Map<*, *>
                var result: Activity? = null
                for (rec in activities.values) {
                    if (rec == null) continue
                    val f = rec.javaClass.getDeclaredField("activity")
                    f.isAccessible = true
                    val act = f.get(rec) as? Activity
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        result = act
                        break
                    }
                }
                result
            } catch (e: Exception) {
                null
            }
        }

        fun getPlayerFragment(): Any? {
            val activity = getActiveActivity() ?: return null
            return try {
                val fragments = (activity as? androidx.fragment.app.FragmentActivity)
                    ?.supportFragmentManager?.fragments ?: return null
                for (f in fragments) {
                    if (f != null) {
                        findFragmentRecursive(f, "com.lagradost.cloudstream3.ui.player")?.let { return it }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }

        fun currentPlayer(): Any? {
            val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment ?: return null
            return try {
                val f = fragment.javaClass.getDeclaredField("player")
                f.isAccessible = true
                f.get(fragment)
            } catch (e: Exception) {
                try {
                    fragment.javaClass.getMethod("getPlayer").invoke(fragment)
                } catch (ex: Exception) {
                    null
                }
            }
        }

        fun getAppContext(): Context? = try {
            val c = Class.forName("android.app.ActivityThread")
            c.getMethod("currentApplication").invoke(null) as? Context
        } catch (e: Exception) {
            null
        }

        fun pausePlayer() {
            val player = currentPlayer() ?: return
            try {
                val m = player.javaClass.methods.firstOrNull { it.name == "handleEvent" } ?: return
                val types = m.parameterTypes
                if (types.isEmpty()) return
                val pause = types[0].enumConstants?.firstOrNull {
                    it.toString().contains("Pause", ignoreCase = true)
                }
                if (types.size == 2) {
                    val src = types[1].enumConstants?.firstOrNull {
                        it.toString().contains("Sync", ignoreCase = true)
                    } ?: types[1].enumConstants?.firstOrNull {
                        it.toString().contains("UI", ignoreCase = true)
                    }
                    m.invoke(player, pause, src)
                } else {
                    m.invoke(player, pause)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun getRealExoPlayer(player: Any): Any? {
            val rootName = player.javaClass.name
            if (rootName.contains("Player", true) && !rootName.contains("Cache", true)) {
                try {
                    if (player.javaClass.methods.any { it.name == "addListener" }) return player
                } catch (e: Exception) {
                }
            }
            try {
                for (f in player.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(player) ?: continue
                    val cn = v.javaClass.name
                    if (cn.contains("Player", true) && !cn.contains("Cache", true)) {
                        try {
                            if (v.javaClass.methods.any { it.name == "addListener" }) return v
                        } catch (ex: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
            }
            return null
        }

        private fun hookPlayerListener() {
            val raw = currentPlayer() ?: return
            val player = getRealExoPlayer(raw) ?: return
            if (player === lastHookedPlayer) return
            lastHookedPlayer = player
            try {
                val listenerClass = try {
                    Class.forName("androidx.media3.common.Player\$Listener")
                } catch (e: Exception) {
                    Class.forName("com.google.android.exoplayer2.Player\$Listener")
                }
                val addListener = player.javaClass.getMethod("addListener", listenerClass)
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                    object : java.lang.reflect.InvocationHandler {
                        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                            when (method.name) {
                                "equals" -> return proxy === (args?.get(0))
                                "hashCode" -> return System.identityHashCode(proxy)
                                "toString" -> return "ExoPlayerProxyListener"
                            }
                            handler.post { checkMegaPlayback(player) }
                            val rt = method.returnType
                            return when (rt) {
                                Boolean::class.javaPrimitiveType -> false
                                Byte::class.javaPrimitiveType -> 0.toByte()
                                Short::class.javaPrimitiveType -> 0.toShort()
                                Int::class.javaPrimitiveType -> 0
                                Long::class.javaPrimitiveType -> 0L
                                Float::class.javaPrimitiveType -> 0f
                                Double::class.javaPrimitiveType -> 0.0
                                Char::class.javaPrimitiveType -> '\u0000'
                                else -> null
                            }
                        }
                    }
                )
                addListener.invoke(player, proxy)
            } catch (e: Exception) {
                android.util.Log.e("WitAnimeScanner", "hook failed", e)
            }
        }

        private fun findUrlInObject(obj: Any, depth: Int = 0): String? {
            if (depth > 3) return null
            try {
                for (f in obj.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (v is String && v.contains("mega-webview://")) return v
                    if (v is Uri && v.toString().contains("mega-webview://")) return v.toString()
                    val pkg = v.javaClass.`package`?.name ?: ""
                    if (pkg.contains("lagradost") || pkg.contains("media3") || pkg.contains("exoplayer") || pkg.contains("google")) {
                        findUrlInObject(v, depth + 1)?.let { return it }
                    }
                }
            } catch (e: Exception) {
            }
            return null
        }

        private fun getPlayingUrl(player: Any): String? = try {
            val item = player.javaClass.getMethod("getCurrentMediaItem").invoke(player)
            val lc = item?.javaClass?.getDeclaredField("localConfiguration")?.also { it.isAccessible = true }?.get(item)
            val uri = lc?.javaClass?.getDeclaredField("uri")?.also { it.isAccessible = true }?.get(lc) as? Uri
            uri?.toString() ?: findUrlInObject(player)
        } catch (e: Exception) {
            findUrlInObject(player)
        }

        private fun checkMegaPlayback(player: Any) {
            val now = System.currentTimeMillis()
            synchronized(WitAnime::class.java) {
                if (WitAnime.isWebViewOpen || (now - WitAnime.lastWebViewOpenTime) < 10000L || activeDialog?.isShowing == true) return
                val playing = getPlayingUrl(player) ?: return
                if (playing.contains("mega-webview://")) {
                    WitAnime.isWebViewOpen = true
                    WitAnime.lastWebViewOpenTime = now
                    pausePlayer()
                    val real = playing.substringAfter("mega-webview://")
                    CoroutineScope(Dispatchers.Main).launch { openMegaPlayer(real) }
                }
            }
        }

        private fun megaEmbed(u: String): String {
            val v = try { java.net.URLDecoder.decode(u, "UTF-8") } catch (e: Exception) { u }
            Regex("""(?i)mega\.(?:nz|io)/(?:file|embed)/([A-Za-z0-9_-]+)#([A-Za-z0-9_-]+)""").find(v)?.let {
                return "https://mega.nz/embed/${it.groupValues[1]}#${it.groupValues[2]}"
            }
            Regex("""(?i)mega\.(?:nz|io)/#!([A-Za-z0-9_-]+)!([A-Za-z0-9_-]+)""").find(v)?.let {
                return "https://mega.nz/embed/${it.groupValues[1]}#${it.groupValues[2]}"
            }
            return v
        }

        private suspend fun openMegaPlayer(megaUrl: String) {
            withContext(Dispatchers.Main) {
                if (activeDialog?.isShowing == true) return@withContext
                val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment
                val activity = fragment?.activity ?: getActiveActivity()

                if (activity != null) {
                    try {
                        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                        activeDialog = dialog
                        var webViewRef: WebView? = null
                        val originalOrientation = activity.requestedOrientation
                        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        dialog.setOnDismissListener {
                            try {
                                webViewRef?.let { w ->
                                    w.stopLoading()
                                    w.loadUrl("about:blank")
                                    w.clearHistory()
                                    w.removeAllViews()
                                    w.destroy()
                                }
                            } catch (e: Exception) {
                            }
                            webViewRef = null
                            activity.requestedOrientation = originalOrientation
                            activeDialog = null
                            synchronized(WitAnime::class.java) {
                                WitAnime.isWebViewOpen = false
                                WitAnime.lastWebViewOpenTime = System.currentTimeMillis()
                            }
                            lastHookedPlayer = null
                        }

                        val frame = FrameLayout(activity)
                        frame.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        val webView = WebView(activity)
                        webViewRef = webView
                        webView.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.allowFileAccess = false
                        webView.settings.allowContentAccess = false
                        webView.settings.mediaPlaybackRequiresUserGesture = false
                        webView.settings.userAgentString = userAgent
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(
                                    "setTimeout(function(){var v=document.querySelector('video');if(v){v.play();}},2500);",
                                    null
                                )
                            }
                        }
                        webView.webChromeClient = WebChromeClient()
                        webView.loadUrl(megaEmbed(megaUrl))
                        frame.addView(webView)

                        val density = activity.resources.displayMetrics.density
                        val close = android.widget.Button(activity).apply {
                            text = "✕"
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#99000000"))
                            }
                            setPadding(0, 0, 0, 0)
                            setOnClickListener { dialog.dismiss() }
                        }
                        val size = (32 * density).toInt()
                        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                            topMargin = (8 * density).toInt()
                            marginEnd = (8 * density).toInt()
                        }
                        frame.addView(close, lp)
                        dialog.setContentView(frame)
                        dialog.show()
                    } catch (e: Exception) {
                        synchronized(WitAnime::class.java) { WitAnime.isWebViewOpen = false }
                        activeDialog = null
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(megaUrl)))
                        } catch (ex: Exception) {
                        }
                    }
                } else {
                    synchronized(WitAnime::class.java) { WitAnime.isWebViewOpen = false }
                    activeDialog = null
                    try {
                        getAppContext()?.let {
                            it.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(megaUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    init {
        PlayerAccess.startMonitoring()
    }

    // ============================= Core Helpers =============================
    private val DEBUG = false
    private val episodeUrlRegex = Regex("""/watch/([^/?#]+)/([^/?#]+)""")
    private val tokenRegex = Regex("^[a-f0-9]{64}$")
    
    private val fileHosts = listOf(
        "mediafire.com", "gofile.io", "4shared.com", "mega.nz", "mega.io",
        "drive.google.com", "pixeldrain", "krakenfiles", "streamtape", "dood",
        "voe.sx", "mixdrop", "filemoon", "streamwish", "vidhide", "uqload",
        "ok.ru", "sendvid", "userdrive", "lulustream", "upstream", "vidmoly",
        "yourupload", "mp4upload", "dailymotion", "terabox", "send.cm", "workupload"
    )

    private fun hostOf(u: String): String? = try {
        java.net.URI(u).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    private fun isFileHost(u: String): Boolean {
        val h = hostOf(u) ?: return false
        return fileHosts.any { h.contains(it) }
    }

    private fun normalizeUrl(raw: String, base: String = mainUrl): String? {
        val v = unescape(raw.trim()).trim('"', '\'')
        if (v.isBlank() || v.startsWith("#") || v.startsWith("javascript:", true) || v.startsWith("data:", true)) return null
        return when {
            v.startsWith("//") -> "https:$v"
            v.startsWith("http://", true) || v.startsWith("https://", true) -> v
            v.startsWith("/") -> mainUrl + v
            v.startsWith("./") || v.startsWith("../") -> try { java.net.URI(base).resolve(v).toString() } catch (e: Exception) { null }
            else -> null
        }
    }

    private fun unescape(s: String): String = s
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u002F", "/")
        .replace("&amp;", "&")

    private fun imgUrl(img: Element?): String? {
        if (img == null) return null
        val s = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-lazy-src") }
        return s.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private fun qualityValue(label: String?): Int {
        val l = label?.trim()?.uppercase().orEmpty()
        return when {
            l.isBlank() -> Qualities.Unknown.value
            l == "4K" || l == "UHD" -> 2160
            l == "FHD" -> 1080
            l == "HD" -> 720
            l == "SD" -> 480
            else -> Regex("""\d{3,4}""").find(l)?.value?.toIntOrNull()
                ?.takeIf { it in 144..4320 } ?: Qualities.Unknown.value
        }
    }

    private fun qualityLabel(q: Int): String = when {
        q >= 2160 -> "4K"
        q >= 1080 -> "FHD"
        q >= 720 -> "HD"
        q in 1..719 -> "SD"
        else -> "?"
    }

    private fun qualityRank(q: Int): Int = when {
        q >= 2160 -> 0
        q >= 1440 -> 1
        q >= 1080 -> 2
        q >= 720 -> 3
        q in 1..719 -> 4
        else -> 5
    }

    private val keyedQualityRegex =
        Regex("""(?i)(?:label|size|res|resolution|quality|height|title|name)["']?\s*[:=]\s*["']?\s*(\d{3,4})""")
    private val keyedWordQualityRegex =
        Regex("""(?i)(?:label|quality|title|name)["']?\s*[:=]\s*["']?\s*(fhd|uhd|4k|hd|sd)(?![a-z])""")

    private fun qualityFromUrl(u: String): Int? {
        Regex("""(?<![0-9A-Za-z])(2160|1440|1080|720|576|480|360|240)p?(?![0-9A-Za-z])""").find(u)?.let { return it.groupValues[1].toInt() }
        val up = u.uppercase()
        return when {
            Regex("""(?<![A-Z])(4K|UHD)(?![A-Z])""").containsMatchIn(up) -> 2160
            Regex("""(?<![A-Z])FHD(?![A-Z])""").containsMatchIn(up) -> 1080
            Regex("""(?<![A-Z])HD(?![A-Z])""").containsMatchIn(up) -> 720
            Regex("""(?<![A-Z])SD(?![A-Z])""").containsMatchIn(up) -> 480
            else -> null
        }
    }

    private fun qualityFromWindow(w: String): Int? {
        keyedQualityRegex.find(w)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 144..4320 }?.let { return it }
        keyedWordQualityRegex.find(w)?.groupValues?.get(1)?.let { return qualityValue(it) }
        return null
    }

    private fun animeUrlFromAny(href: String): String {
        val m = episodeUrlRegex.find(href)
        return if (m != null) "$mainUrl/anime/${m.groupValues[1]}" else fixUrl(href)
    }

    private fun cardToResponse(a: Element, withEpisode: Boolean): SearchResponse? {
        val href = a.attr("href")
        if (href.isBlank()) return null
        val img = a.select("img").firstOrNull { it.attr("alt").isNotBlank() } ?: a.selectFirst("img")
        val title = (a.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
            ?: a.selectFirst("span[dir=ltr]")?.text()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt"))?.trim()
        if (title.isNullOrBlank()) return null
        val poster = imgUrl(img)
        val badge = if (withEpisode && href.contains("/watch/")) {
            a.selectFirst("div.truncate")?.text()?.trim()
        } else null
        val nm = if (!badge.isNullOrBlank()) "$title - $badge" else title
        val type = if (href.contains("/movie/")) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(nm, animeUrlFromAny(href), type) {
            this.posterUrl = poster
        }
    }

    private fun norm(s: String): String = s
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي').lowercase()

    private fun cardsIn(container: Element): List<SearchResponse> =
        container.select("a[href*=/watch/], a[href*=/anime/], a[href*=/movie/]")
            .filter { a -> a.parents().none { it.tagName() == "nav" || it.tagName() == "footer" } }
            .mapNotNull { cardToResponse(it, withEpisode = true) }
            .distinctBy { it.url }

    private fun containerFor(h2: Element): Element? {
        var c: Element? = h2.parent()
        var i = 0
        while (c != null && i < 6) {
            if (c.selectFirst("a[href*=/watch/], a[href*=/anime/], a[href*=/movie/]") != null) return c
            c = c.parent()
            i++
        }
        return null
    }

    private fun listingCards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("a[href*=/anime/], a[href*=/movie/]")
            .filter { a -> a.parents().none { it.tagName() == "aside" || it.tagName() == "nav" || it.tagName() == "footer" } }
            .mapNotNull { cardToResponse(it, withEpisode = false) }
            .distinctBy { it.url }

    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "$mainUrl/movies" to "كل الأفلام",
        "$mainUrl/seasonal" to "كل أنميات الموسم",
        "$mainUrl/browse" to "قائمة الأنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "home") return getHome(page)
        val doc = app.get("${request.data}?page=$page", interceptor = cfKiller).document
        val items = listingCards(doc)
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private suspend fun getHome(page: Int): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

        val document = app.get(mainUrl, interceptor = cfKiller).document
        var episodes: List<SearchResponse>? = null
        var movies: List<SearchResponse>? = null
        var season: List<SearchResponse>? = null
        val others = ArrayList<HomePageList>()

        val hero = document.select("[data-hero-slide]").mapNotNull { sl ->
            val a = sl.selectFirst("h2 a") ?: return@mapNotNull null
            val href = a.attr("href")
            val t = a.text().trim()
            if (href.isBlank() || t.isBlank()) return@mapNotNull null
            newAnimeSearchResponse(t, fixUrl(href), TvType.Anime) {
                this.posterUrl = imgUrl(sl.selectFirst("img"))
            }
        }.distinctBy { it.url }

        for (h2 in document.select("h2")) {
            if (h2.parents().any { it.attr("data-test") == "hero-carousel" }) continue
            val title = h2.text().trim()
            if (title.isBlank()) continue
            val container = containerFor(h2) ?: continue
            val items = cardsIn(container)
            if (items.isEmpty()) continue
            val t = norm(title)
            when {
                t.contains("احدث") && t.contains("حلقات") && episodes == null -> episodes = items
                t.contains("احدث") && t.contains("افلام") && movies == null -> movies = items
                t.contains("اشهر") && t.contains("الموسم") && season == null -> season = items
                else -> others.add(HomePageList(title, items))
            }
        }
        if (movies == null) {
            movies = try {
                listingCards(app.get("$mainUrl/movies", interceptor = cfKiller).document).take(30)
            } catch (e: Exception) {
                null
            }
        }
        if (season == null) {
            season = try {
                listingCards(app.get("$mainUrl/seasonal", interceptor = cfKiller).document).take(30)
            } catch (e: Exception) {
                null
            }
        }

        val result = ArrayList<HomePageList>()
        if (hero.isNotEmpty()) result.add(HomePageList("المميز", hero))
        episodes?.takeIf { it.isNotEmpty() }?.let { result.add(HomePageList("أحدث الحلقات", it)) }
        movies?.takeIf { it.isNotEmpty() }?.let { result.add(HomePageList("أحدث الأفلام", it)) }
        season?.takeIf { it.isNotEmpty() }?.let { result.add(HomePageList("أشهر أنميات الموسم", it)) }
        result.addAll(others)
        return newHomePageResponse(result, false)
    }

    private fun collectSuggest(node: Any?, out: MutableList<SearchResponse>) {
        when (node) {
            is JSONObject -> {
                val u = node.optString("url")
                val t = node.optString("title")
                if (u.startsWith("http") && t.isNotBlank()) {
                    val type = if (u.contains("/movie/")) TvType.AnimeMovie else TvType.Anime
                    out.add(newAnimeSearchResponse(t, u, type) {
                        this.posterUrl = node.optString("poster").takeIf { it.isNotBlank() }
                    })
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    if (v is JSONObject || v is JSONArray) collectSuggest(v, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectSuggest(node.opt(i), out)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val fromPage = try {
            listingCards(
                app.get("$mainUrl/search?q=$q", headers = mapOf("User-Agent" to userAgent), interceptor = cfKiller).document
            )
        } catch (e: Exception) {
            emptyList()
        }
        if (fromPage.isNotEmpty()) return fromPage
        return try {
            val res = app.get(
                "$mainUrl/search/suggest?q=$q",
                headers = mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
                interceptor = cfKiller
            ).text
            val out = ArrayList<SearchResponse>()
            val root: Any = if (res.trimStart().startsWith("[")) JSONArray(res) else JSONObject(res)
            collectSuggest(root, out)
            out.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var pageUrl = url
        var res = app.get(pageUrl, interceptor = cfKiller)
        if (res.code == 404 && pageUrl.contains("/anime/")) {
            val alt = pageUrl.replace("/anime/", "/movie/")
            val r2 = try { app.get(alt, interceptor = cfKiller) } catch (e: Exception) { null }
            if (r2 != null && r2.code in 200..299) {
                pageUrl = alt
                res = r2
            }
        }
        return loadPage(pageUrl, res.document)
    }

    private suspend fun loadPage(url: String, document: org.jsoup.nodes.Document): LoadResponse {
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val info = document.selectFirst("h1")?.parent()
        val poster = imgUrl(document.selectFirst("div.shrink-0 img"))
        val description = info?.selectFirst("p.leading-relaxed")?.text()?.trim()
        val genres = document.select("span.rounded-full")
            .filter { it.className().contains("bg-white/10") }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val statusText = info?.selectFirst("span.capitalize")?.text().orEmpty()
        val typeText = info?.selectFirst("span.font-bold")?.text().orEmpty()
        val completed = statusText.contains("مكتمل") || statusText.contains("منتهي")
        val tvType = if (typeText.contains("movie", ignoreCase = true) || typeText.contains("فيلم")) {
            TvType.AnimeMovie
        } else TvType.Anime

        fun detail(label: String): String? =
            document.select("span.text-neutral-400")
                .firstOrNull { it.text().trim() == label }
                ?.parent()?.selectFirst("p")?.text()?.trim()

        val year = detail("السنة")?.toIntOrNull()
        val slug = url.trimEnd('/').substringAfterLast("/")
        val isMovieUrl = url.contains("/movie", ignoreCase = true)
        val isMovie = tvType == TvType.AnimeMovie || isMovieUrl

        fun parseEpisodes(links: List<Element>): List<Episode> = links.mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val num = href.trimEnd('/').substringAfterLast("/").toIntOrNull()
            val label = a.selectFirst("span.text-sm")?.text()?.trim()
            newEpisode(fixUrl(href)) {
                this.name = label?.takeIf { it.isNotBlank() } ?: num?.let { "الحلقة $it" } ?: "الحلقة"
                this.episode = num
                this.posterUrl = imgUrl(a.selectFirst("img"))
            }
        }

        var episodes = parseEpisodes(
            document.select("div[data-episode-list]").firstOrNull()?.select("a[href*=/watch/]")?.toList().orEmpty()
        )
        if (episodes.isEmpty()) {
            episodes = parseEpisodes(
                document.select("a[href*=/watch/]").filter { it.attr("href").contains("/$slug") }
            )
        }
        if (episodes.isEmpty() && isMovie) {
            val probes = listOf("$mainUrl/watch/$slug/1", "$mainUrl/watch/$slug", "$mainUrl/movie/$slug/watch")
            for (p in probes) {
                val ok = try {
                    val r = app.get(p, interceptor = cfKiller)
                    r.code in 200..299 && (r.text.contains("sourcesUrl") || r.text.contains("csrf-token"))
                } catch (e: Exception) {
                    false
                }
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

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else tvType) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.year = year
            this.showStatus = if (completed) ShowStatus.Completed else ShowStatus.Ongoing
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ============================= Rate Limiting & Throttling Management =============================
    private val siteLock = Mutex()
    @Volatile private var nextSlotAt = 0L
    @Volatile private var cooldownUntil = 0L
    @Volatile private var throttleHits = 0
    private val SITE_GAP_MS = 900L
    private val BASE_BACKOFF_MS = 2500L

    private suspend fun siteWait() {
        siteLock.withLock {
            val now = System.currentTimeMillis()
            val backoffMs = (BASE_BACKOFF_MS * (1 + throttleHits * 0.5)).toLong().coerceAtMost(15000L)
            val start = maxOf(now, nextSlotAt, cooldownUntil)
            nextSlotAt = start + SITE_GAP_MS + backoffMs
            if (start > now) delay(start - now)
        }
    }

    @Volatile private var rateLimit = 0

    private fun observeRate(h: (String) -> String?) {
        val limit = h("X-RateLimit-Limit")?.trim()?.toIntOrNull()
        val remaining = h("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
        if (limit != null && limit > 0) rateLimit = limit
        if (remaining != null && remaining <= 3) {
            val retry = h("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
            val reset = h("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { r ->
                if (r > 1_000_000_000L) r * 1000 - System.currentTimeMillis() else r * 1000
            }
            val wait = (retry ?: reset ?: 8000L).coerceIn(2000L, 120_000L)
            cooldownUntil = maxOf(cooldownUntil, System.currentTimeMillis() + wait)
        }
    }

    private fun siteBackoff(ms: Long) {
        throttleHits = minOf(throttleHits + 1, 6)
        cooldownUntil = maxOf(cooldownUntil, System.currentTimeMillis() + maxOf(ms, 3500L))
    }

    private suspend fun waitForCooldownIfNeeded() {
        val remaining = cooldownUntil - System.currentTimeMillis()
        if (remaining > 0L) {
            delay(remaining.coerceAtMost(120000L))
            cooldownUntil = 0L
            throttleHits = maxOf(throttleHits - 1, 0)
        }
    }

    private fun isThrottled(code: Int, text: String): Boolean =
        code == 429 || (text.length < 8000 &&
            (text.contains("Too Many Requests", ignoreCase = true) || text.contains("طلبات كثيرة")))

    // ============================= Advanced Link Extraction =============================
    private val linkCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ExtractorLink>>>()
    private val sessionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Session>>()

    private fun retryAfterMs(header: String?, attempt: Int): Long =
        ((header?.trim()?.toLongOrNull()?.times(1000)) ?: (2000L * (attempt + 1))).coerceIn(1500L, 30_000L)

    private data class Session(
        val html: String,
        val document: org.jsoup.nodes.Document,
        val csrf: String,
        val cookies: MutableMap<String, String>
    ) {
        val xsrf: String?
            get() = cookies["XSRF-TOKEN"]?.let {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            }
    }

    private suspend fun openSession(url: String, force: Boolean = false): Session {
        val now = System.currentTimeMillis()
        if (!force) {
            sessionCache[url]?.let { (t, sess) -> if (now - t < 120_000L) return sess }
        }
        waitForCooldownIfNeeded()
        for (i in 0..3) {
            siteWait()
            val page = app.get(url, interceptor = cfKiller)
            observeRate { page.headers[it] }
            if (page.code == 429) {
                siteBackoff(retryAfterMs(page.headers["Retry-After"], i))
                if (i < 3) continue else throw ErrorLoadingException("429 After Retries")
            }
            if (page.code == 403) throw ErrorLoadingException("403 Forbidden")
            if (page.code in 500..599) {
                siteBackoff(4000L)
                if (i < 3) continue else throw ErrorLoadingException("Server Error ${page.code}")
            }
            val html = page.text
            val doc = page.document
            val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: Regex("""(?i)csrf[-_]?token["']?\s*[:=,]\s*["']([A-Za-z0-9]{20,})["']""").find(html)?.groupValues?.get(1)
                ?: ""
            val sess = Session(html, doc, csrf, page.cookies.toMutableMap())
            sessionCache[url] = System.currentTimeMillis() to sess
            return sess
        }
        throw ErrorLoadingException("Failed after retries")
    }

    private suspend fun fetchSources(data: String, first: Session): Pair<String, Session> {
        var session = first
        var lastCode = -1
        var lastText = ""

        fun toUrl(path: String): String = if (path.startsWith("http")) path else mainUrl + path
        val strict = Regex("""sourcesUrl:\s*'([^']+)'""").find(first.html)?.groupValues?.get(1)?.replace("\\/", "/")
        val flexible = Regex("""(?i)\bsourcesUrl\s*[:=]\s*["']([^"']+)["']""").find(first.html)?.groupValues?.get(1)?.replace("\\/", "/")
        val guessed = java.net.URI(data).path.trimEnd('/') + "/sources"
        val candidates = listOfNotNull(strict, flexible, guessed).map { toUrl(it) }.distinct()

        for ((idx, sourcesUrl) in candidates.withIndex()) {
            if (idx > 0) {
                delay(800L)
                session = try { openSession(data, force = true) } catch (e: Exception) { session }
            }
            val token = session.csrf.ifBlank { session.xsrf.orEmpty() }
            val headers = mutableMapOf(
                "Accept" to "application/json",
                "X-CSRF-TOKEN" to token,
                "Origin" to mainUrl,
                "Referer" to data,
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
                "User-Agent" to userAgent
            )
            session.xsrf?.let { headers["X-XSRF-TOKEN"] = it }

            try {
                siteWait()
                val res = app.post(sourcesUrl, headers = headers, cookies = session.cookies, interceptor = cfKiller)
                observeRate { res.headers[it] }
                lastCode = res.code
                lastText = res.text
                session.cookies.putAll(res.cookies)

                if (lastCode == 429) {
                    siteBackoff(retryAfterMs(res.headers["Retry-After"], idx))
                    continue
                }
                if (lastCode in 200..299) {
                    val validJson = try { JSONObject(lastText); true } catch (e: Exception) { false }
                    if (validJson) return lastText to session
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        sessionCache.remove(data)
        return lastText to session
    }

    private data class SrcEntry(
        val quality: String,
        val token: String?,
        val direct: String?,
        val label: String?,
        val version: String?,
        val download: Boolean = false
    )

    private fun isQualityKey(k: String): Boolean {
        val u = k.uppercase()
        return u in setOf("SD", "HD", "FHD", "UHD", "4K") || Regex("""\d{3,4}P?""").matches(u)
    }

    private fun collectEntries(node: Any?, qualityKey: String, out: MutableList<SrcEntry>) {
        when (node) {
            is JSONObject -> {
                val token = node.optString("token").takeIf { tokenRegex.matches(it) }
                val direct = listOf("url", "link", "href", "src", "file")
                    .map { node.optString(it) }
                    .firstOrNull { it.startsWith("http") || it.startsWith("//") }
                if (token != null || direct != null) {
                    out.add(
                        SrcEntry(
                            quality = node.optString("quality").ifBlank { qualityKey },
                            token = token,
                            direct = direct,
                            label = node.optString("label").takeIf { it.isNotBlank() }
                                ?: node.optString("name").takeIf { it.isNotBlank() },
                            version = node.optString("version").takeIf { it.isNotBlank() }
                        )
                    )
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        collectEntries(v, if (isQualityKey(k)) k else qualityKey, out)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectEntries(node.opt(i), qualityKey, out)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val now = System.currentTimeMillis()
        linkCache[data]?.let { (t, links) ->
            if (now - t < 600000L) {
                links.forEach(callback)
                return links.isNotEmpty()
            }
        }

        val extractedLinks = ArrayList<ExtractorLink>()
        try {
            val session = openSession(data)
            val (sourcesJson, finalSession) = fetchSources(data, session)
            
            if (sourcesJson.isBlank()) return false
            
            val root = JSONObject(sourcesJson)
            val players = root.optJSONObject("players") ?: JSONObject()
            val downloads = root.optJSONObject("downloads") ?: JSONObject()
            
            val entries = ArrayList<SrcEntry>()
            collectEntries(players, "HD", entries)
            collectEntries(downloads, "HD", entries)
            
            for (entry in entries.distinctBy { it.token ?: it.direct }) {
                val quality = qualityValue(entry.quality)
                val name = entry.label ?: entry.quality
                
                if (entry.direct != null && entry.direct.startsWith("http")) {
                    val link = newExtractorLink(
                        source = this.name,
                        name = "$name • ${this.name}",
                        url = entry.direct,
                        referer = data,
                        quality = quality,
                        type = if (entry.direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                    extractedLinks.add(link)
                    callback(link)
                }
            }
            
            linkCache[data] = System.currentTimeMillis() to extractedLinks
            return extractedLinks.isNotEmpty()
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }
}
