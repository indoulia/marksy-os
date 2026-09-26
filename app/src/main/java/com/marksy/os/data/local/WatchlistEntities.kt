package com.marksy.os.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A named stock watchlist (Defence, SmallCap…); a stock may sit in several. */
@Entity(tableName = "watchlists")
data class WatchlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

@Entity(tableName = "watchlist_items", primaryKeys = ["watchlistId", "symbol"], indices = [Index(value = ["symbol"])])
data class WatchlistItemEntity(
    val watchlistId: Long,
    val symbol: String,
    val name: String? = null,
    val addedAt: Long
)

enum class WatchAdd { ADDED, ALREADY_THERE, FULL }

@Dao
interface WatchlistDao {
    @Insert
    suspend fun insertList(list: WatchlistEntity): Long

    @Query("SELECT * FROM watchlists ORDER BY createdAt, id")
    suspend fun lists(): List<WatchlistEntity>

    @Query("SELECT * FROM watchlists ORDER BY createdAt, id")
    fun observeLists(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist_items ORDER BY addedAt, symbol")
    fun observeItems(): Flow<List<WatchlistItemEntity>>

    @Query("SELECT COUNT(*) FROM watchlist_items WHERE watchlistId = :listId")
    suspend fun count(listId: Long): Int

    @Query("SELECT COUNT(*) FROM watchlist_items WHERE watchlistId = :listId AND symbol = :symbol")
    suspend fun contains(listId: Long, symbol: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: WatchlistItemEntity): Long

    @Query("DELETE FROM watchlist_items WHERE watchlistId = :listId AND symbol = :symbol")
    suspend fun removeItem(listId: Long, symbol: String): Int

    @Query("DELETE FROM watchlist_items WHERE watchlistId = :listId")
    suspend fun clearList(listId: Long): Int

    @Query("DELETE FROM watchlists WHERE id = :listId")
    suspend fun deleteListRow(listId: Long): Int

    /** The cap check and insert are one transaction so two quick adds can't overfill a list. */
    @Transaction
    suspend fun addCapped(item: WatchlistItemEntity, max: Int): WatchAdd {
        if (contains(item.watchlistId, item.symbol) > 0) return WatchAdd.ALREADY_THERE
        if (count(item.watchlistId) >= max) return WatchAdd.FULL
        insertItem(item)
        return WatchAdd.ADDED
    }

    @Transaction
    suspend fun deleteList(listId: Long) {
        clearList(listId)
        deleteListRow(listId)
    }
}
