package com.reelcounter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.LocalDateTime

enum class Source(val label: String) {
    INSTAGRAM("Instagram Reels"),
    YOUTUBE("YouTube Shorts"),
}

data class DaySummary(val day: LocalDate, val instagram: Int, val youtube: Int) {
    val total: Int get() = instagram + youtube
}

/** Local-only store of scroll counts, one row per (app, day, hour). */
class CountStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "reel_counts.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE counts (
                source TEXT NOT NULL,
                day TEXT NOT NULL,
                hour INTEGER NOT NULL,
                count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (source, day, hour)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun increment(source: Source, at: LocalDateTime = LocalDateTime.now()) {
        val db = writableDatabase
        val day = at.toLocalDate().toString()
        db.beginTransaction()
        try {
            val updated = db.compileStatement(
                "UPDATE counts SET count = count + 1 WHERE source = ? AND day = ? AND hour = ?"
            ).run {
                bindString(1, source.name)
                bindString(2, day)
                bindLong(3, at.hour.toLong())
                executeUpdateDelete()
            }
            if (updated == 0) {
                db.insert("counts", null, ContentValues().apply {
                    put("source", source.name)
                    put("day", day)
                    put("hour", at.hour)
                    put("count", 1)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Total for a day, for one app or (source = null) for both. */
    fun totalFor(day: LocalDate, source: Source? = null): Int {
        val args = mutableListOf(day.toString())
        var sql = "SELECT COALESCE(SUM(count), 0) FROM counts WHERE day = ?"
        if (source != null) {
            sql += " AND source = ?"
            args += source.name
        }
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Per-app counts for each of the 24 hours of a day. */
    fun hourly(day: LocalDate): Map<Source, IntArray> {
        val result = Source.values().associateWith { IntArray(24) }
        readableDatabase.rawQuery(
            "SELECT source, hour, count FROM counts WHERE day = ?",
            arrayOf(day.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val source = runCatching { Source.valueOf(c.getString(0)) }.getOrNull() ?: continue
                result.getValue(source)[c.getInt(1)] = c.getInt(2)
            }
        }
        return result
    }

    /** Most recent days first. */
    fun dailySummaries(limit: Int = 60): List<DaySummary> {
        val out = mutableListOf<DaySummary>()
        readableDatabase.rawQuery(
            "SELECT day, " +
                "SUM(CASE WHEN source = 'INSTAGRAM' THEN count ELSE 0 END), " +
                "SUM(CASE WHEN source = 'YOUTUBE' THEN count ELSE 0 END) " +
                "FROM counts GROUP BY day ORDER BY day DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += DaySummary(LocalDate.parse(c.getString(0)), c.getInt(1), c.getInt(2))
            }
        }
        return out
    }

    fun exportCsv(): String {
        val sb = StringBuilder("day,hour,source,count\n")
        readableDatabase.rawQuery(
            "SELECT day, hour, source, count FROM counts ORDER BY day, hour, source", null
        ).use { c ->
            while (c.moveToNext()) {
                sb.append(c.getString(0)).append(',')
                    .append("%02d:00".format(c.getInt(1))).append(',')
                    .append(c.getString(2)).append(',')
                    .append(c.getInt(3)).append('\n')
            }
        }
        return sb.toString()
    }

    fun clearAll() {
        writableDatabase.delete("counts", null, null)
    }

    companion object {
        @Volatile private var instance: CountStore? = null

        fun get(context: Context): CountStore =
            instance ?: synchronized(this) {
                instance ?: CountStore(context.applicationContext).also { instance = it }
            }
    }
}
