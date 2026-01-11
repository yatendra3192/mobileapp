package com.aiezzy.slideshowmaker.di

import android.content.Context
import androidx.room.Room
import com.aiezzy.slideshowmaker.data.face.FaceDatabase
import com.aiezzy.slideshowmaker.data.face.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Room database and DAO instances.
 * All DAOs are provided as singletons scoped to the application lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "face_database"

    @Provides
    @Singleton
    fun provideFaceDatabase(
        @ApplicationContext context: Context
    ): FaceDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            FaceDatabase::class.java,
            DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideScannedPhotoDao(database: FaceDatabase): ScannedPhotoDao {
        return database.scannedPhotoDao()
    }

    @Provides
    @Singleton
    fun provideDetectedFaceDao(database: FaceDatabase): DetectedFaceDao {
        return database.detectedFaceDao()
    }

    @Provides
    @Singleton
    fun providePersonDao(database: FaceDatabase): PersonDao {
        return database.personDao()
    }

    @Provides
    @Singleton
    fun provideFaceClusterDao(database: FaceDatabase): FaceClusterDao {
        return database.faceClusterDao()
    }

    @Provides
    @Singleton
    fun provideScanProgressDao(database: FaceDatabase): ScanProgressDao {
        return database.scanProgressDao()
    }

    @Provides
    @Singleton
    fun provideClusterRepresentativeDao(database: FaceDatabase): ClusterRepresentativeDao {
        return database.clusterRepresentativeDao()
    }

    @Provides
    @Singleton
    fun provideClusterHistoryDao(database: FaceDatabase): ClusterHistoryDao {
        return database.clusterHistoryDao()
    }

    @Provides
    @Singleton
    fun provideClusteringConstraintDao(database: FaceDatabase): ClusteringConstraintDao {
        return database.clusteringConstraintDao()
    }

    @Provides
    @Singleton
    fun provideStagedFaceDao(database: FaceDatabase): StagedFaceDao {
        return database.stagedFaceDao()
    }

    @Provides
    @Singleton
    fun provideClusterAnchorDao(database: FaceDatabase): ClusterAnchorDao {
        return database.clusterAnchorDao()
    }

    @Provides
    @Singleton
    fun provideClusterStatisticsDao(database: FaceDatabase): ClusterStatisticsDao {
        return database.clusterStatisticsDao()
    }
}
