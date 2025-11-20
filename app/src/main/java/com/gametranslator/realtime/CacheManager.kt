package com.gametranslator.realtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicInteger

class CacheManager(context: Context) : SQLiteOpenHelper(context, "translation_cache.db", null, 2) {

    private val appContext = context.applicationContext
    private val operationCounter = AtomicInteger(0)
    private val CLEANUP_THRESHOLD = 50 // Чистка после каждых 50 операций

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS translations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_text TEXT UNIQUE,
                translated_text TEXT,
                access_count INTEGER DEFAULT 1,
                last_accessed DATETIME DEFAULT CURRENT_TIMESTAMP,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())
        
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_original_text ON translations(original_text)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_last_accessed ON translations(last_accessed)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_count ON translations(access_count)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE translations ADD COLUMN access_count INTEGER DEFAULT 1")
            db.execSQL("ALTER TABLE translations ADD COLUMN last_accessed DATETIME DEFAULT CURRENT_TIMESTAMP")
        }
    }

    fun getTranslation(originalText: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            "translations", 
            arrayOf("translated_text", "access_count"), 
            "original_text = ?", 
            arrayOf(originalText), 
            null, null, null
        )
        
        return try {
            if (cursor.moveToFirst()) {
                val translated = cursor.getString(0)
                val accessCount = cursor.getInt(1) + 1
                
                // Обновляем счетчик обращений и время
                updateAccessStats(originalText, accessCount)
                translated
            } else {
                null
            }
        } finally {
            cursor.close()
            
            // Периодическая очистка старых записей
            if (operationCounter.incrementAndGet() >= CLEANUP_THRESHOLD) {
                operationCounter.set(0)
                cleanOldEntries()
            }
        }
    }

    fun saveTranslation(originalText: String, translatedText: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("original_text", originalText)
            put("translated_text", translatedText)
            put("access_count", 1)
            put("last_accessed", System.currentTimeMillis())
        }
        
        db.insertWithOnConflict("translations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun updateAccessStats(originalText: String, accessCount: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("access_count", accessCount)
            put("last_accessed", System.currentTimeMillis())
        }
        db.update("translations", values, "original_text = ?", arrayOf(originalText))
    }

    private fun cleanOldEntries() {
        val db = writableDatabase
        // Удаляем редко используемые записи и старые записи
        db.execSQL("""
            DELETE FROM translations 
            WHERE (
                last_accessed < datetime('now', '-1 days') 
                AND access_count < 3
            ) OR (
                last_accessed < datetime('now', '-7 days')
            )
            OR id NOT IN (
                SELECT id FROM translations 
                ORDER BY access_count DESC, last_accessed DESC 
                LIMIT 500 -- Максимум 500 самых популярных записей
            )
        """.trimIndent())
        
        // Оптимизация базы данных
        db.execSQL("VACUUM")
    }

    fun closeDB() {
        try { 
            cleanOldEntries()
            super.close() 
        } catch (_: Exception) {}
    }
}
