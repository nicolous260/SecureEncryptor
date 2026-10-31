package com.nicolous.statusgallery

import android.content.Context

/** Tiny settings store: chosen status folders and which statuses were already downloaded. */
object Store {
    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("status_gallery", Context.MODE_PRIVATE)

    fun tree(ctx: Context, pkg: String): String? = p(ctx).getString("tree_$pkg", null)
    fun setTree(ctx: Context, pkg: String, uri: String) = p(ctx).edit().putString("tree_$pkg", uri).apply()

    fun savedUri(ctx: Context, name: String): String? = p(ctx).getString("saved_$name", null)
    fun markSaved(ctx: Context, name: String, uri: String) = p(ctx).edit().putString("saved_$name", uri).apply()

    fun forgetUri(ctx: Context, uri: String) {
        val e = p(ctx).edit()
        p(ctx).all.filter { it.key.startsWith("saved_") && it.value == uri }.keys.forEach { e.remove(it) }
        e.apply()
    }

    /** status file name -> uri of the downloaded copy */
    fun savedEntries(ctx: Context): Map<String, String> =
        p(ctx).all
            .filterKeys { it.startsWith("saved_") }
            .mapKeys { it.key.removePrefix("saved_") }
            .mapValues { it.value.toString() }
}
