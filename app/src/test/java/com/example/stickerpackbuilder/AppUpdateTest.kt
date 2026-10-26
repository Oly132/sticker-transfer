package com.example.stickerpackbuilder

import com.example.stickerpackbuilder.update.GitHubUpdates
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppUpdateTest {
    private val url = "https://github.com/Oly132/sticker-transfer/releases/download/v4.8.0/StickerTransfer.apk"
    private fun release(tag: String = "v4.8.0") = JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "StickerTransfer.apk").put("browser_download_url", url)
            .put("size", 100_000_000).put("state", "uploaded").put("digest", "sha256:" + "a".repeat(64))))
    @Test fun releaseVersionsAreNumericAndExcludeCurrentOlderAndNonVersionTags() {
        assertTrue(GitHubUpdates.newer("v4.10.0", "4.9-samsung"))
        assertFalse(GitHubUpdates.newer("v4.7.0", "4.7-samsung"))
        assertFalse(GitHubUpdates.newer("v4.6.1", "4.7-samsung"))
        assertFalse(GitHubUpdates.newer("latest", "4.7-samsung"))
        assertNull(GitHubUpdates.parse(release("v4.7.0"), "4.7-samsung"))
    }
    @Test fun draftsAndPrereleasesDoNotPromptStableUsers() {
        assertNull(GitHubUpdates.parse(release().put("draft", true), "4.7-samsung"))
        assertNull(GitHubUpdates.parse(release().put("prerelease", true), "4.7-samsung"))
    }
    @Test fun anOrdinaryGithubApkReleaseNeedsNoExtraUpdateManifest() {
        val info = GitHubUpdates.parse(release(), "4.7-samsung")!!
        assertEquals(url, info.url); assertEquals("a".repeat(64), info.sha256)
        assertEquals(info, GitHubUpdates.restore(info.json()))
        val withoutDigest = release()
        withoutDigest.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertNull(GitHubUpdates.parse(withoutDigest, "4.7-samsung")!!.sha256)
    }
    @Test fun otherRepositoriesCleartextCredentialsAndEncodedTraversalAreRejected() {
        for (value in listOf(url.replace("https:", "http:"), url.replace("Oly132", "attacker"),
            url.replace("github.com", "github.com.attacker.example"), url.replace("github.com", "user@github.com"),
            url.replace("v4.8.0", "%2F..%2F"))) assertFalse(value, GitHubUpdates.trustedAsset(value))
    }
    @Test fun oversizedOrMalformedAssetsAreRejectedBeforeDownload() {
        val bad = release()
        bad.getJSONArray("assets").getJSONObject(0).put("size", GitHubUpdates.MAX_APK_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdates.parse(bad, "4.7-samsung") }
        val digest = release()
        digest.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:wrong")
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdates.parse(digest, "4.7-samsung") }
    }
    @Test fun noApkDoesNotOfferADownload() {
        assertNull(GitHubUpdates.parse(release().put("assets", JSONArray()), "4.7-samsung"))
    }
    @Test fun packageVersionAndSigningCertificateMustAllMatch() {
        GitHubUpdates.verifyIdentity("app", "app", 21, 20, setOf("certificate"), setOf("certificate"))
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdates.verifyIdentity("other", "app", 21, 20, setOf("certificate"), setOf("certificate")) }
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdates.verifyIdentity("app", "app", 20, 20, setOf("certificate"), setOf("certificate")) }
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdates.verifyIdentity("app", "app", 21, 20, setOf("other"), setOf("certificate")) }
        assertThrows(IllegalArgumentException::class.java) { GitHubUpdates.verifyIdentity("app", "app", 21, 20, emptySet(), emptySet()) }
    }
    @Test fun updateFileProviderIsPrivateAndGrantOnly() {
        val context = RuntimeEnvironment.getApplication()
        val provider = context.packageManager.resolveContentProvider(context.packageName + ".updates", 0)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
    }
    @Test fun updateUriCannotExposeOtherCacheFilesOrPrivateAccountFiles() {
        val context = RuntimeEnvironment.getApplication()
        val update = java.io.File(context.cacheDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", update)
        assertEquals("content", uri.scheme)
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", java.io.File(context.filesDir, "account.json"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", java.io.File(context.cacheDir, "other.txt"))
        }
    }
}
