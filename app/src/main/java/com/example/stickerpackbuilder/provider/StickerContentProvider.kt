package com.example.stickerpackbuilder.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.stickerpackbuilder.PackRepository
import com.example.stickerpackbuilder.models.StickerPack
import java.io.File

/**
 * Implements WhatsApp's third-party sticker content-provider contract
 * (the same public interface documented at github.com/WhatsApp/stickers).
 * WhatsApp queries this provider directly - nothing here talks to WhatsApp's servers,
 * it's purely WhatsApp reading pack data out of our own local storage.
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        private const val METADATA_CODE = 1
        private const val METADATA_CODE_FOR_SINGLE_PACK = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4

        // Column names WhatsApp expects, verbatim - do not rename these.
        const val STICKER_PACK_IDENTIFIER = "sticker_pack_identifier"
        const val STICKER_PACK_NAME = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER = "sticker_pack_publisher"
        const val STICKER_PACK_ICON = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        const val STICKER_FILE_NAME = "sticker_file_name"
        const val STICKER_FILE_EMOJI = "sticker_emoji"

        private lateinit var AUTHORITY: String
        private lateinit var matcher: UriMatcher

        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private val METADATA_COLUMNS = arrayOf(
            STICKER_PACK_IDENTIFIER, STICKER_PACK_NAME, STICKER_PACK_PUBLISHER, STICKER_PACK_ICON,
            ANDROID_APP_DOWNLOAD_LINK, IOS_APP_DOWNLOAD_LINK, PUBLISHER_EMAIL, PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE, LICENSE_AGREEMENT_WEBSITE, IMAGE_DATA_VERSION, AVOID_CACHE,
            ANIMATED_STICKER_PACK
        )
        private val STICKERS_COLUMNS = arrayOf(STICKER_FILE_NAME, STICKER_FILE_EMOJI)
    }

    override fun onCreate(): Boolean {
        AUTHORITY = "${context!!.packageName}.stickercontentprovider"
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, METADATA, METADATA_CODE)
            addURI(AUTHORITY, "$METADATA/*", METADATA_CODE_FOR_SINGLE_PACK)
            addURI(AUTHORITY, "$STICKERS/*", STICKERS_CODE)
            addURI(AUTHORITY, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
        }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        // Only complete packs (>=3 stickers, has a tray icon) are ever exposed to WhatsApp -
        // an in-progress pack the user hasn't finished shouldn't show up as installable yet.
        val completePacks = PackRepository.loadPacks(context).filter {
            it.isComplete() && runCatching { PackRepository.validatePack(context, it) }.isSuccess
        }

        return when (matcher.match(uri)) {
            METADATA_CODE -> metadataCursor(completePacks)
            METADATA_CODE_FOR_SINGLE_PACK -> {
                val identifier = uri.lastPathSegment
                metadataCursor(completePacks.filter { it.identifier == identifier })
            }
            STICKERS_CODE -> {
                val identifier = uri.lastPathSegment
                val pack = completePacks.firstOrNull { it.identifier == identifier } ?: return null
                stickersCursor(pack)
            }
            else -> null
        }
    }

    private fun metadataCursor(packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(METADATA_COLUMNS)
        for (pack in packs) {
            cursor.newRow()
                .add(STICKER_PACK_IDENTIFIER, pack.identifier)
                .add(STICKER_PACK_NAME, pack.name)
                .add(STICKER_PACK_PUBLISHER, pack.publisher)
                .add(STICKER_PACK_ICON, pack.trayIconFileName)
                .add(ANDROID_APP_DOWNLOAD_LINK, "")
                .add(IOS_APP_DOWNLOAD_LINK, "")
                .add(PUBLISHER_EMAIL, "")
                .add(PUBLISHER_WEBSITE, "")
                .add(PRIVACY_POLICY_WEBSITE, "")
                .add(LICENSE_AGREEMENT_WEBSITE, "")
                .add(IMAGE_DATA_VERSION, pack.version.toString())
                .add(AVOID_CACHE, 0)
                .add(ANIMATED_STICKER_PACK, if (pack.animated) 1 else 0)
        }
        return cursor
    }

    private fun stickersCursor(pack: StickerPack): Cursor {
        val cursor = MatrixCursor(STICKERS_COLUMNS)
        for (sticker in pack.stickers) {
            cursor.newRow().add(STICKER_FILE_NAME, sticker.fileName).add(STICKER_FILE_EMOJI, sticker.emoji)
        }
        return cursor
    }

    override fun getType(uri: Uri): String {
        return when (matcher.match(uri)) {
            METADATA_CODE -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$METADATA"
            METADATA_CODE_FOR_SINGLE_PACK -> "vnd.android.cursor.item/vnd.$AUTHORITY.$METADATA"
            STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$STICKERS"
            STICKERS_ASSET_CODE -> "image/webp"
            else -> throw IllegalArgumentException("Unsupported uri: $uri")
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val context = context ?: return null
        if (mode != "r") throw java.io.FileNotFoundException("Read-only provider")
        if (matcher.match(uri) != STICKERS_ASSET_CODE) return null

        val segments = uri.pathSegments // [stickers_asset, <packId>, <fileName>]
        if (segments.size < 3) return null
        val packId = segments[1]
        val fileName = segments[2]

        val pack = PackRepository.loadPacks(context).firstOrNull {
            it.identifier == packId && it.isComplete()
        } ?: return null
        if (fileName != pack.trayIconFileName && pack.stickers.none { it.fileName == fileName }) return null
        val file = try {
            PackRepository.safeAsset(PackRepository.packDir(context, packId), fileName)
        } catch (_: IllegalArgumentException) { return null }
        if (!file.exists()) return null

        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    // Read-only provider - WhatsApp never writes back to us.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
