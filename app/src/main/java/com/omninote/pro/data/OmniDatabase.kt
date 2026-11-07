package com.omninote.pro.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class,
        NotebookEntity::class,
        PageMetaEntity::class,
        TagEntity::class,
        NotebookTagCrossRef::class,
        PageOcrFtsEntity::class,
        StickerCollectionEntity::class,
        StickerElementEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class OmniDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun stickerDao(): StickerDao

    companion object {
        const val FILE_NAME = "omninote.db"

        fun create(context: Context): OmniDatabase =
            Room.databaseBuilder(context.applicationContext, OmniDatabase::class.java, FILE_NAME)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
