package com.aiezzy.slideshowmaker.face.clustering.index

import android.content.Context
import android.util.Log
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.entities.AnchorMatch
import com.aiezzy.slideshowmaker.data.face.entities.ClusterAnchorEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anchor embedding index for O(log n) anchor-based face matching.
 *
 * Unlike FaceEmbeddingIndex which indexes cluster centroids, this index
 * stores individual anchor embeddings. This is critical for the anchor-based
 * clustering system because:
 *
 * 1. Only high-quality (ANCHOR-tier) faces are indexed
 * 2. Each anchor maintains its identity (no averaging/centroid drift)
 * 3. Face matching is done against real faces, not synthetic centroids
 * 4. Multiple anchors per cluster provide pose diversity
 *
 * Key differences from centroid-based indexing:
 * - Centroids can drift over time as faces are added (averaging effect)
 * - Centroids don't represent any real face well ("uncanny valley" effect)
 * - Anchors are actual high-quality faces that provide reliable matching
 */
@Singleton
class AnchorEmbeddingIndex @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "AnchorEmbeddingIndex"
        private const val INDEX_FILENAME = "anchor_embedding_index.hnsw"

        // Default embedding dimensions (FaceNet = 512, MobileFaceNet = 192)
        private const val DEFAULT_DIMENSIONS = 512

        // HNSW parameters tuned for anchor matching
        // Higher M and efConstruction for better accuracy (anchors are quality faces)
        private const val M = 24                // Max connections per layer (higher than centroid index)
        private const val EF_CONSTRUCTION = 300 // Construction-time ef (higher for accuracy)
        private const val EF_SEARCH = 150       // Search-time ef (higher for accuracy)
    }

    private var index: HnswIndex? = null
    private var dimensions = DEFAULT_DIMENSIONS

    // Mapping from anchorId -> clusterId for quick lookup
    private val anchorToCluster = mutableMapOf<String, String>()

    // Mapping from anchorId -> poseCategory for pose-aware matching
    private val anchorToPose = mutableMapOf<String, String>()

    // Mapping from anchorId -> quality for weighted matching
    private val anchorToQuality = mutableMapOf<String, Float>()

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
                    Log.i(TAG, "Loaded anchor index from disk: ${index?.size} anchors")
                    // Note: anchorToCluster mappings need to be rebuilt from database
                } else {
                    Log.w(TAG, "Failed to load anchor index from disk, starting fresh")
                }
            }
        }
    }

    /**
     * Build the index from a list of anchors.
     * This replaces any existing index data.
     */
    suspend fun buildFromAnchors(anchors: List<ClusterAnchorEntity>) = withContext(Dispatchers.IO) {
        if (index == null) {
            initialize()
        }

        index?.clear()
        anchorToCluster.clear()
        anchorToPose.clear()
        anchorToQuality.clear()

        var added = 0
        var skipped = 0

        for (anchor in anchors) {
            if (!anchor.isActive) {
                skipped++
                continue
            }

            val embedding = FaceConverters.byteArrayToFloatArray(anchor.embedding)

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
                index?.insert(anchor.anchorId, embedding)
                anchorToCluster[anchor.anchorId] = anchor.clusterId
                anchorToPose[anchor.anchorId] = anchor.poseCategory
                anchorToQuality[anchor.anchorId] = anchor.qualityScore
                added++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add anchor ${anchor.anchorId}: ${e.message}")
                skipped++
            }
        }

        Log.i(TAG, "Built anchor index with $added anchors ($skipped skipped)")

        // Save to disk
        saveIndex()
    }

    /**
     * Add a single anchor to the index.
     */
    suspend fun addAnchor(anchor: ClusterAnchorEntity) {
        if (!anchor.isActive) return

        if (index == null) {
            val embedding = FaceConverters.byteArrayToFloatArray(anchor.embedding)
            initialize(embedding.size)
        }

        try {
            val embedding = FaceConverters.byteArrayToFloatArray(anchor.embedding)
            index?.insert(anchor.anchorId, embedding)
            anchorToCluster[anchor.anchorId] = anchor.clusterId
            anchorToPose[anchor.anchorId] = anchor.poseCategory
            anchorToQuality[anchor.anchorId] = anchor.qualityScore
            Log.v(TAG, "Added anchor ${anchor.anchorId} for cluster ${anchor.clusterId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add anchor ${anchor.anchorId}", e)
        }
    }

    /**
     * Add anchor from raw data (for incremental updates).
     */
    suspend fun addAnchor(
        anchorId: String,
        clusterId: String,
        embedding: FloatArray,
        poseCategory: String,
        qualityScore: Float
    ) {
        if (index == null) {
            initialize(embedding.size)
        }

        try {
            index?.insert(anchorId, embedding)
            anchorToCluster[anchorId] = clusterId
            anchorToPose[anchorId] = poseCategory
            anchorToQuality[anchorId] = qualityScore
            Log.v(TAG, "Added anchor $anchorId for cluster $clusterId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add anchor $anchorId", e)
        }
    }

    /**
     * Remove an anchor from the index.
     */
    suspend fun removeAnchor(anchorId: String) {
        index?.remove(anchorId)
        anchorToCluster.remove(anchorId)
        anchorToPose.remove(anchorId)
        anchorToQuality.remove(anchorId)
        Log.v(TAG, "Removed anchor $anchorId from index")
    }

    /**
     * Remove all anchors for a cluster (e.g., when cluster is deleted).
     */
    suspend fun removeClusterAnchors(clusterId: String) {
        val anchorsToRemove = anchorToCluster.entries
            .filter { it.value == clusterId }
            .map { it.key }

        for (anchorId in anchorsToRemove) {
            removeAnchor(anchorId)
        }
        Log.i(TAG, "Removed ${anchorsToRemove.size} anchors for cluster $clusterId")
    }

    /**
     * Find the k nearest anchors to a given embedding.
     *
     * @param embedding The query face embedding
     * @param k Number of nearest anchors to find
     * @return List of AnchorMatch sorted by similarity descending
     */
    suspend fun findNearestAnchors(
        embedding: FloatArray,
        k: Int = 10
    ): List<AnchorMatch> {
        val idx = index ?: return emptyList()

        return try {
            val results = idx.search(embedding, k)
            results.mapNotNull { result ->
                val clusterId = anchorToCluster[result.id] ?: return@mapNotNull null
                val poseCategory = anchorToPose[result.id] ?: "UNKNOWN"
                val quality = anchorToQuality[result.id] ?: 0f

                AnchorMatch(
                    clusterId = clusterId,
                    anchorId = result.id,
                    similarity = 1f - result.distance, // Convert distance to similarity
                    poseCategory = poseCategory,
                    anchorQuality = quality
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Anchor search failed", e)
            emptyList()
        }
    }

    /**
     * Find anchors above a similarity threshold.
     *
     * @param embedding The query face embedding
     * @param minSimilarity Minimum cosine similarity (0-1)
     * @param maxResults Maximum number of results
     * @return List of AnchorMatch within threshold, sorted by similarity descending
     */
    suspend fun findMatchingAnchors(
        embedding: FloatArray,
        minSimilarity: Float,
        maxResults: Int = 20
    ): List<AnchorMatch> {
        val idx = index ?: return emptyList()

        return try {
            val maxDistance = 1f - minSimilarity
            val results = idx.searchWithThreshold(embedding, maxDistance, maxResults)
            results.mapNotNull { result ->
                val clusterId = anchorToCluster[result.id] ?: return@mapNotNull null
                val poseCategory = anchorToPose[result.id] ?: "UNKNOWN"
                val quality = anchorToQuality[result.id] ?: 0f

                AnchorMatch(
                    clusterId = clusterId,
                    anchorId = result.id,
                    similarity = 1f - result.distance,
                    poseCategory = poseCategory,
                    anchorQuality = quality
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Threshold anchor search failed", e)
            emptyList()
        }
    }

    /**
     * Find the best matching anchor for a face.
     *
     * @param embedding The face embedding
     * @param minSimilarity Minimum similarity to consider a match
     * @return Best matching anchor or null if none above threshold
     */
    suspend fun findBestAnchorMatch(
        embedding: FloatArray,
        minSimilarity: Float
    ): AnchorMatch? {
        val matches = findMatchingAnchors(embedding, minSimilarity, 1)
        return matches.firstOrNull()
    }

    /**
     * Find all anchors for a specific cluster.
     *
     * @param clusterId The cluster to find anchors for
     * @return List of anchor IDs in this cluster
     */
    fun getAnchorIdsForCluster(clusterId: String): List<String> {
        return anchorToCluster.entries
            .filter { it.value == clusterId }
            .map { it.key }
    }

    /**
     * Get the cluster ID for an anchor.
     */
    fun getClusterIdForAnchor(anchorId: String): String? {
        return anchorToCluster[anchorId]
    }

    /**
     * Get all unique cluster IDs that have anchors in the index.
     */
    fun getAllClusterIds(): Set<String> {
        return anchorToCluster.values.toSet()
    }

    /**
     * Get anchor count per cluster.
     */
    fun getAnchorCountPerCluster(): Map<String, Int> {
        return anchorToCluster.values.groupingBy { it }.eachCount()
    }

    /**
     * Save the index to disk.
     */
    suspend fun saveIndex() {
        index?.save(getIndexFile())
        // Note: mappings are not persisted and need to be rebuilt from database
    }

    /**
     * Clear the index completely.
     */
    suspend fun clear() {
        index?.clear()
        anchorToCluster.clear()
        anchorToPose.clear()
        anchorToQuality.clear()
        getIndexFile().delete()
        Log.i(TAG, "Anchor index cleared")
    }

    /**
     * Get the number of anchors in the index.
     */
    fun size(): Int = index?.size ?: 0

    /**
     * Check if the index is empty.
     */
    fun isEmpty(): Boolean = index?.isEmpty ?: true

    /**
     * Check if an anchor exists in the index.
     */
    fun containsAnchor(anchorId: String): Boolean = index?.contains(anchorId) ?: false

    /**
     * Get index statistics for debugging.
     */
    fun getStats(): AnchorIndexStats {
        val baseStats = index?.getStats()
        val clusterCount = getAllClusterIds().size
        val poseDistribution = anchorToPose.values.groupingBy { it }.eachCount()

        return AnchorIndexStats(
            anchorCount = size(),
            clusterCount = clusterCount,
            dimensions = dimensions,
            poseDistribution = poseDistribution,
            avgAnchorsPerCluster = if (clusterCount > 0) size().toFloat() / clusterCount else 0f,
            hnswStats = baseStats
        )
    }

    /**
     * Rebuild anchor-to-cluster mappings from database.
     * Call this after loading index from disk.
     */
    fun rebuildMappings(anchors: List<ClusterAnchorEntity>) {
        anchorToCluster.clear()
        anchorToPose.clear()
        anchorToQuality.clear()

        for (anchor in anchors) {
            if (containsAnchor(anchor.anchorId)) {
                anchorToCluster[anchor.anchorId] = anchor.clusterId
                anchorToPose[anchor.anchorId] = anchor.poseCategory
                anchorToQuality[anchor.anchorId] = anchor.qualityScore
            }
        }

        Log.i(TAG, "Rebuilt mappings for ${anchorToCluster.size} anchors")
    }

    /**
     * Verify index integrity against database anchors.
     */
    fun verifyIntegrity(dbAnchors: List<ClusterAnchorEntity>): AnchorIndexIntegrityResult {
        val indexAnchorIds = index?.getAllIds() ?: emptySet()
        val dbActiveAnchorIds = dbAnchors.filter { it.isActive }.map { it.anchorId }.toSet()

        val missingInIndex = dbActiveAnchorIds - indexAnchorIds
        val orphanedInIndex = indexAnchorIds - dbActiveAnchorIds

        return AnchorIndexIntegrityResult(
            isValid = missingInIndex.isEmpty() && orphanedInIndex.isEmpty(),
            missingInIndex = missingInIndex,
            orphanedInIndex = orphanedInIndex,
            indexSize = indexAnchorIds.size,
            dbActiveSize = dbActiveAnchorIds.size
        )
    }

    private fun getIndexFile(): File {
        val dir = File(context.filesDir, "face_index")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, INDEX_FILENAME)
    }

    /**
     * Statistics about the anchor index.
     */
    data class AnchorIndexStats(
        val anchorCount: Int,
        val clusterCount: Int,
        val dimensions: Int,
        val poseDistribution: Map<String, Int>,
        val avgAnchorsPerCluster: Float,
        val hnswStats: HnswIndex.IndexStats?
    )

    /**
     * Result of anchor index integrity verification.
     */
    data class AnchorIndexIntegrityResult(
        val isValid: Boolean,
        val missingInIndex: Set<String>,
        val orphanedInIndex: Set<String>,
        val indexSize: Int,
        val dbActiveSize: Int
    ) {
        val needsRebuild: Boolean
            get() = missingInIndex.size > indexSize * 0.1 || // More than 10% missing
                    orphanedInIndex.size > 10  // More than 10 orphaned
    }
}
