package com.zaneschepke.wireguardautotunnel.data.repository

import com.zaneschepke.wireguardautotunnel.data.entity.Asset
import com.zaneschepke.wireguardautotunnel.util.NumberUtils
import org.junit.Assert.assertTrue
import org.junit.Test

/** The daily UpdateCheckWorker's decision: latest release tag version vs BuildConfig.VERSION_NAME. */
class UpdateVersionTest {
    private val latest = ApkAssetSelector.select(
        listOf(Asset("transwarp-standalone-v5.18.0-arm64.apk", "u")), "standalone", listOf("arm64-v8a"))!!.version

    @Test
    fun olderInstallSeesUpdateNewerDoesNot() {
        assertTrue(NumberUtils.compareVersions(latest, "5.17.0") > 0) // forced lower version: notify
        assertTrue(NumberUtils.compareVersions(latest, "5.19.0") < 0) // 5.19.0 debug: nothing
        assertTrue(NumberUtils.compareVersions(latest, "5.18.0") == 0)
        assertTrue(NumberUtils.compareVersions("5.10.0", "5.9.0") > 0) // numeric, not lexical
    }
}
