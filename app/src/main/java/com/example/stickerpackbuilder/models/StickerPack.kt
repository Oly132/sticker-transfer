package com.example.stickerpackbuilder.models

import org.json.JSONArray
import org.json.JSONObject

data class StickerPack(
    val identifier: String,
    var name: String,
    val publisher: String = "Sticker Sync",
    var trayIconFileName: String = "",
    val stickers: MutableList<Sticker> = mutableListOf(),
    val animated: Boolean = false,
    var version: Long = 1,
    var sealed: Boolean = false
) {
    fun isComplete() = stickers.size in 3..30 && trayIconFileName.isNotEmpty()
    fun toJson() = JSONObject().put("identifier", identifier).put("name", name)
        .put("publisher", publisher).put("trayIconFileName", trayIconFileName)
        .put("animated", animated).put("version", version).put("sealed", sealed)
        .put("stickers", JSONArray().apply { stickers.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(obj: JSONObject) = StickerPack(
            obj.getString("identifier"), obj.getString("name"),
            obj.optString("publisher", "Sticker Sync"), obj.optString("trayIconFileName", ""),
            mutableListOf<Sticker>().apply {
                val arr = obj.optJSONArray("stickers") ?: JSONArray()
                for (i in 0 until arr.length()) add(Sticker.fromJson(arr.getJSONObject(i)))
            }, obj.optBoolean("animated", false), obj.optLong("version", 1),
            obj.optBoolean("sealed", !obj.getString("identifier").startsWith("auto_"))
        )
    }
}

data class Sticker(val fileName: String, val emoji: String = "😀", val hash: String = "") {
    fun toJson() = JSONObject().put("fileName", fileName).put("emoji", emoji).put("hash", hash)
    companion object {
        fun fromJson(obj: JSONObject) = Sticker(obj.getString("fileName"),
            obj.optString("emoji", "😀"), obj.optString("hash", ""))
    }
}
