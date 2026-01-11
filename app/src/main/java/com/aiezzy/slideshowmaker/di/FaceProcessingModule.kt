package com.aiezzy.slideshowmaker.di

import android.content.Context
import com.aiezzy.slideshowmaker.data.face.dao.ClusterAnchorDao
import com.aiezzy.slideshowmaker.data.face.dao.ClusterStatisticsDao
import com.aiezzy.slideshowmaker.face.clustering.FaceClusteringService
import com.aiezzy.slideshowmaker.face.clustering.TwoPassClusteringService
import com.aiezzy.slideshowmaker.face.clustering.index.AnchorEmbeddingIndex
import com.aiezzy.slideshowmaker.face.clustering.index.FaceEmbeddingIndex
import com.aiezzy.slideshowmaker.face.detection.FaceDetectionService
import com.aiezzy.slideshowmaker.face.embedding.FaceEmbeddingGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing face processing service instances.
 * All services are singletons to ensure consistent model loading
 * and efficient resource usage.
 */
@Module
@InstallIn(SingletonComponent::class)
object FaceProcessingModule {

    @Provides
    @Singleton
    fun provideFaceDetectionService(
        @ApplicationContext context: Context
    ): FaceDetectionService {
        return FaceDetectionService(context)
    }

    @Provides
    @Singleton
    fun provideFaceEmbeddingGenerator(
        @ApplicationContext context: Context
    ): FaceEmbeddingGenerator {
        return FaceEmbeddingGenerator(context)
    }

    @Provides
    @Singleton
    fun provideFaceClusteringService(
        embeddingGenerator: FaceEmbeddingGenerator
    ): FaceClusteringService {
        return FaceClusteringService(embeddingGenerator)
    }

    @Provides
    @Singleton
    fun provideFaceEmbeddingIndex(
        @ApplicationContext context: Context
    ): FaceEmbeddingIndex {
        return FaceEmbeddingIndex(context)
    }

    @Provides
    @Singleton
    fun provideAnchorEmbeddingIndex(
        @ApplicationContext context: Context
    ): AnchorEmbeddingIndex {
        return AnchorEmbeddingIndex(context)
    }

    @Provides
    @Singleton
    fun provideTwoPassClusteringService(
        clusteringService: FaceClusteringService,
        anchorIndex: AnchorEmbeddingIndex,
        anchorDao: ClusterAnchorDao,
        statisticsDao: ClusterStatisticsDao
    ): TwoPassClusteringService {
        return TwoPassClusteringService(clusteringService, anchorIndex, anchorDao, statisticsDao)
    }
}
