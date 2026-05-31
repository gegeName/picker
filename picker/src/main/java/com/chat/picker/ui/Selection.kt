package com.chat.picker.ui

import com.chat.picker.model.MediaEntity

internal object Selection {
    val all = mutableListOf<MediaEntity>()
    val selected = linkedSetOf<MediaEntity>()
    var max: Int = 9

    data class ToggleResult(
        val accepted: Boolean,
        val affected: List<MediaEntity>,
    )

    private fun key(e: MediaEntity): Long =
        (e.id shl 4) or (e.mediaType.ordinal.toLong() and 0xF)

    fun clear() {
        all.clear()
        selected.clear()
    }

    fun toggle(item: MediaEntity): ToggleResult {
        val k = key(item)
        val ordered = selected.toList()
        val idx = ordered.indexOfFirst { key(it) == k }
        return if (idx >= 0) {
            selected.remove(ordered[idx])
            ToggleResult(true, ordered.subList(idx, ordered.size).toList())
        } else {
            if (selected.size >= max) ToggleResult(false, emptyList())
            else {
                selected.add(item)
                ToggleResult(true, listOf(item))
            }
        }
    }

    fun selectSingle(item: MediaEntity): ToggleResult {
        val k = key(item)
        val ordered = selected.toList()
        val alreadySelected = ordered.any { key(it) == k }
        selected.clear()
        return if (alreadySelected) {
            ToggleResult(true, ordered)
        } else {
            selected.add(item)
            ToggleResult(true, ordered + item)
        }
    }

    fun indexOf(item: MediaEntity): Int {
        val k = key(item)
        var i = 0
        for (e in selected) {
            i++
            if (key(e) == k) return i
        }
        return -1
    }

    fun preSelect(items: Collection<MediaEntity>) {
        if (items.isEmpty()) return
        val existed = HashSet<Long>(selected.size * 2 + items.size).apply {
            selected.forEach { add(key(it)) }
        }
        items.forEach { e ->
            if (selected.size >= max) return
            if (existed.add(key(e))) selected.add(e)
        }
    }
}
