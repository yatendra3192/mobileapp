package com.aiezzy.slideshowmaker.face.clustering.index

import android.content.Context
import android.util.Log
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.entities.FaceClusterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Face embedding index for efficient nearest neighbor search.
 *
 * Manages HNSW index for cluster centroids, enabling O(log n) cluster lookup
 * instead of O(n) linear search.
 *
 * Key features:
 * - Automatic persistence to disk
 * - Incremental updates (add/remove clusters)
 * - Batch initialization from database
 * - Similarity threshold search
 */
@Singleton
class FaceEmbeddingIndex @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "FaceEmbeddingIndex"
        private const val INDEX_FILENAME = "face_cluster_index.hnsw"

        // Default embedding dimensions (FaceNet = 512, MobileFaceNet = 192)
        private const val DEFAULT_DIMENSIONS = 512

        // HNSW parameters tuned for face clustering
        private const val M = 16                // Max connections per layer
        private const val EF_CONSTRUCTION = 200 // Construction-time ef
        private const val EF_SEARCH = 100       // Search-time ef (higher = more accurate)
    }

    private var index: HnswIndex? = null
    private var dimensions = DEFAULT_DIMENSIONS

    /**
     * Initialize the index, optionally loading from disk.
     *
     * @param embeddingDimensions The dimensionality of embeddings
     * @param loadFromDisk Whether to attempt loading existing index
     */
    suspend fun initialize(
        embeddingDimensions: Int = DEFAULT_DIMENSIONS,
        loadFromDisk: Boolean = true
    ) = withContext(Dispatchers.IO) {
        dimensions = embeddingDimensions
        index = HnswIndex(
            dimensions = dimensions,
            m = M,
            efConstruction = EF_CONSTRUCTION,
            efSearch = EF_SEARCH
        )

        if (loadFromDisk) {
            val indexFile = getIndexFile()
            if (indexFile.exists()) {
                val loaded = index?.load(indexFile) ?: false
                if (loaded) {
                    Log.i(TAG, "Loaded index from disk: ${index?.size} clusters")
                } else {
                    Log.w(TAG, "Failed to load index from disk, starting fresh")
                }
            }
        }
    }

    /**
     * Build the index from a list of clusters.
     * This replaces any existing index data.
     */
    suspend fun buildFromClusters(clusters: List<FaceClusterEntity>) = withContext(Dispatchers.IO) {
        if (index == null) {
            initialize()
        }

        index?.clear()

        var added = 0
        var skipped = 0

        for (cluster in clusters) {
            val centroidBytes = cluster.centroidEmbedding
            if (centroidBytes == null) {
                skipped++
                continue
            }

            val embedding = FaceConverters.byteArrayToFloatArray(centroidBytes)

            // Detect dimensions from first valid embedding
            if (added == 0 && embedding.size != dimensions) {
                Log.i(TAG, "Adjusting dimensions from $dimensions to ${embedding.size}")
                dimensions = embedding.size
                index = HnswIndex(
                    dimensions = dimensions,
                    m = M,
                    efConstruction = EF_CONSTRUCTION,
                    efSearch = EF_SEARCH
                )
            }

            try {
                index?.insert(cluster.clusterId, embedding)
                added++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add cluster ${cluster.clusterId}: ${e.message}")
                skipped++
            }
        }

        Log.i(TAG, "Built index with $added clusters ($skipped skipped)")

        // Save to disk
        saveIndex()
    }

    /**
     * Add or update a cluster in the index.
     */
    suspend fun addCluster(clusterId: String, embedding: FloatArray) {
        if (index == null) {
            initialize(embedding.size)
        }

        try {
            index?.insert(clusterId, embedding)
            Log.v(TAG, "Added cluster $clusterId to index")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add cluster $clusterId", e)
        }
    }

    /**
     * Add or update a cluster from bytes.
     */
    suspend fun addCluster(clusterId: String, embeddingBytes: ByteArray) {
        val embedding = FaceConverters.byteArrayToFloatArray(embeddingBytes)
        addCluster(clusterId, embedding)
    }

    /**
     * Remove a cluster from the index.
     */
    suspend fun removeCluster(clusterId: String) {
        index?.remove(clusterId)
        Log.v(TAG, "Removed cluster $clusterId from index")
    }

    /**
     * Find the k nearest clusters to a given embedding.
     *
     * @param embedding The query face embedding
     * @param k Number of nearest clusters to find
     * @return List of (clusterId, similarity) pairs, sorted by similarity descending
     */
    suspend fun findNearestClusters(
        embedding: FloatArray,
        k: Int = 5
    ): List<ClusterMatch> {
        val idx = index ?: return emptyList()

        return try {
            val results = idx.search(embedding, k)
            results.map { result ->
                ClusterMatch(
                    clusterId = result.id,
                    similarity = 1f - result.distance, // Convert distance to similarity
                    distance = result.distance
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            emptyList()
        }
    }

    /**
     * Find clusters above a similarity threshold.
     *
     * @param embedding The query face embedding
     * @param minSimilarity Minimum cosine similarity (0-1)
     * @param maxResults Maximum number of results
     * @return List of matching clusters sorted by similarity descending
     */
    suspend fun findMatchingClusters(
        embedding: FloatArray,
        minSimilarity: Float,
        maxResults: Int = 10
    ): List<ClusterMatch> {
        val idx = index ?: return emptyList()

        return try {
            // Convert similarity threshold to distance threshold
            val maxDistance = 1f - minSimilarity
            val results = idx.searchWithThreshold(embedding, maxDistance, maxResults)
            results.map { result ->
                ClusterMatch(
                    clusterId = result.id,
                    similarity = 1f - result.distance,
                    distance = result.distance
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Threshold search failed", e)
            emptyList()
        }
    }

    /**
     * Find the best matching cluster for a face.
     *
     * @param embedding The face embedding
     * @param minSimilarity Minimum similarity to consider a match
     * @return Best matching cluster or null if none above threshold
     */
    suspend fun findBestMatch(
        embedding: FloatArray,
        minSimilarity: Float
    ): ClusterMatch? {
        val matches = findMatchingClusters(embedding, minSimilarity, 1)
        return matches.firstOrNull()
    }

    /**
     * Find potential merge candidates - clusters similar to a given cluster.
     *
     * @param clusterId The cluster to find merge candidates for
     * @param minSimilarity Minimum similarity for merge consideration
     * @return List of potential merge candidates
     */
    suspend fun findMergeCandidates(
        clusterId: String,
        minSimilarity: Float
    ): List<ClusterMatch> {
        val embedding = index?.getEmbedding(clusterId) ?: return emptyList()
        return findMatchingClusters(embedding, minSimilarity, 20)
            .filter { it.clusterId != clusterId }
    }

    /**
     * Save the index to disk.
     */
    suspend fun saveIndex() {
        index?.save(getIndexFile())
    }

    /**
     * Clear the index completely.
     */
    suspend fun clear() {
        index?.clear()
        getIndexFile().delete()
        Log.i(TAG, "Index cleared")
    }

    /**
     * Get the number of clusters in the index.
     */
    fun size(): Int = index?.size ?: 0

    /**
     * Check if the index is empty.
     */
    fun isEmpty(): Boolean = index?.isEmpty ?: true

    /**
     * Check if a cluster exists in the index.
     */
    fun contains(clusterId: String): Boolean = index?.contains(clusterId) ?: false

    /**
     * Get index statistics for debugging.
     */
    fun getStats(): HnswIndex.IndexStats? = index?.getStats()

    /**
     * Get all cluster IDs in the index.
     */
    fun getAllClusterIds(): Set<String> = index?.getAllIds() ?: emptySet()

    /**
     * Get the embedding for a cluster.
     */
    fun getEmbeddingForCluster(clusterId: String): FloatArray? = index?.getEmbedding(clusterId)

    /**
     * Update ef_search parameter for more/less accurate search.
     * Higher values = more accurate but slower.
     */
    fun setSearchAccuracy(efSearch: Int) {
        index?.setEfSearch(efSearch)
    }

    /**
     * Verify index integrity against database.
     *
     * @param dbClusterIds Set of cluster IDs from database
     * @return IndexIntegrityResult with discrepancies
     */
    fun verifyIntegrity(dbClusterIds: Set<String>): IndexIntegrityResult {
        val indexClusterIds = getAllClusterIds()

        val missingInIndex = dbClusterIds - indexClusterIds
        val orphanedInIndex = indexClusterIds - dbClusterIds

        return IndexIntegrityResult(
            isValid = missingInIndex.isEmpty() && orphanedInIndex.isEmpty(),
            missingInIndex = missingInIndex,
            orphanedInIndex = orphanedInIndex,
            indexSize = indexClusterIds.size,
            dbSize = dbClusterIds.size
        )
    }

    /**
     * Result of index integrity verification.
     */
    data class IndexIntegrityResult(
        val isValid: Boolean,
        val missingInIndex: Set<String>,
        val orphanedInIndex: Set<String>,
        val indexSize: Int,
        val dbSize: Int
    ) {
        val needsRebuild: Boolean
            get() = missingInIndex.size > indexSize * 0.1 || // More than 10% missing
                    orphanedInIndex.size > 10  // More than 10 orphaned
    }

    private fun getIndexFile(): File {
        val dir = File(context.filesDir, "face_index")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, INDEX_FILENAME)
    }

    /**
     * Result of a cluster search.
     */
    data class ClusterMatch(
        val clusterId: String,
        val similarity: Float,  // Cosine similarity (1 = identical)
        val distance: Float     // Cosine distance (0 = identical)
    )
}
