package com.example.smsserver

import android.content.Context
import androidx.room.*
import io.ktor.util.toLocalDateTime
import java.util.*


@Database(entities = [LogData::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    companion object {

        private var APPDATABASE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase? {
            if (APPDATABASE == null) {
                synchronized(AppDatabase::class) {
                    APPDATABASE = Room.databaseBuilder(context.applicationContext,
                        AppDatabase::class.java, "log.db").allowMainThreadQueries().build()
                }
            }
            return APPDATABASE
        }
    }


    abstract fun daoData(): DaoData
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time?.toLong()
    }
}
