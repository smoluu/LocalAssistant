package com.localassistant.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database singleton for the Local Assistant app.
 */
@Database(
    entities = [ChatMessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class LocalAssistantDatabase : RoomDatabase() {
    
    abstract fun chatMessageDao(): ChatMessageDao
    
    companion object {
        private const val DATABASE_NAME = "local_assistant_db"
        
        @Volatile
        private var INSTANCE: LocalAssistantDatabase? = null
        
        fun getInstance(context: Context): LocalAssistantDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): LocalAssistantDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                LocalAssistantDatabase::class.java,
                DATABASE_NAME
            )
            .fallbackToDestructiveMigration()
            .build()
        }
    }
}
