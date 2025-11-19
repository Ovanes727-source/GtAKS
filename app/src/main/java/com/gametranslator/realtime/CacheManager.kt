
package com.gametranslator.realtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CacheManager(context: Context) : SQLiteOpenHelper(context, "translation_cache.db", null, 1) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS translations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_text TEXT UNIQUE,
                translated_text TEXT,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_original_text ON translations(original_text)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS translations")
        onCreate(db)
    }

    fun getTranslation(originalText: String): String? {
        val db = readableDatabase
        val cursor = db.query("translations", arrayOf("translated_text"), "original_text = ?", arrayOf(originalText), null, null, null)
        val result = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        return result
    }

    fun saveTranslation(originalText: String, translatedText: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("original_text", originalText)
            put("translated_text", translatedText)
        }
        db.insertWithOnConflict("translations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        cleanOldEntries()
    }

    private fun cleanOldEntries() {
        val db = writableDatabase
        db.execSQL(
            """
            DELETE FROM translations 
            WHERE timestamp < datetime('now', '-7 days')
            AND id NOT IN (
                SELECT id FROM translations ORDER BY timestamp DESC LIMIT 1000
            )
            """.trimIndent()
        )
    }

    fun closeDB() {
        try { super.close() } catch (_: Exception) {}
    }
}
