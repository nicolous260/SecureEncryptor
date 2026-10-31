package com.nicolous.statussaver

import android.content.Context

/** Tiny settings store: trigger word, chosen status folders, already-saved file names. */
object Store {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("status_saver", Context.MODE_PRIVATE)

    fun trigger(ctx: Context): String = p(ctx).getString("trigger", "nic") ?: "nic"
    fun setTrigger(ctx: Context, v: String) = p(ctx).edit().putString("trigger", v).apply()

    fun tree(ctx: Context, pkg: String): String? = p(ctx).getString("tree_$pkg", null)
    fun setTree(ctx: Context, pkg: String, uri: String) = p(ctx).edit().putString("tree_$pkg", uri).apply()

    fun isSaved(ctx: Context, name: String): Boolean = savedList(ctx).contains(name)

    fun addSaved(ctx: Context, name: String) {
        val l = savedList(ctx).toMutableList()
        l.add(name)
        val trimmed = if (l.size > 300) l.subList(l.size - 300, l.size) else l
        p(ctx).edit().putString("saved", trimmed.joinToString("\n")).putString("last", name).apply()
    }

    fun last(ctx: Context): String? = p(ctx).getString("last", null)

    private fun savedList(ctx: Context): List<String> =
        p(ctx).getString("saved", "")!!.split("\n").filter { it.isNotEmpty() }
}
