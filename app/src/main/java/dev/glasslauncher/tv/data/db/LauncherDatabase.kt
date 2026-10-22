package dev.glasslauncher.tv.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Пользовательские настройки конкретного приложения: порядок, избранное, скрытие. */
@Entity(tableName = "app_prefs")
data class AppPrefEntity(
    @PrimaryKey val packageName: String,
    val sortIndex: Int = Int.MAX_VALUE,
    val hidden: Boolean = false,
    val favorite: Boolean = false,
    val favoriteIndex: Int = Int.MAX_VALUE,
)

@Dao
interface AppPrefDao {
    @Query("SELECT * FROM app_prefs")
    fun observeAll(): Flow<List<AppPrefEntity>>

    @Query("SELECT * FROM app_prefs WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppPrefEntity?

    @Query("SELECT MAX(favoriteIndex) FROM app_prefs WHERE favorite = 1")
    suspend fun maxFavoriteIndex(): Int?

    @Upsert
    suspend fun upsert(entity: AppPrefEntity)

    @Query("DELETE FROM app_prefs WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("UPDATE app_prefs SET sortIndex = 2147483647")
    suspend fun resetOrder()
}

@Database(entities = [AppPrefEntity::class], version = 1, exportSchema = false)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun appPrefDao(): AppPrefDao
}
