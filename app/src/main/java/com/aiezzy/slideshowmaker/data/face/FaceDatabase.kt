package com.aiezzy.slideshowmaker.data.face

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.dao.*
import com.aiezzy.slideshowmaker.data.face.entities.*

@Database(
    entities = [
        ScannedPhotoEntity::class,
        DetectedFaceEntity::class,
        PersonEntity::class,
        FaceClusterEntity::class,
        ScanProgressEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(FaceConverters::class)
abstract class FaceDatabase : RoomDatabase() {

    abstract fun scannedPhotoDao(): ScannedPhotoDao
    abstract fun detectedFaceDao(): DetectedFaceDao
    abstract fun personDao(): PersonDao
    abstract fun faceClusterDao(): FaceClusterDao
    abstract fun scanProgressDao(): ScanProgressDao

    companion object {
        private const val DATABASE_NAME = "face_database"

        @Volatile
        private var INSTANCE: FaceDatabase? = null

        fun getInstance(context: Context): FaceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): FaceDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                FaceDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Clear the singleton instance (useful for testing)
         */
        fun clearInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
