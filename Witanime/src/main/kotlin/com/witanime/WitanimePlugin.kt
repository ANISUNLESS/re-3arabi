package com.cimanow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.witanime.MailruExtractor
import com.witanime.VideaExtractor
import com.witanime.WitAnime

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WitAnime(context))
        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(MailruExtractor())
    }
}
