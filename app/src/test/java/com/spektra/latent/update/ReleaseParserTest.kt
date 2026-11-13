package com.spektra.latent.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseParserTest {
    @Test fun explicitApkChannelsAreRespectedAndGenericNamesStillWork() {
        assertTrue(ReleaseParser.compatibleApk("Spektramat-v0.1.0-preview.2.apk", debug = true))
        assertTrue(ReleaseParser.compatibleApk("Spektramat-v0.1.0-preview.2.apk", debug = false))
        assertTrue(ReleaseParser.compatibleApk("app-debug.apk", debug = true))
        assertTrue(ReleaseParser.compatibleApk("Spektramat-dev-arm64.apk", debug = true))
        assertFalse(ReleaseParser.compatibleApk("app-release.apk", debug = true))
        assertFalse(ReleaseParser.compatibleApk("app-debug.apk", debug = false))
        assertFalse(ReleaseParser.compatibleApk("Spektramat-dev-arm64.apk", debug = false))
        assertFalse(ReleaseParser.compatibleApk("app.zip", debug = true))
    }

    @Test fun semanticVersionsKeepPreviewOrder() {
        assertTrue(ReleaseParser.compare("v0.1.0-preview.10", "0.1.0-preview.2-dev") > 0)
        assertTrue(ReleaseParser.compare("v0.1.0", "0.1.0-preview.10") > 0)
        assertTrue(ReleaseParser.compare("v0.2.0-preview.1", "0.1.0") > 0)
        assertEquals(0, ReleaseParser.compare("v0.1.0-preview.2", "0.1.0-preview.2-dev"))
        assertTrue(ReleaseParser.compare("v0.1.0-preview.1", "0.1.0-preview.2-dev") < 0)
    }

    @Test fun latestFindsPreviewApkAndIgnoresDraftsAndNonApks() {
        val releases = """[
          {"tag_name":"v0.1.0-preview.3","published_at":"2026-11-13","prerelease":true,"assets":[{"name":"Spektramat-preview.3-debug.apk","size":123,"browser_download_url":"https://github.com/Knonoa/Spektramat/releases/download/v0.1.0-preview.3/app.apk","digest":"sha256:${"a".repeat(64)}"}]},
          {"tag_name":"v1.0.0","published_at":"2026-11-13","draft":true,"assets":[{"name":"app.apk"}]},
          {"tag_name":"v2.0.0","published_at":"2026-11-13","assets":[{"name":"source.zip"}]},
          {"tag_name":"v0.1.0-preview.1","published_at":"2026-11-12","assets":[{"name":"app.apk"}]}
        ]"""
        val release = ReleaseParser.latest(releases)!!
        assertEquals("v0.1.0-preview.3", release.tag)
        assertEquals("a".repeat(64), release.apk.digest)
        assertEquals(123L, release.apk.size)
    }

    @Test fun checksumsMustMatchTheExactAsset() {
        val hash = "b".repeat(64)
        assertEquals(hash, ReleaseParser.checksum("$hash  app.apk\n${"a".repeat(64)} *other.apk", "app.apk"))
        assertNull(ReleaseParser.checksum("$hash  other.apk", "app.apk"))
        assertNull(ReleaseParser.checksum(hash, "app.apk"))
        assertEquals(hash, ReleaseParser.checksum(hash, "app.apk", bare = true))
        assertNull(ReleaseParser.checksum("invalid app.apk", "app.apk", bare = true))
    }
}
