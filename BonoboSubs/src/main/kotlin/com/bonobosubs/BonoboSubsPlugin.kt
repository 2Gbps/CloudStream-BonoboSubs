package com.bonobosubs

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BonoboSubsPlugin : BasePlugin() {
    override fun load() {
        // All providers should be added in this manner.
        registerMainAPI(BonoboSubsProvider())
    }
}
