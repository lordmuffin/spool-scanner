package com.bambu.nfc.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.bambu.nfc.data.spoolman.Spool
import com.bambu.nfc.data.spoolman.SpoolmanJson

/**
 * Optional offline cache: the last spool list fetched from Spoolman, stored as the raw JSON of
 * each spool, so the inventory still shows something when Spoolman can't be reached (e.g. away
 * from home Wi-Fi). Never written to Spoolman; Spoolman stays the source of truth.
 */
@Entity(tableName = "cached_spools")
data class CachedSpoolEntity(
    @PrimaryKey val id: Long,
    val json: String,
    val fetchedAt: Long,
)

@Dao
interface CachedSpoolDao {
    @Query("SELECT * FROM cached_spools ORDER BY id DESC")
    suspend fun all(): List<CachedSpoolEntity>

    @Query("DELETE FROM cached_spools")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CachedSpoolEntity>)

    @Transaction
    suspend fun replaceAll(rows: List<CachedSpoolEntity>) {
        clear()
        insertAll(rows)
    }
}

@Database(entities = [CachedSpoolEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cachedSpoolDao(): CachedSpoolDao
}

class SpoolCache(private val dao: CachedSpoolDao) {
    suspend fun save(spools: List<Spool>) {
        val now = System.currentTimeMillis()
        dao.replaceAll(spools.map { CachedSpoolEntity(it.id, SpoolmanJson.encodeToString(Spool.serializer(), it), now) })
    }

    /** Cached spools and when they were fetched (null when the cache is empty). */
    suspend fun load(): Pair<List<Spool>, Long?> {
        val rows = dao.all()
        val spools = rows.mapNotNull { runCatching { SpoolmanJson.decodeFromString(Spool.serializer(), it.json) }.getOrNull() }
        return spools to rows.maxOfOrNull { it.fetchedAt }
    }
}
