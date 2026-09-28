package com.example.smsserver

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Entity
import java.util.Date

/** Keep the version-one schema so existing sent-message history remains readable. */
@Entity(tableName = "dataStock")
data class LogData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tel") val tel: String,
    @ColumnInfo(name = "sms") val sms: String,
    @ColumnInfo(name = "date") val nowData: Date,
)

@Dao
interface DaoData {
    @Insert
    suspend fun insertData(data: LogData)

    @Query("SELECT * FROM dataStock WHERE date >= :start AND date < :end ORDER BY date DESC")
    suspend fun getFromTable(start: Date, end: Date): List<LogData>
}

class Converters {
    @TypeConverter fun fromTimestamp(value: Long?): Date? = value?.let(::Date)
    @TypeConverter fun dateToTimestamp(date: Date?): Long? = date?.time
}

@Database(entities = [LogData::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun daoData(): DaoData

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "log.db")
                .build().also { instance = it }
        }
    }
}
