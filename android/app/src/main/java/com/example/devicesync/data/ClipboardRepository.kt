package com.example.devicesync.data

import java.util.UUID

class ClipboardRepository(private val dao: ClipboardDao) {

    companion object {
        const val MAX_ITEMS = 10
    }

    fun getAllItems() = dao.getAllItems()

    suspend fun addItem(content: String, source: String, clipboardId: String? = null): Boolean {
        val id = clipboardId ?: UUID.randomUUID().toString()
        if (dao.existsById(id) > 0) return false

        val item = ClipboardItem(
            clipboardId = id,
            source = source,
            content = content,
            timestamp = System.currentTimeMillis()
        )

        dao.insert(item)

        while (dao.getCount() > MAX_ITEMS) {
            dao.deleteOldest()
        }

        return true
    }
}
