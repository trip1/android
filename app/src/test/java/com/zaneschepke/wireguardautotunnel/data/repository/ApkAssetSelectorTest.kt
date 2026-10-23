package com.zaneschepke.wireguardautotunnel.data.repository

import com.zaneschepke.wireguardautotunnel.data.entity.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkAssetSelectorTest {
    // Exactly the names the build outputs and the release uploads
    private val assets =
        listOf(
                "transwarp-standalone-v5.10.0.apk",
                "transwarp-standalone-v5.10.0-arm64.apk",
                "transwarp-standalone-v5.10.0-armv7.apk",
                "SHA256SUMS",
                "tunnel-list.png",
            )
            .map { Asset(it, "https://example/$it") }

    private fun pick(abis: List<String>) = ApkAssetSelector.select(assets, "standalone", abis)

    @Test
    fun picksAbiSplitThenUniversal() {
        assertEquals("transwarp-standalone-v5.10.0-arm64.apk", pick(listOf("arm64-v8a", "armeabi-v7a"))?.asset?.name)
        assertEquals("transwarp-standalone-v5.10.0-armv7.apk", pick(listOf("armeabi-v7a"))?.asset?.name)
        assertEquals("transwarp-standalone-v5.10.0.apk", pick(listOf("x86_64"))?.asset?.name)
        assertEquals("5.10.0", pick(listOf("arm64-v8a"))?.version)
        assertEquals("5.10.0", pick(listOf("x86_64"))?.version)
    }

    @Test
    fun ignoresOtherFlavorsAndUpstreamNames() {
        val other = listOf("wgtunnel-standalone-v4.0.0.apk", "transwarp-fdroid-v5.10.0.apk").map { Asset(it, "u") }
        assertNull(ApkAssetSelector.select(other, "standalone", listOf("arm64-v8a")))
    }
}
