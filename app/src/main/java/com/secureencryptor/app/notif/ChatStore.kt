package com.secureencryptor.app.notif

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMsg(val sender: String, val text: String, val time: Long, val mine: Boolean)

/** Decrypted conversations, stored on this phone only, encrypted with the Keystore key. */
object ChatStore {
    /** Bumped on every change so Compose screens refresh. */
    val version = mutableStateOf(0)

    private const val FILE = "chats.bin"
    private const val MAX_PER_CHAT = 500
    private var cache: LinkedHashMap<String, MutableList<ChatMsg>>? = null

    @Synchronized
    private fun data(ctx: Context): LinkedHashMap<String, MutableList<ChatMsg>> {
        cache?.let { return it }
        val map = LinkedHashMap<String, MutableList<ChatMsg>>()
        try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (f.exists()) {
                val json = JSONObject(String(SecretStore.open(f.readBytes()), Charsets.UTF_8))
                for (k in json.keys()) {
                    val arr = json.getJSONArray(k)
                    val list = ArrayList<ChatMsg>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(ChatMsg(o.getString("s"), o.getString("t"), o.getLong("ts"), o.getBoolean("m")))
                    }
                    map[k] = list
                }
            }
        } catch (e: Exception) {
            // unreadable store: start fresh
        }
        cache = map
        return map
    }

    @Synchronized
    private fun save(ctx: Context) {
        try {
            val json = JSONObject()
            for ((k, list) in data(ctx)) {
                val arr = JSONArray()
                for (m in list) {
                    arr.put(JSONObject().put("s", m.sender).put("t", m.text).put("ts", m.time).put("m", m.mine))
                }
                json.put(k, arr)
            }
            File(ctx.applicationContext.filesDir, FILE).writeBytes(SecretStore.seal(json.toString().toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            // best effort
        }
    }

    @Synchronized
    fun add(ctx: Context, conv: String, m: ChatMsg): Boolean {
        val list = data(ctx).getOrPut(conv) { ArrayList() }
        if (list.any { it.time == m.time && it.text == m.text && it.mine == m.mine }) return false
        list.add(m)
        list.sortBy { it.time }
        while (list.size > MAX_PER_CHAT) list.removeAt(0)
        save(ctx)
        version.value = version.value + 1
        return true
    }

    @Synchronized
    fun conversations(ctx: Context): List<Pair<String, ChatMsg>> =
        data(ctx).entries
            .filter { it.value.isNotEmpty() }
            .sortedByDescending { it.value.last().time }
            .map { it.key to it.value.last() }

    @Synchronized
    fun messages(ctx: Context, conv: String): List<ChatMsg> = data(ctx)[conv]?.toList() ?: emptyList()

    @Synchronized
    fun clear(ctx: Context) {
        data(ctx).clear()
        save(ctx)
        version.value = version.value + 1
    }
}
