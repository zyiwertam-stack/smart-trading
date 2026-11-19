package com.smarttrading.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trades")
data class TradeEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val pair: String,
    val accountType: String,
    val mode: String,
    val direction: String,
    val stake: Double,
    val expirySeconds: Int,
    val status: String,
    val pnl: Double?,
    val platformId: String?,
    val aiVersion: String,
    val sourceSignal: String
)

@Dao
interface TradeDao {
    @Query("SELECT * FROM trades ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TradeEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(trade: TradeEntity)
}

@Database(entities = [TradeEntity::class], version = 1, exportSchema = false)
abstract class TradeDatabase : RoomDatabase() {
    abstract fun trades(): TradeDao
}
