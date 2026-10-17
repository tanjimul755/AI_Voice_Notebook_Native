package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Main Room Database for the AI Voice Notebook application.
 * Manages persistent storage of VoiceNoteEntity metadata.
 */
@Database(entities = [VoiceNoteEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun voiceNoteDao(): VoiceNoteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE voice_notes ADD COLUMN reminderTask TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE voice_notes ADD COLUMN isReminderEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE voice_notes ADD COLUMN reminderStatus TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("UPDATE voice_notes SET isReminderEnabled = 1, reminderStatus = 'SCHEDULED' WHERE reminderTimestamp IS NOT NULL AND reminderTimestamp > 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voice_notebook_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun setTestInstance(testDb: AppDatabase?) {
            INSTANCE = testDb
        }
    }
}
