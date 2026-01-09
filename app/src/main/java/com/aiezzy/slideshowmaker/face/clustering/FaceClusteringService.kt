package com.aiezzy.slideshowmaker.face.clustering

import android.util.Log
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.entities.DetectedFaceEntity
import com.aiezzy.slideshowmaker.data.face.entities.FaceClusterEntity
import com.aiezzy.slideshowmaker.face.embedding.FaceEmbeddingGenerator
import java.util.UUID
import kotlin.math.sqrt

/**
 * Service for clustering faces based on embedding similarity.
 * Uses centroid-based incremental clustering algorithm.
 */
class FaceClusteringService(
    private val embeddingGenerator: FaceEmbeddingGenerator
) {

    companion object {
        private const val TAG = "FaceClusteringService"

        // Similarity thresholds (cosine similarity)
        const val SIMILARITY_THRESHOLD = 0.55f  // Threshold for assigning to existing cluster
        const val MERGE_THRESHOLD = 0.70f       // Threshold for merging clusters
        const val HIGH_CONFIDENCE_THRESHOLD = 0.75f  // High confidence match
    }

    /**
     * Result of clustering a single face
     */
    data class ClusterResult(
        val clusterId: String,
        val isNewCluster: Boolean,
        val similarity: Float,
        val updatedCentroid: FloatArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ClusterResult

            if (clusterId != other.clusterId) return false
            if (isNewCluster != other.isNewCluster) return false
            if (similarity != other.similarity) return false
            if (updatedCentroid != null) {
                if (other.updatedCentroid == null) return false
                if (!updatedCentroid.contentEquals(other.updatedCentroid)) return false
            } else if (other.updatedCentroid != null) return false

            return true
        }

        override fun hashCode(): Int {
            var result = clusterId.hashCode()
            result = 31 * result + isNewCluster.hashCode()
            result = 31 * result + similarity.hashCode()
            result = 31 * result + (updatedCentroid?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Cluster a face against existing clusters
     * @param faceEmbedding The embedding of the new face
     * @param existingClusters List of existing clusters with their centroid embeddings
     * @return ClusterResult with assigned cluster ID and updated centroid
     */
    fun clusterFace(
        faceEmbedding: FloatArray,
        existingClusters: List<FaceClusterEntity>
    ): ClusterResult {
        if (existingClusters.isEmpty()) {
            // Create new cluster
            return ClusterResult(
                clusterId = UUID.randomUUID().toString(),
                isNewCluster = true,
                similarity = 1.0f,
                updatedCentroid = faceEmbedding.copyOf()
            )
        }

        // Find best matching cluster
        var bestClusterId: String? = null
        var bestSimilarity = 0f
        var bestCentroid: FloatArray? = null

        for (cluster in existingClusters) {
            val centroidBytes = cluster.centroidEmbedding
            if (centroidBytes == null) continue

            val centroidEmbedding = FaceConverters.byteArrayToFloatArray(centroidBytes)
            val similarity = embeddingGenerator.calculateSimilarity(faceEmbedding, centroidEmbedding)

            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestClusterId = cluster.clusterId
                bestCentroid = centroidEmbedding
            }
        }

        return if (bestSimilarity >= SIMILARITY_THRESHOLD && bestClusterId != null && bestCentroid != null) {
            // Assign to existing cluster and update centroid
            val clusterSize = existingClusters.find { it.clusterId == bestClusterId }?.faceCount ?: 1
            val updatedCentroid = updateCentroid(bestCentroid, faceEmbedding, clusterSize)

            ClusterResult(
                clusterId = bestClusterId,
                isNewCluster = false,
                similarity = bestSimilarity,
                updatedCentroid = updatedCentroid
            )
        } else {
            // Create new cluster
            ClusterResult(
                clusterId = UUID.randomUUID().toString(),
                isNewCluster = true,
                similarity = bestSimilarity,
                updatedCentroid = faceEmbedding.copyOf()
            )
        }
    }

    /**
     * Update cluster centroid with new face embedding
     * Uses running average formula
     */
    private fun updateCentroid(
        currentCentroid: FloatArray,
        newEmbedding: FloatArray,
        currentSize: Int
    ): FloatArray {
        val newCentroid = FloatArray(currentCentroid.size)
        val newSize = currentSize + 1

        for (i in currentCentroid.indices) {
            // Running average: new_centroid = (old_centroid * n + new_value) / (n + 1)
            newCentroid[i] = (currentCentroid[i] * currentSize + newEmbedding[i]) / newSize
        }

        // Normalize the centroid
        return normalizeEmbedding(newCentroid)
    }

    /**
     * Normalize embedding to unit length
     */
    private fun normalizeEmbedding(embedding: FloatArray): FloatArray {
        var norm = 0f
        for (v in embedding) {
            norm += v * v
        }
        norm = sqrt(norm)

        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] = embedding[i] / norm
            }
        }

        return embedding
    }

    /**
     * Find clusters that should be merged based on centroid similarity
     */
    fun findMergeCandidates(clusters: List<FaceClusterEntity>): List<Pair<String, String>> {
        val mergeCandidates = mutableListOf<Pair<String, String>>()

        for (i in clusters.indices) {
            val cluster1 = clusters[i]
            val centroid1Bytes = cluster1.centroidEmbedding ?: continue
            val centroid1 = FaceConverters.byteArrayToFloatArray(centroid1Bytes)

            for (j in i + 1 until clusters.size) {
                val cluster2 = clusters[j]
                val centroid2Bytes = cluster2.centroidEmbedding ?: continue
                val centroid2 = FaceConverters.byteArrayToFloatArray(centroid2Bytes)

                val similarity = embeddingGenerator.calculateSimilarity(centroid1, centroid2)
                if (similarity >= MERGE_THRESHOLD) {
                    mergeCandidates.add(cluster1.clusterId to cluster2.clusterId)
                    Log.d(TAG, "Merge candidate found: ${cluster1.clusterId} <-> ${cluster2.clusterId}, similarity: $similarity")
                }
            }
        }

        return mergeCandidates
    }

    /**
     * Calculate merged centroid for two clusters
     */
    fun calculateMergedCentroid(
        cluster1: FaceClusterEntity,
        cluster2: FaceClusterEntity
    ): FloatArray? {
        val centroid1Bytes = cluster1.centroidEmbedding ?: return null
        val centroid2Bytes = cluster2.centroidEmbedding ?: return null

        val centroid1 = FaceConverters.byteArrayToFloatArray(centroid1Bytes)
        val centroid2 = FaceConverters.byteArrayToFloatArray(centroid2Bytes)

        val totalSize = cluster1.faceCount + cluster2.faceCount
        val mergedCentroid = FloatArray(centroid1.size)

        for (i in centroid1.indices) {
            mergedCentroid[i] = (centroid1[i] * cluster1.faceCount + centroid2[i] * cluster2.faceCount) / totalSize
        }

        return normalizeEmbedding(mergedCentroid)
    }

    /**
     * Calculate similarity between a face and a cluster
     */
    fun calculateFaceClusterSimilarity(
        faceEmbedding: FloatArray,
        cluster: FaceClusterEntity
    ): Float {
        val centroidBytes = cluster.centroidEmbedding ?: return 0f
        val centroid = FaceConverters.byteArrayToFloatArray(centroidBytes)
        return embeddingGenerator.calculateSimilarity(faceEmbedding, centroid)
    }

    /**
     * Find the best representative face for a cluster
     * (face with highest similarity to centroid)
     */
    fun findRepresentativeFace(
        faces: List<DetectedFaceEntity>,
        clusterCentroid: ByteArray
    ): String? {
        if (faces.isEmpty()) return null

        val centroid = FaceConverters.byteArrayToFloatArray(clusterCentroid)
        var bestFaceId: String? = null
        var bestSimilarity = 0f

        for (face in faces) {
            val faceEmbeddingBytes = face.embedding ?: continue
            val faceEmbedding = FaceConverters.byteArrayToFloatArray(faceEmbeddingBytes)
            val similarity = embeddingGenerator.calculateSimilarity(faceEmbedding, centroid)

            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestFaceId = face.faceId
            }
        }

        return bestFaceId
    }

    /**
     * Check if similarity indicates a high-confidence match
     */
    fun isHighConfidenceMatch(similarity: Float): Boolean {
        return similarity >= HIGH_CONFIDENCE_THRESHOLD
    }
}
