package com.aiezzy.slideshowmaker.face

import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.aiezzy.slideshowmaker.data.face.FaceDatabase
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.dao.*
import com.aiezzy.slideshowmaker.data.face.entities.*
import com.aiezzy.slideshowmaker.face.clustering.ChineseWhispersClustering
import com.aiezzy.slideshowmaker.face.clustering.FaceClusteringService
import com.aiezzy.slideshowmaker.face.clustering.TwoPassClusteringService
import com.aiezzy.slideshowmaker.face.clustering.index.AnchorEmbeddingIndex
import com.aiezzy.slideshowmaker.face.clustering.index.FaceEmbeddingIndex
import com.aiezzy.slideshowmaker.face.detection.FaceDetectionService
import com.aiezzy.slideshowmaker.face.embedding.FaceEmbeddingGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for face-related operations.
 * Coordinates detection, embedding, clustering, and database operations.
 *
 * Key features:
 * - HNSW-based O(log n) cluster search for scalability
 * - Multiple representative embeddings per cluster for accuracy
 * - Cluster history tracking for undo support
 * - Must-link/Cannot-link constraints for user corrections
 * - Proper error handling with Result types
 * - Memory-efficient bitmap processing
 * - Quality-based face filtering
 * - Hilt dependency injection for testability
 */
@Singleton
class FaceRepository @Inject constructor(
    private val database: FaceDatabase,
    private val scannedPhotoDao: ScannedPhotoDao,
    private val detectedFaceDao: DetectedFaceDao,
    private val personDao: PersonDao,
    private val faceClusterDao: FaceClusterDao,
    private val scanProgressDao: ScanProgressDao,
    private val clusterRepresentativeDao: ClusterRepresentativeDao,
    private val clusterHistoryDao: ClusterHistoryDao,
    private val clusteringConstraintDao: ClusteringConstraintDao,
    private val stagedFaceDao: StagedFaceDao,
    private val clusterAnchorDao: ClusterAnchorDao,          // NEW: Anchor-based clustering
    private val clusterStatisticsDao: ClusterStatisticsDao,  // NEW: Adaptive thresholds
    private val detectionService: FaceDetectionService,
    private val embeddingGenerator: FaceEmbeddingGenerator,
    private val clusteringService: FaceClusteringService,
    private val twoPassClusteringService: TwoPassClusteringService,  // NEW: Two-pass clustering
    private val embeddingIndex: FaceEmbeddingIndex,
    private val anchorIndex: AnchorEmbeddingIndex  // NEW: Anchor embedding index
) {

    companion object {
        private const val TAG = "FaceRepository"
        private const val HISTORY_EXPIRY_DAYS = 30L
        private const val HISTORY_EXPIRY_MS = HISTORY_EXPIRY_DAYS * 24 * 60 * 60 * 1000L

        // Staging buffer configuration
        private const val MAX_STAGED_FACES = 50          // Flush to DB when reached
        private const val STAGING_FLUSH_INTERVAL_MS = 5000L  // Flush every 5 seconds
        private const val STAGING_TIMEOUT_MS = 60_000L   // Max time in staging
    }

    /**
     * NEW: Embedding source statistics for diagnostics.
     * Tracks which embedding model is being used during scanning.
     * Call logStats() at scan completion to see distribution.
     */
    object EmbeddingStats {
        private var facenetCount = 0
        private var mobilefacenetCount = 0
        private var hashFallbackCount = 0
        private var totalProcessed = 0
        private var alignmentSuccessCount = 0
        private var alignmentFailCount = 0
        private var lowQualityRejections = 0

        @Synchronized
        fun recordEmbedding(source: String, alignmentSuccessful: Boolean, alignmentQuality: Float) {
            totalProcessed++
            when (source) {
                "FACENET_512" -> facenetCount++
                "MOBILEFACENET_192" -> mobilefacenetCount++
                "HASH_FALLBACK" -> hashFallbackCount++
            }
            if (alignmentSuccessful) {
                alignmentSuccessCount++
            } else {
                alignmentFailCount++
            }
            // Track if alignment quality is below threshold
            if (alignmentQuality < 0.30f) {
                lowQualityRejections++
            }
        }

        @Synchronized
        fun reset() {
            facenetCount = 0
            mobilefacenetCount = 0
            hashFallbackCount = 0
            totalProcessed = 0
            alignmentSuccessCount = 0
            alignmentFailCount = 0
            lowQualityRejections = 0
        }

        @Synchronized
        fun logStats() {
            if (totalProcessed == 0) {
                Log.i("EmbeddingStats", "No embeddings processed yet")
                return
            }

            val facenetPct = (facenetCount * 100 / totalProcessed)
            val mobilefacenetPct = (mobilefacenetCount * 100 / totalProcessed)
            val hashPct = (hashFallbackCount * 100 / totalProcessed)
            val alignmentSuccessPct = (alignmentSuccessCount * 100 / totalProcessed)

            Log.i("EmbeddingStats", """
                |============ Embedding Source Distribution ============
                |FaceNet 512-dim:     $facenetCount ($facenetPct%)
                |MobileFaceNet 192-dim: $mobilefacenetCount ($mobilefacenetPct%)
                |Hash Fallback:       $hashFallbackCount ($hashPct%)
                |------------------------------------------------------
                |Total Processed:     $totalProcessed
                |Alignment Success:   $alignmentSuccessCount ($alignmentSuccessPct%)
                |Alignment Failed:    $alignmentFailCount
                |Low Quality Rejections: $lowQualityRejections
                |======================================================
            """.trimMargin())
        }

        /** Get stats summary as a data class for programmatic access */
        @Synchronized
        fun getStatsSummary(): StatsSummary {
            return StatsSummary(
                facenetCount = facenetCount,
                mobilefacenetCount = mobilefacenetCount,
                hashFallbackCount = hashFallbackCount,
                totalProcessed = totalProcessed,
                alignmentSuccessCount = alignmentSuccessCount,
                lowQualityRejections = lowQualityRejections
            )
        }

        data class StatsSummary(
            val facenetCount: Int,
            val mobilefacenetCount: Int,
            val hashFallbackCount: Int,
            val totalProcessed: Int,
            val alignmentSuccessCount: Int,
            val lowQualityRejections: Int
        )
    }

    // Thread safety mutexes
    private val indexMutex = Mutex()         // Protects HNSW index operations
    private val stagingMutex = Mutex()       // Protects staging buffer operations
    private var indexInitialized = false

    // In-memory staging buffer for uncertain faces
    private val stagingBuffer = mutableListOf<StagedFaceData>()
    private var lastStagingFlush = System.currentTimeMillis()

    /**
     * In-memory representation of a staged face.
     */
    data class StagedFaceData(
        val faceId: String,
        val embedding: FloatArray,
        val qualityScore: Float,
        val photoUri: String,
        val boundingBox: android.graphics.RectF,
        val imageWidth: Int,
        val imageHeight: Int,
        val candidateClusterId: String?,
        val candidateSimilarity: Float,
        val conflictingClusterId: String? = null,
        val conflictingSimilarity: Float = 0f,
        val timestamp: Long = System.currentTimeMillis(),
        val qualityBreakdown: QualityBreakdown? = null,
        val eulerY: Float? = null,
        val eulerZ: Float? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as StagedFaceData
            return faceId == other.faceId
        }
        override fun hashCode(): Int = faceId.hashCode()
    }

    /**
     * Quality score breakdown for detailed tracking.
     */
    data class QualityBreakdown(
        val sizeScore: Float,
        val poseScore: Float,
        val sharpnessScore: Float,
        val brightnessScore: Float,
        val eyeVisibilityScore: Float
    )

    // ============ Index Initialization ============

    /**
     * Initialize the HNSW embedding index from database clusters.
     * Should be called before processing photos.
     */
    suspend fun initializeIndex() = indexMutex.withLock {
        if (indexInitialized) return@withLock

        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Initializing face embedding index...")

                // Initialize the index
                embeddingIndex.initialize(loadFromDisk = true)

                // If index is empty, rebuild from database
                if (embeddingIndex.isEmpty()) {
                    val clusters = faceClusterDao.getAll()
                    if (clusters.isNotEmpty()) {
                        Log.i(TAG, "Rebuilding index from ${clusters.size} clusters")
                        embeddingIndex.buildFromClusters(clusters)
                    }
                }

                indexInitialized = true
                Log.i(TAG, "Index initialized with ${embeddingIndex.size()} clusters")

                // Fix any orphaned clusters (clusters without persons)
                // This ensures all clusters appear in the UI
                try {
                    val fixedCount = fixOrphanedClusters()
                    when {
                        fixedCount > 0 -> Log.i(TAG, "Fixed $fixedCount orphaned clusters during initialization")
                        else -> Log.d(TAG, "No orphaned clusters to fix")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fix orphaned clusters", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize index", e)
            }
        }
    }

    /**
     * Ensure index is initialized before use.
     */
    private suspend fun ensureIndexInitialized() {
        if (!indexInitialized) {
            initializeIndex()
        }
    }

    // ============ Staging Buffer Operations (Multi-Stage Clustering) ============

    /**
     * Add a face to the staging buffer for later verification.
     *
     * Used for faces in the uncertain zone (0.45-0.60 similarity).
     * These faces are held until batch verification can resolve conflicts.
     */
    suspend fun addToStagingBuffer(stagedFace: StagedFaceData) = stagingMutex.withLock {
        stagingBuffer.add(stagedFace)
        Log.d(TAG, "Added face ${stagedFace.faceId} to staging buffer (size: ${stagingBuffer.size})")

        // Check if we should flush
        val shouldFlush = stagingBuffer.size >= MAX_STAGED_FACES ||
                (System.currentTimeMillis() - lastStagingFlush) > STAGING_FLUSH_INTERVAL_MS

        if (shouldFlush) {
            flushStagingBufferInternal()
        }
    }

    /**
     * Get current staging buffer size.
     */
    suspend fun getStagingBufferSize(): Int = stagingMutex.withLock {
        stagingBuffer.size
    }

    /**
     * Force flush the staging buffer.
     * Verifies all staged faces and commits them to database.
     */
    suspend fun flushStagingBuffer() = stagingMutex.withLock {
        flushStagingBufferInternal()
    }

    /**
     * Internal flush implementation (must be called with stagingMutex held).
     */
    private suspend fun flushStagingBufferInternal() {
        if (stagingBuffer.isEmpty()) {
            lastStagingFlush = System.currentTimeMillis()
            return
        }

        Log.i(TAG, "Flushing staging buffer with ${stagingBuffer.size} faces")

        // Copy buffer and clear
        val facesToVerify = stagingBuffer.toList()
        stagingBuffer.clear()
        lastStagingFlush = System.currentTimeMillis()

        // Process each staged face
        withContext(Dispatchers.IO) {
            for (stagedFace in facesToVerify) {
                try {
                    verifyStagedFaceAndCommit(stagedFace)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to verify staged face ${stagedFace.faceId}", e)
                    // Add to rejected in DB for tracking
                    try {
                        stagedFaceDao.insert(stagedFace.toEntity(StagingStatus.REJECTED))
                    } catch (dbError: Exception) {
                        Log.e(TAG, "Failed to record rejected face", dbError)
                    }
                }
            }
        }
    }

    /**
     * Verify a single staged face and commit to database.
     */
    private suspend fun verifyStagedFaceAndCommit(stagedFace: StagedFaceData) {
        // Check timeout
        if (System.currentTimeMillis() - stagedFace.timestamp > STAGING_TIMEOUT_MS) {
            Log.w(TAG, "Staged face ${stagedFace.faceId} timed out, creating new cluster")
            commitFaceAsNewCluster(stagedFace)
            return
        }

        // If no candidate cluster, create new cluster
        if (stagedFace.candidateClusterId == null) {
            commitFaceAsNewCluster(stagedFace)
            return
        }

        // Get representatives for the candidate cluster
        val representatives = clusterRepresentativeDao.getByClusterId(stagedFace.candidateClusterId)
        val allClusters = faceClusterDao.getAll()

        // Use clustering service to verify
        val decision = clusteringService.verifyStagedFace(
            faceEmbedding = stagedFace.embedding,
            faceQuality = stagedFace.qualityScore,
            candidateClusterId = stagedFace.candidateClusterId,
            representatives = representatives,
            allClusters = allClusters
        )

        when {
            decision.canCommitImmediately && decision.bestClusterId != null -> {
                // Verified - commit to the cluster
                commitFaceToCluster(stagedFace, decision.bestClusterId, decision.bestSimilarity,
                    if (decision.confidence == FaceClusteringService.MatchConfidence.HIGH)
                        AssignmentConfidence.HIGH else AssignmentConfidence.MEDIUM
                )
            }
            decision.shouldCreateNewCluster || decision.bestClusterId == null -> {
                // Doesn't match - create new cluster
                commitFaceAsNewCluster(stagedFace)
            }
            else -> {
                // Still uncertain after verification - force decision based on best match
                if (decision.bestSimilarity >= FaceClusteringService.DEFINITELY_DIFFERENT &&
                    decision.bestClusterId != null) {
                    commitFaceToCluster(stagedFace, decision.bestClusterId, decision.bestSimilarity,
                        AssignmentConfidence.STAGED)
                } else {
                    commitFaceAsNewCluster(stagedFace)
                }
            }
        }
    }

    /**
     * Commit a staged face to an existing cluster.
     */
    private suspend fun commitFaceToCluster(
        stagedFace: StagedFaceData,
        clusterId: String,
        similarity: Float,
        confidence: String
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            // Create detected face entity
            val faceEntity = DetectedFaceEntity(
                faceId = stagedFace.faceId,
                photoUri = stagedFace.photoUri,
                boundingBoxLeft = stagedFace.boundingBox.left,
                boundingBoxTop = stagedFace.boundingBox.top,
                boundingBoxRight = stagedFace.boundingBox.right,
                boundingBoxBottom = stagedFace.boundingBox.bottom,
                embedding = FaceConverters.floatArrayToByteArray(stagedFace.embedding),
                confidence = stagedFace.qualityScore,  // Legacy field
                clusterId = clusterId,
                imageWidth = stagedFace.imageWidth,
                imageHeight = stagedFace.imageHeight,
                matchScore = similarity,
                qualityScore = stagedFace.qualityScore,
                sizeScore = stagedFace.qualityBreakdown?.sizeScore ?: 0f,
                poseScore = stagedFace.qualityBreakdown?.poseScore ?: 0f,
                sharpnessScore = stagedFace.qualityBreakdown?.sharpnessScore ?: 0f,
                brightnessScore = stagedFace.qualityBreakdown?.brightnessScore ?: 0f,
                eyeVisibilityScore = stagedFace.qualityBreakdown?.eyeVisibilityScore ?: 0f,
                assignmentConfidence = confidence,
                verifiedAt = System.currentTimeMillis(),
                embeddingQuality = stagedFace.qualityScore / 100f,
                eulerY = stagedFace.eulerY,
                eulerZ = stagedFace.eulerZ
            )

            detectedFaceDao.insert(faceEntity)

            // Update cluster centroid only if high quality
            if (confidence == AssignmentConfidence.HIGH ||
                (confidence == AssignmentConfidence.MEDIUM &&
                        stagedFace.qualityScore >= FaceClusteringService.MIN_QUALITY_FOR_CENTROID * 100)) {
                updateClusterCentroid(clusterId, stagedFace.embedding, similarity)
            }

            Log.d(TAG, "Committed staged face ${stagedFace.faceId} to cluster $clusterId " +
                    "(similarity: ${"%.3f".format(similarity)}, confidence: $confidence)")
        }
    }

    /**
     * Commit a staged face as a new cluster.
     */
    private suspend fun commitFaceAsNewCluster(stagedFace: StagedFaceData) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val clusterId = UUID.randomUUID().toString()
            val personId = UUID.randomUUID().toString()

            // Create person first
            val person = PersonEntity(
                personId = personId,
                name = null,
                representativeFaceId = stagedFace.faceId,
                photoCount = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            personDao.insert(person)

            // Create cluster
            val cluster = FaceClusterEntity(
                clusterId = clusterId,
                personId = personId,
                centroidEmbedding = FaceConverters.floatArrayToByteArray(stagedFace.embedding),
                faceCount = 1,
                createdAt = System.currentTimeMillis()
            )
            faceClusterDao.insert(cluster)

            // Create face entity
            val faceEntity = DetectedFaceEntity(
                faceId = stagedFace.faceId,
                photoUri = stagedFace.photoUri,
                boundingBoxLeft = stagedFace.boundingBox.left,
                boundingBoxTop = stagedFace.boundingBox.top,
                boundingBoxRight = stagedFace.boundingBox.right,
                boundingBoxBottom = stagedFace.boundingBox.bottom,
                embedding = FaceConverters.floatArrayToByteArray(stagedFace.embedding),
                confidence = stagedFace.qualityScore,
                clusterId = clusterId,
                imageWidth = stagedFace.imageWidth,
                imageHeight = stagedFace.imageHeight,
                matchScore = 1.0f,  // First face in cluster
                qualityScore = stagedFace.qualityScore,
                sizeScore = stagedFace.qualityBreakdown?.sizeScore ?: 0f,
                poseScore = stagedFace.qualityBreakdown?.poseScore ?: 0f,
                sharpnessScore = stagedFace.qualityBreakdown?.sharpnessScore ?: 0f,
                brightnessScore = stagedFace.qualityBreakdown?.brightnessScore ?: 0f,
                eyeVisibilityScore = stagedFace.qualityBreakdown?.eyeVisibilityScore ?: 0f,
                assignmentConfidence = AssignmentConfidence.NEW,
                verifiedAt = System.currentTimeMillis(),
                embeddingQuality = stagedFace.qualityScore / 100f,
                eulerY = stagedFace.eulerY,
                eulerZ = stagedFace.eulerZ
            )
            detectedFaceDao.insert(faceEntity)

            // Add to index
            try {
                indexMutex.withLock {
                    embeddingIndex.addCluster(clusterId, stagedFace.embedding)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add new cluster to index", e)
            }

            Log.d(TAG, "Created new cluster $clusterId for staged face ${stagedFace.faceId}")
        }
    }

    /**
     * Update cluster centroid with new embedding.
     */
    private suspend fun updateClusterCentroid(clusterId: String, newEmbedding: FloatArray, similarity: Float) {
        val cluster = faceClusterDao.getById(clusterId) ?: return
        val existingCentroid = cluster.centroidEmbedding?.let { FaceConverters.byteArrayToFloatArray(it) }
            ?: return

        // Weight based on similarity
        val weight = if (similarity >= FaceClusteringService.DEFINITE_SAME_PERSON) 1.0f else 0.5f
        val newCount = cluster.faceCount + 1

        // Running average: new_centroid = (old_centroid * count + new_embedding * weight) / (count + weight)
        val updatedCentroid = FloatArray(existingCentroid.size)
        val totalWeight = cluster.faceCount + weight

        for (i in existingCentroid.indices) {
            updatedCentroid[i] = (existingCentroid[i] * cluster.faceCount + newEmbedding[i] * weight) / totalWeight
        }

        // Normalize
        var norm = 0f
        for (v in updatedCentroid) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (i in updatedCentroid.indices) updatedCentroid[i] /= norm
        }

        // Update in database
        faceClusterDao.updateCentroid(clusterId, FaceConverters.floatArrayToByteArray(updatedCentroid))
        faceClusterDao.updateFaceCount(clusterId, newCount)

        // Update in index (remove old and add new since index doesn't support update)
        try {
            indexMutex.withLock {
                embeddingIndex.removeCluster(clusterId)
                embeddingIndex.addCluster(clusterId, updatedCentroid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update cluster in index", e)
        }
    }

    /**
     * Convert StagedFaceData to database entity.
     */
    private fun StagedFaceData.toEntity(status: String = StagingStatus.PENDING) = StagedFaceEntity(
        faceId = faceId,
        embedding = FaceConverters.floatArrayToByteArray(embedding),
        qualityScore = qualityScore,
        photoUri = photoUri,
        createdAt = timestamp,
        candidateClusterId = candidateClusterId,
        candidateSimilarity = candidateSimilarity,
        conflictingClusterId = conflictingClusterId,
        conflictingSimilarity = conflictingSimilarity,
        status = status,
        boundingBoxLeft = boundingBox.left,
        boundingBoxTop = boundingBox.top,
        boundingBoxRight = boundingBox.right,
        boundingBoxBottom = boundingBox.bottom,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        sizeScore = qualityBreakdown?.sizeScore ?: 0f,
        poseScore = qualityBreakdown?.poseScore ?: 0f,
        sharpnessScore = qualityBreakdown?.sharpnessScore ?: 0f,
        brightnessScore = qualityBreakdown?.brightnessScore ?: 0f,
        eyeVisibilityScore = qualityBreakdown?.eyeVisibilityScore ?: 0f,
        eulerY = eulerY,
        eulerZ = eulerZ
    )

    // ============ Scan Progress Operations ============

    fun getScanProgressFlow(): Flow<ScanProgressEntity?> = scanProgressDao.getProgressFlow()

    suspend fun getScanProgress(): ScanProgressEntity? = scanProgressDao.getProgress()

    suspend fun startNewScan(totalPhotos: Int) {
        // Initialize index before starting scan
        initializeIndex()
        scanProgressDao.startNewScan(totalPhotos)
    }

    suspend fun markScanComplete() {
        scanProgressDao.markComplete()
        // Save index to disk after scan completes
        try {
            embeddingIndex.saveIndex()
            Log.i(TAG, "Saved embedding index to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save embedding index", e)
        }
    }

    // ============ Photo Scanning Operations ============

    suspend fun addPhotosToScan(photos: List<ScannedPhotoEntity>) {
        scannedPhotoDao.insertAll(photos)
    }

    suspend fun isPhotoScanned(uri: String): Boolean {
        return scannedPhotoDao.exists(uri)
    }

    suspend fun getPendingPhotos(limit: Int = 100): List<ScannedPhotoEntity> {
        return scannedPhotoDao.getPendingPhotos(limit)
    }

    /**
     * Mark a photo as failed permanently.
     */
    suspend fun markPhotoFailed(photoUri: String, reason: String) {
        scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
        Log.w(TAG, "Photo marked as failed: $photoUri - $reason")
    }

    /**
     * Mark a photo for retry by resetting its status to PENDING.
     */
    suspend fun markPhotoForRetry(photoUri: String) {
        scannedPhotoDao.resetToPending(photoUri)
        Log.d(TAG, "Photo marked for retry: $photoUri")
    }

    /**
     * Process a single photo: detect faces, generate embeddings, cluster.
     *
     * Features:
     * - HNSW-based O(log n) cluster search for scalability
     * - Multiple representatives per cluster for accuracy
     * - Must-link/Cannot-link constraint enforcement
     * - Quality filtering to skip low-quality faces
     * - Proper bitmap recycling to prevent memory leaks
     * - Memory-safe processing with guaranteed cleanup
     */
    suspend fun processPhoto(photoUri: String): ProcessingResult = withContext(Dispatchers.IO) {
        // Ensure index is ready
        ensureIndexInitialized()

        // Track all bitmaps for guaranteed cleanup
        val allBitmaps = mutableListOf<android.graphics.Bitmap>()

        try {
            val uri = Uri.parse(photoUri)

            // Detect faces with quality metrics
            val detectionResult = detectionService.detectFaces(uri)
            if (detectionResult.isFailure) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
                return@withContext ProcessingResult.Error(
                    "Detection failed: ${detectionResult.exceptionOrNull()?.message}"
                )
            }

            val detectedFaces = detectionResult.getOrNull() ?: emptyList()
            if (detectedFaces.isEmpty()) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.NO_FACES, System.currentTimeMillis())
                return@withContext ProcessingResult.NoFaces
            }

            // Collect all bitmaps upfront for guaranteed cleanup
            detectedFaces.forEach { face ->
                face.faceBitmap?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        allBitmaps.add(bitmap)
                    }
                }
            }

            var newClustersCreated = 0
            val processedFaces = mutableListOf<DetectedFaceEntity>()

            for (detectedFace in detectedFaces) {
                // Skip faces not suitable for embedding
                if (!detectedFace.isSuitableForEmbedding) {
                    Log.v(TAG, "Skipping low-quality face: quality=${detectedFace.quality.overallScore}")
                    continue
                }

                val faceBitmap = detectedFace.faceBitmap
                if (faceBitmap == null || faceBitmap.isRecycled) {
                    continue
                }

                // Generate embedding with landmarks for alignment
                val landmarks = if (detectedFace.leftEye != null && detectedFace.rightEye != null) {
                    FaceEmbeddingGenerator.FaceLandmarks(
                        leftEyeX = detectedFace.leftEye.x,
                        leftEyeY = detectedFace.leftEye.y,
                        rightEyeX = detectedFace.rightEye.x,
                        rightEyeY = detectedFace.rightEye.y,
                        noseX = detectedFace.nose?.x,
                        noseY = detectedFace.nose?.y,
                        leftMouthX = detectedFace.leftMouth?.x,
                        leftMouthY = detectedFace.leftMouth?.y,
                        rightMouthX = detectedFace.rightMouth?.x,
                        rightMouthY = detectedFace.rightMouth?.y
                    )
                } else {
                    null
                }

                val embedding = embeddingGenerator.generateEmbedding(faceBitmap, landmarks)

                // Get cannot-link constraints for this face
                val cannotLinkFaceIds = clusteringConstraintDao.getCannotLinkFaceIds(detectedFace.faceId)
                    .toSet()

                // CRITICAL: Use mutex to prevent race condition during parallel processing
                // The entire operation (check index → decide cluster → update index) must be atomic
                // Without this, parallel threads can create duplicate clusters for the same person
                val clusterResult = indexMutex.withLock {
                    val result = if (embedding != null) {
                        clusteringService.clusterFaceWithIndex(
                            faceEmbedding = embedding,
                            faceQuality = detectedFace.quality.overallScore,
                            embeddingIndex = embeddingIndex,
                            representativesProvider = { clusterId ->
                                clusterRepresentativeDao.getRepresentativesForCluster(clusterId)
                            },
                            cannotLinkFaceIds = cannotLinkFaceIds
                        )
                    } else {
                        // Create new cluster for faces without embeddings
                        FaceClusteringService.EnhancedClusterResult(
                            clusterId = UUID.randomUUID().toString(),
                            isNewCluster = true,
                            similarity = 0f,
                            confidence = FaceClusteringService.ClusterResult.ClusterConfidence.NEW_CLUSTER,
                            matchedRepresentativeId = null,
                            candidatesChecked = 0
                        )
                    }

                    // IMPORTANT: Update index IMMEDIATELY for new clusters while still holding mutex
                    // This prevents race condition where another thread creates duplicate cluster
                    if (result.isNewCluster && embedding != null) {
                        embeddingIndex.addCluster(result.clusterId, embedding)
                    }

                    result
                }

                // Create face entity with match score for thumbnail selection
                // matchScore indicates how well this face matches its cluster (higher = better match)
                // For new clusters, the face is the seed so matchScore is 1.0 (perfect match to itself)
                val matchScore = if (clusterResult.isNewCluster) 1.0f else clusterResult.similarity

                val faceEntity = DetectedFaceEntity(
                    faceId = detectedFace.faceId,
                    photoUri = photoUri,
                    boundingBoxLeft = detectedFace.boundingBox.left,
                    boundingBoxTop = detectedFace.boundingBox.top,
                    boundingBoxRight = detectedFace.boundingBox.right,
                    boundingBoxBottom = detectedFace.boundingBox.bottom,
                    embedding = embedding?.let { FaceConverters.floatArrayToByteArray(it) },
                    confidence = detectedFace.quality.overallScore,
                    clusterId = clusterResult.clusterId,
                    imageWidth = detectedFace.imageWidth,
                    imageHeight = detectedFace.imageHeight,
                    matchScore = matchScore
                )

                // Save face to database
                detectedFaceDao.insert(faceEntity)
                processedFaces.add(faceEntity)

                // Update or create cluster
                if (clusterResult.isNewCluster) {
                    val embeddingBytes = embedding?.let { FaceConverters.floatArrayToByteArray(it) }

                    // Use atomic transaction: create person first, then cluster with personId already set
                    // This prevents orphaned clusters (clusters without persons)
                    database.withTransaction {
                        // Create person first
                        val displayNumber = personDao.getNextDisplayNumber()
                        val person = PersonEntity(
                            personId = UUID.randomUUID().toString(),
                            name = null,
                            representativeFaceId = faceEntity.faceId,
                            photoCount = 1,
                            isHidden = false,
                            createdAt = System.currentTimeMillis(),
                            displayNumber = displayNumber
                        )
                        personDao.insert(person)

                        // Create cluster WITH personId already set (not null)
                        val newCluster = FaceClusterEntity(
                            clusterId = clusterResult.clusterId,
                            personId = person.personId,  // Set personId immediately
                            centroidEmbedding = embeddingBytes,
                            faceCount = 1,
                            createdAt = System.currentTimeMillis()
                        )
                        faceClusterDao.insert(newCluster)

                        Log.d(TAG, "Created cluster ${newCluster.clusterId} with person ${person.personId} atomically")
                    }
                    newClustersCreated++

                    // Note: Index was already updated inside mutex to prevent race condition

                    // Create first representative for new cluster (with pose info)
                    if (embeddingBytes != null) {
                        val eulerY = detectedFace.quality.eulerY ?: 0f
                        val eulerZ = detectedFace.quality.eulerZ ?: 0f
                        val poseCategory = FaceClusteringService.PoseCategory.fromEulerY(eulerY)

                        val representative = ClusterRepresentativeEntity(
                            id = UUID.randomUUID().toString(),
                            clusterId = clusterResult.clusterId,
                            faceId = faceEntity.faceId,
                            embedding = embeddingBytes,
                            qualityScore = detectedFace.quality.overallScore,
                            rank = 0,  // First representative is rank 0
                            eulerY = eulerY,
                            eulerZ = eulerZ,
                            poseCategory = poseCategory.name,
                            createdAt = System.currentTimeMillis()
                        )
                        clusterRepresentativeDao.insert(representative)
                    }

                    // Record history for undo support
                    recordClusterHistory(
                        operation = ClusterOperation.CREATE,
                        clusterId = clusterResult.clusterId,
                        faceId = faceEntity.faceId
                    )
                } else {
                    // Update existing cluster
                    val existingCluster = faceClusterDao.getById(clusterResult.clusterId)
                    if (existingCluster != null && embedding != null) {
                        val embeddingBytes = FaceConverters.floatArrayToByteArray(embedding)

                        // Update centroid for ALL matches, with weight based on confidence level
                        // HIGH confidence: full weight (1.0)
                        // MEDIUM confidence: partial weight (0.5) - still contributes but less influence
                        // This prevents centroid drift while still improving accuracy over time
                        val centroidWeight = when (clusterResult.confidence) {
                            FaceClusteringService.ClusterResult.ClusterConfidence.HIGH -> 1.0f
                            FaceClusteringService.ClusterResult.ClusterConfidence.MEDIUM -> 0.5f
                            else -> 0.0f
                        }

                        val updatedCentroid = if (centroidWeight > 0f) {
                            calculateWeightedCentroidUpdate(
                                currentCentroidBytes = existingCluster.centroidEmbedding,
                                newEmbedding = embedding,
                                currentSize = existingCluster.faceCount,
                                weight = centroidWeight
                            )
                        } else {
                            existingCluster.centroidEmbedding
                        }

                        val updatedCluster = existingCluster.copy(
                            centroidEmbedding = updatedCentroid,
                            faceCount = existingCluster.faceCount + 1,
                            updatedAt = System.currentTimeMillis()
                        )
                        faceClusterDao.update(updatedCluster)

                        // Update HNSW index with new centroid for any centroid change
                        if (centroidWeight > 0f && updatedCentroid != null) {
                            val newCentroidFloats = FaceConverters.byteArrayToFloatArray(updatedCentroid)
                            embeddingIndex.addCluster(clusterResult.clusterId, newCentroidFloats)
                        }

                        // Consider adding as new representative (with pose diversity)
                        maybeAddRepresentative(
                            clusterId = clusterResult.clusterId,
                            faceId = faceEntity.faceId,
                            embedding = embeddingBytes,
                            qualityScore = detectedFace.quality.overallScore,
                            eulerY = detectedFace.quality.eulerY,
                            eulerZ = detectedFace.quality.eulerZ
                        )

                        // Update person photo count
                        existingCluster.personId?.let { personId ->
                            updatePersonPhotoCount(personId)
                        }
                    }
                }
            }

            // Update photo status
            scannedPhotoDao.updateStatus(photoUri, ScanStatus.SCANNED, System.currentTimeMillis())

            // Update scan progress
            scanProgressDao.incrementProgress(photoUri, processedFaces.size)

            ProcessingResult.Success(processedFaces.size, newClustersCreated)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing photo: $photoUri", e)
            scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
            ProcessingResult.Error(e.message ?: "Unknown error")
        } finally {
            // CRITICAL: Always recycle ALL bitmaps to prevent memory leaks
            allBitmaps.forEach { bitmap ->
                try {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error recycling bitmap", e)
                }
            }
            allBitmaps.clear()
        }
    }

    // ============================================================================
    // ANCHOR-BASED TWO-PASS CLUSTERING SYSTEM
    // ============================================================================

    /**
     * Feature flag to enable anchor-based clustering.
     * Set to true to use the new two-pass system, false for legacy centroid-based.
     */
    private var useAnchorBasedClustering = true

    /**
     * Process a photo using the anchor-based two-pass clustering system.
     *
     * This is the production-grade clustering approach that:
     * 1. Only uses high-quality ANCHOR faces to define cluster identity
     * 2. Uses three-zone decision model (SAFE_SAME/UNCERTAIN/SAFE_DIFFERENT)
     * 3. Runs Pass 1 for high precision, Pass 2 for controlled recall
     * 4. Never matches face-to-face, only face-to-anchor
     *
     * @param photoUri The URI of the photo to process
     * @param photoTimestamp Optional timestamp for temporal context
     * @return ProcessingResult with success/failure info
     */
    suspend fun processPhotoWithAnchorClustering(
        photoUri: String,
        photoTimestamp: Long? = null
    ): ProcessingResult = withContext(Dispatchers.IO) {
        // Ensure anchor index is initialized
        ensureAnchorIndexInitialized()

        // Track all bitmaps for guaranteed cleanup
        val allBitmaps = mutableListOf<android.graphics.Bitmap>()

        try {
            val uri = Uri.parse(photoUri)

            // Detect faces with quality metrics
            val detectionResult = detectionService.detectFaces(uri)
            if (detectionResult.isFailure) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
                return@withContext ProcessingResult.Error(
                    "Detection failed: ${detectionResult.exceptionOrNull()?.message}"
                )
            }

            val detectedFaces = detectionResult.getOrNull() ?: emptyList()
            if (detectedFaces.isEmpty()) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.NO_FACES, System.currentTimeMillis())
                return@withContext ProcessingResult.NoFaces
            }

            // Collect all bitmaps upfront for guaranteed cleanup
            detectedFaces.forEach { face ->
                face.faceBitmap?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        allBitmaps.add(bitmap)
                    }
                }
            }

            // Convert detected faces to FaceData for two-pass clustering
            val faceDataList = mutableListOf<TwoPassClusteringService.FaceData>()

            for (detectedFace in detectedFaces) {
                // Skip faces not suitable for embedding
                if (!detectedFace.isSuitableForEmbedding) {
                    Log.v(TAG, "Skipping low-quality face: quality=${detectedFace.quality.overallScore}")
                    continue
                }

                val faceBitmap = detectedFace.faceBitmap
                if (faceBitmap == null || faceBitmap.isRecycled) {
                    continue
                }

                // Generate embedding with landmarks for alignment
                val landmarks = if (detectedFace.leftEye != null && detectedFace.rightEye != null) {
                    FaceEmbeddingGenerator.FaceLandmarks(
                        leftEyeX = detectedFace.leftEye.x,
                        leftEyeY = detectedFace.leftEye.y,
                        rightEyeX = detectedFace.rightEye.x,
                        rightEyeY = detectedFace.rightEye.y,
                        noseX = detectedFace.nose?.x,
                        noseY = detectedFace.nose?.y,
                        leftMouthX = detectedFace.leftMouth?.x,
                        leftMouthY = detectedFace.leftMouth?.y,
                        rightMouthX = detectedFace.rightMouth?.x,
                        rightMouthY = detectedFace.rightMouth?.y
                    )
                } else {
                    null
                }

                val embedding = embeddingGenerator.generateEmbedding(faceBitmap, landmarks)
                if (embedding == null) {
                    Log.w(TAG, "Failed to generate embedding for face ${detectedFace.faceId}")
                    continue
                }

                faceDataList.add(
                    TwoPassClusteringService.FaceData(
                        faceId = detectedFace.faceId,
                        embedding = embedding,
                        qualityScore = detectedFace.quality.overallScore,
                        sharpnessScore = detectedFace.quality.sharpnessScore,
                        eyeVisibilityScore = detectedFace.quality.eyeVisibilityScore,
                        eulerY = detectedFace.quality.eulerY,
                        eulerZ = detectedFace.quality.eulerZ,
                        photoUri = photoUri,
                        photoTimestamp = photoTimestamp
                    )
                )
            }

            if (faceDataList.isEmpty()) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.NO_FACES, System.currentTimeMillis())
                return@withContext ProcessingResult.NoFaces
            }

            // CRITICAL: Create CANNOT_LINK constraints for all faces in the same photo
            // Faces in the same photo are ALWAYS different people - this prevents wrong merges
            if (faceDataList.size >= 2) {
                val samePhotoConstraints = createSamePhotoConstraints(faceDataList)
                Log.d(TAG, "Created ${samePhotoConstraints.size} same-photo CANNOT_LINK constraints for $photoUri")
            }

            // Collect existing cannot-link constraints for constraint-aware clustering
            val faceIds = faceDataList.map { it.faceId }
            val cannotLinkMap = getCannotLinkMapForFaces(faceIds)

            // Run Pass 1 (High Precision) clustering with constraint awareness
            val pass1Result = indexMutex.withLock {
                twoPassClusteringService.processPhotoFaces(faceDataList, cannotLinkMap = cannotLinkMap)
            }

            var newClustersCreated = 0
            val processedFaces = mutableListOf<DetectedFaceEntity>()

            // Process assigned faces from Pass 1
            database.withTransaction {
                // Handle new clusters
                for (newClusterId in pass1Result.newClusters) {
                    val assignedFaceId = pass1Result.assignedFaces.entries
                        .find { it.value == newClusterId }?.key

                    if (assignedFaceId != null) {
                        val faceData = faceDataList.find { it.faceId == assignedFaceId }
                        if (faceData != null) {
                            // Create person and cluster atomically
                            val displayNumber = personDao.getNextDisplayNumber()
                            val person = PersonEntity(
                                personId = UUID.randomUUID().toString(),
                                name = null,
                                representativeFaceId = assignedFaceId,
                                photoCount = 1,
                                isHidden = false,
                                createdAt = System.currentTimeMillis(),
                                displayNumber = displayNumber
                            )
                            personDao.insert(person)

                            val embeddingBytes = FaceConverters.floatArrayToByteArray(faceData.embedding)
                            val newCluster = FaceClusterEntity(
                                clusterId = newClusterId,
                                personId = person.personId,
                                centroidEmbedding = embeddingBytes,
                                faceCount = 1,
                                createdAt = System.currentTimeMillis()
                            )
                            faceClusterDao.insert(newCluster)

                            Log.d(TAG, "Created anchor cluster $newClusterId with person ${person.personId}")
                            newClustersCreated++
                        }
                    }
                }

                // Insert anchors from Pass 1
                for (anchor in pass1Result.newAnchors) {
                    clusterAnchorDao.insert(anchor)
                    // Update anchor index
                    val anchorEmbedding = FaceConverters.byteArrayToFloatArray(anchor.embedding)
                    anchorIndex.addAnchor(
                        anchorId = anchor.anchorId,
                        clusterId = anchor.clusterId,
                        embedding = anchorEmbedding,
                        poseCategory = anchor.poseCategory,
                        qualityScore = anchor.qualityScore
                    )
                }

                // Save all assigned faces to database
                for ((faceId, clusterId) in pass1Result.assignedFaces) {
                    val faceData = faceDataList.find { it.faceId == faceId } ?: continue
                    val detectedFace = detectedFaces.find { it.faceId == faceId } ?: continue

                    val faceEntity = DetectedFaceEntity(
                        faceId = faceId,
                        photoUri = photoUri,
                        boundingBoxLeft = detectedFace.boundingBox.left,
                        boundingBoxTop = detectedFace.boundingBox.top,
                        boundingBoxRight = detectedFace.boundingBox.right,
                        boundingBoxBottom = detectedFace.boundingBox.bottom,
                        embedding = FaceConverters.floatArrayToByteArray(faceData.embedding),
                        confidence = faceData.qualityScore,
                        clusterId = clusterId,
                        imageWidth = detectedFace.imageWidth,
                        imageHeight = detectedFace.imageHeight,
                        matchScore = 1.0f  // High confidence for Pass 1 assignments
                    )
                    detectedFaceDao.insert(faceEntity)
                    processedFaces.add(faceEntity)

                    // Update cluster face count
                    val cluster = faceClusterDao.getById(clusterId)
                    if (cluster != null && clusterId !in pass1Result.newClusters) {
                        faceClusterDao.update(cluster.copy(
                            faceCount = cluster.faceCount + 1,
                            updatedAt = System.currentTimeMillis()
                        ))
                        // Update person photo count
                        cluster.personId?.let { updatePersonPhotoCount(it) }
                    }

                    // Record history
                    recordClusterHistory(
                        operation = if (clusterId in pass1Result.newClusters) ClusterOperation.CREATE else ClusterOperation.MOVE_FACE,
                        clusterId = clusterId,
                        faceId = faceId
                    )
                }

                // Handle display-only faces (visible but not clustered)
                for (faceId in pass1Result.displayOnlyFaces) {
                    val detectedFace = detectedFaces.find { it.faceId == faceId } ?: continue
                    val faceData = faceDataList.find { it.faceId == faceId }

                    val faceEntity = DetectedFaceEntity(
                        faceId = faceId,
                        photoUri = photoUri,
                        boundingBoxLeft = detectedFace.boundingBox.left,
                        boundingBoxTop = detectedFace.boundingBox.top,
                        boundingBoxRight = detectedFace.boundingBox.right,
                        boundingBoxBottom = detectedFace.boundingBox.bottom,
                        embedding = faceData?.embedding?.let { FaceConverters.floatArrayToByteArray(it) },
                        confidence = detectedFace.quality.overallScore,
                        clusterId = null,  // Not assigned to any cluster
                        imageWidth = detectedFace.imageWidth,
                        imageHeight = detectedFace.imageHeight,
                        matchScore = 0f
                    )
                    detectedFaceDao.insert(faceEntity)
                }
            }

            // Update cluster statistics for affected clusters
            val affectedClusterIds = pass1Result.assignedFaces.values.toSet()
            twoPassClusteringService.updateClusterStatistics(affectedClusterIds.toList())

            // Update photo status
            scannedPhotoDao.updateStatus(photoUri, ScanStatus.SCANNED, System.currentTimeMillis())

            // Update scan progress
            scanProgressDao.incrementProgress(photoUri, processedFaces.size)

            Log.i(TAG, "Anchor clustering: ${processedFaces.size} faces processed, " +
                    "${newClustersCreated} new clusters, " +
                    "${pass1Result.deferredFaces.size} deferred")

            ProcessingResult.Success(processedFaces.size, newClustersCreated)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing photo with anchor clustering: $photoUri", e)
            scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
            ProcessingResult.Error(e.message ?: "Unknown error")
        } finally {
            // CRITICAL: Always recycle ALL bitmaps to prevent memory leaks
            allBitmaps.forEach { bitmap ->
                try {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error recycling bitmap", e)
                }
            }
            allBitmaps.clear()
        }
    }


    /**
     * Process a photo for DETECTION ONLY - no clustering is performed.
     *
     * This method:
     * 1. Detects faces in the photo
     * 2. Generates embeddings for each face
     * 3. Stores faces in DB with clusterId = null
     * 4. Marks photo as scanned
     *
     * Clustering is performed later via BatchClusteringService after ALL photos are scanned.
     *
     * @param photoUri The URI of the photo to process
     * @return ProcessingResult with detected face count
     */
    suspend fun processPhotoDetectionOnly(
        photoUri: String
    ): ProcessingResult = withContext(Dispatchers.IO) {
        val allBitmaps = mutableListOf<android.graphics.Bitmap>()

        try {
            val uri = Uri.parse(photoUri)

            val detectionResult = detectionService.detectFaces(uri)
            if (detectionResult.isFailure) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
                return@withContext ProcessingResult.Error(
                    "Detection failed: ${detectionResult.exceptionOrNull()?.message}"
                )
            }

            val detectedFaces = detectionResult.getOrNull() ?: emptyList()
            if (detectedFaces.isEmpty()) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.NO_FACES, System.currentTimeMillis())
                return@withContext ProcessingResult.NoFaces
            }

            detectedFaces.forEach { face ->
                face.faceBitmap?.let { bitmap ->
                    if (!bitmap.isRecycled) allBitmaps.add(bitmap)
                }
            }

            var savedFaces = 0

            database.withTransaction {
                for (detectedFace in detectedFaces) {
                    if (!detectedFace.isSuitableForEmbedding) continue

                    val faceBitmap = detectedFace.faceBitmap
                    if (faceBitmap == null || faceBitmap.isRecycled) continue

                    val landmarks = if (detectedFace.leftEye != null && detectedFace.rightEye != null) {
                        FaceEmbeddingGenerator.FaceLandmarks(
                            leftEyeX = detectedFace.leftEye.x, leftEyeY = detectedFace.leftEye.y,
                            rightEyeX = detectedFace.rightEye.x, rightEyeY = detectedFace.rightEye.y,
                            noseX = detectedFace.nose?.x, noseY = detectedFace.nose?.y,
                            leftMouthX = detectedFace.leftMouth?.x, leftMouthY = detectedFace.leftMouth?.y,
                            rightMouthX = detectedFace.rightMouth?.x, rightMouthY = detectedFace.rightMouth?.y
                        )
                    } else null

                    val embedding = embeddingGenerator.generateEmbedding(faceBitmap, landmarks) ?: continue

                    EmbeddingStats.recordEmbedding(
                        source = if (embedding.size == 512) "FACENET_512" else "MOBILEFACENET_192",
                        alignmentSuccessful = landmarks != null,
                        alignmentQuality = detectedFace.quality.overallScore
                    )

                    val qualityTier = when {
                        detectedFace.quality.overallScore >= 0.75f -> "ANCHOR"
                        detectedFace.quality.overallScore >= 0.50f -> "CLUSTERING"
                        detectedFace.quality.overallScore >= 0.30f -> "DISPLAY_ONLY"
                        else -> "REJECTED"
                    }

                    val faceEntity = DetectedFaceEntity(
                        faceId = detectedFace.faceId,
                        photoUri = photoUri,
                        boundingBoxLeft = detectedFace.boundingBox.left,
                        boundingBoxTop = detectedFace.boundingBox.top,
                        boundingBoxRight = detectedFace.boundingBox.right,
                        boundingBoxBottom = detectedFace.boundingBox.bottom,
                        embedding = FaceConverters.floatArrayToByteArray(embedding),
                        confidence = detectedFace.quality.overallScore,
                        qualityScore = detectedFace.quality.overallScore,
                        qualityTier = qualityTier,
                        clusterId = null,
                        imageWidth = detectedFace.imageWidth,
                        imageHeight = detectedFace.imageHeight,
                        matchScore = null
                    )
                    detectedFaceDao.insert(faceEntity)
                    savedFaces++
                }
            }

            scannedPhotoDao.updateStatus(photoUri, ScanStatus.SCANNED, System.currentTimeMillis())
            scanProgressDao.incrementProgress(photoUri, savedFaces)

            Log.i(TAG, "Detection only: Saved $savedFaces unclustered faces from $photoUri")
            ProcessingResult.Success(savedFaces, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error in detection-only processing: $photoUri", e)
            scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
            ProcessingResult.Error(e.message ?: "Unknown error")
        } finally {
            allBitmaps.forEach { bitmap ->
                try { if (!bitmap.isRecycled) bitmap.recycle() } catch (_: Exception) {}
            }
            allBitmaps.clear()
        }
    }

    /**
     * Finalize all deferred faces from two-pass clustering.
     *
     * Call this at the end of a scan to resolve all faces that were
     * deferred during Pass 1 due to uncertainty.
     *
     * @return Number of faces that were finally assigned
     */
    suspend fun finalizeAnchorClustering(): Int = withContext(Dispatchers.IO) {
        val pass2Result = indexMutex.withLock {
            twoPassClusteringService.finalizeAllDeferred()
        }

        var assignedCount = 0

        database.withTransaction {
            // Handle new clusters created for long-deferred faces
            for (newClusterId in pass2Result.forcedNewClusters) {
                val assignedFaceId = pass2Result.newAssignments.entries
                    .find { it.value == newClusterId }?.key

                if (assignedFaceId != null) {
                    // Create person and cluster
                    val displayNumber = personDao.getNextDisplayNumber()
                    val person = PersonEntity(
                        personId = UUID.randomUUID().toString(),
                        name = null,
                        representativeFaceId = assignedFaceId,
                        photoCount = 1,
                        isHidden = false,
                        createdAt = System.currentTimeMillis(),
                        displayNumber = displayNumber
                    )
                    personDao.insert(person)

                    val newCluster = FaceClusterEntity(
                        clusterId = newClusterId,
                        personId = person.personId,
                        centroidEmbedding = null,
                        faceCount = 1,
                        createdAt = System.currentTimeMillis()
                    )
                    faceClusterDao.insert(newCluster)

                    Log.d(TAG, "Pass 2: Created forced cluster $newClusterId")
                }
            }

            // Update face assignments from Pass 2
            for ((faceId, clusterId) in pass2Result.newAssignments) {
                detectedFaceDao.updateClusterId(faceId, clusterId)

                // Update cluster face count
                val cluster = faceClusterDao.getById(clusterId)
                if (cluster != null && clusterId !in pass2Result.forcedNewClusters) {
                    faceClusterDao.update(cluster.copy(
                        faceCount = cluster.faceCount + 1,
                        updatedAt = System.currentTimeMillis()
                    ))
                    cluster.personId?.let { updatePersonPhotoCount(it) }
                }

                assignedCount++
            }
        }

        // Log merge suggestions for manual review
        if (pass2Result.suggestedMerges.isNotEmpty()) {
            Log.i(TAG, "Pass 2 found ${pass2Result.suggestedMerges.size} potential cluster merges:")
            for (bridge in pass2Result.suggestedMerges) {
                Log.i(TAG, "  Merge suggestion: ${bridge.clusterIdA.take(8)} <-> ${bridge.clusterIdB.take(8)} " +
                        "(similarity=${bridge.similarity}, confidence=${bridge.confidence})")
            }
        }

        Log.i(TAG, "Pass 2 complete: $assignedCount faces assigned, " +
                "${pass2Result.stillDeferred.size} still unassigned")

        assignedCount
    }

    /**
     * Initialize the anchor embedding index.
     */
    private suspend fun ensureAnchorIndexInitialized() {
        if (anchorIndex.isEmpty()) {
            val allAnchors = clusterAnchorDao.getAllActiveAnchors()
            if (allAnchors.isNotEmpty()) {
                anchorIndex.buildFromAnchors(allAnchors)
                Log.i(TAG, "Initialized anchor index with ${allAnchors.size} anchors")
            } else {
                anchorIndex.initialize()
                Log.i(TAG, "Initialized empty anchor index")
            }
        }
    }

    /**
     * Rebuild anchor index from database.
     * Call this after database restoration or if index gets corrupted.
     */
    suspend fun rebuildAnchorIndex() = withContext(Dispatchers.IO) {
        val allAnchors = clusterAnchorDao.getAllActiveAnchors()
        anchorIndex.buildFromAnchors(allAnchors)
        Log.i(TAG, "Rebuilt anchor index with ${allAnchors.size} anchors")
    }

    /**
     * Get anchor-based clustering statistics.
     */
    suspend fun getAnchorClusteringStats(): AnchorClusteringStats = withContext(Dispatchers.IO) {
        val anchorCount = clusterAnchorDao.getTotalActiveCount()
        val clusterStats = clusterStatisticsDao.getAll()
        val deferredCount = twoPassClusteringService.getDeferredCount()
        val indexStats = anchorIndex.getStats()

        AnchorClusteringStats(
            totalAnchors = anchorCount,
            clustersWithStats = clusterStats.size,
            averageAcceptanceThreshold = clusterStats.map { it.acceptanceThreshold }.average().toFloat(),
            deferredFaces = deferredCount,
            indexSize = indexStats.anchorCount
        )
    }

    /**
     * Statistics for anchor-based clustering.
     */
    data class AnchorClusteringStats(
        val totalAnchors: Int,
        val clustersWithStats: Int,
        val averageAcceptanceThreshold: Float,
        val deferredFaces: Int,
        val indexSize: Int
    )

    /**
     * Consider adding a face as a new cluster representative.
     *
     * Uses pose-diverse selection strategy:
     * 1. Prioritize filling missing pose categories (frontal, left, right, profile)
     * 2. Only then consider quality-based replacement
     * 3. Ensure representatives are diverse from each other
     */
    private suspend fun maybeAddRepresentative(
        clusterId: String,
        faceId: String,
        embedding: ByteArray,
        qualityScore: Float,
        eulerY: Float?,
        eulerZ: Float?
    ) {
        val existingReps = clusterRepresentativeDao.getRepresentativesForCluster(clusterId)

        // Only consider high-quality faces as representatives
        if (qualityScore < FaceClusteringService.MIN_REPRESENTATIVE_QUALITY) {
            return
        }

        // Determine pose category
        val poseCategory = FaceClusteringService.PoseCategory.fromEulerY(eulerY ?: 0f)
        val poseCategoryName = poseCategory.name

        // Check if we need this pose category
        val existingPoseCategories = existingReps.map { it.poseCategory }.toSet()
        val isPoseMissing = poseCategoryName !in existingPoseCategories

        // If we have fewer than max representatives
        if (existingReps.size < FaceClusteringService.MAX_REPRESENTATIVES) {
            val rank = existingReps.size
            val representative = ClusterRepresentativeEntity(
                id = UUID.randomUUID().toString(),
                clusterId = clusterId,
                faceId = faceId,
                embedding = embedding,
                qualityScore = qualityScore,
                rank = rank,
                eulerY = eulerY ?: 0f,
                eulerZ = eulerZ ?: 0f,
                poseCategory = poseCategoryName,
                createdAt = System.currentTimeMillis()
            )
            clusterRepresentativeDao.insert(representative)
            Log.v(TAG, "Added representative to cluster $clusterId, pose=$poseCategoryName, quality=$qualityScore")
            return
        }

        // We're at max representatives - need to decide if we should replace

        // Priority 1: If this pose is missing and we have a duplicate pose, replace the worst duplicate
        if (isPoseMissing) {
            // Find pose categories with multiple representatives
            val poseCounts = existingReps.groupingBy { it.poseCategory }.eachCount()
            val duplicatePose = poseCounts.filter { it.value > 1 }.keys.firstOrNull()

            if (duplicatePose != null) {
                // Replace the worst representative from the duplicate pose
                val worstDuplicate = existingReps
                    .filter { it.poseCategory == duplicatePose }
                    .minByOrNull { it.qualityScore }

                if (worstDuplicate != null) {
                    clusterRepresentativeDao.delete(worstDuplicate)
                    val representative = ClusterRepresentativeEntity(
                        id = UUID.randomUUID().toString(),
                        clusterId = clusterId,
                        faceId = faceId,
                        embedding = embedding,
                        qualityScore = qualityScore,
                        rank = worstDuplicate.rank,
                        eulerY = eulerY ?: 0f,
                        eulerZ = eulerZ ?: 0f,
                        poseCategory = poseCategoryName,
                        createdAt = System.currentTimeMillis()
                    )
                    clusterRepresentativeDao.insert(representative)
                    Log.v(TAG, "Replaced duplicate pose $duplicatePose with new pose $poseCategoryName in cluster $clusterId")
                    return
                }
            }
        }

        // Priority 2: If this pose already exists, only replace if significantly better quality (20%+)
        val samePoserRep = existingReps.find { it.poseCategory == poseCategoryName }
        if (samePoserRep != null && qualityScore > samePoserRep.qualityScore * 1.2f) {
            clusterRepresentativeDao.delete(samePoserRep)
            val representative = ClusterRepresentativeEntity(
                id = UUID.randomUUID().toString(),
                clusterId = clusterId,
                faceId = faceId,
                embedding = embedding,
                qualityScore = qualityScore,
                rank = samePoserRep.rank,
                eulerY = eulerY ?: 0f,
                eulerZ = eulerZ ?: 0f,
                poseCategory = poseCategoryName,
                createdAt = System.currentTimeMillis()
            )
            clusterRepresentativeDao.insert(representative)
            Log.v(TAG, "Replaced same-pose representative in cluster $clusterId (quality ${samePoserRep.qualityScore} -> $qualityScore)")
            return
        }

        // Priority 3: Check if diverse enough and better than worst overall
        val newEmbedding = FaceConverters.byteArrayToFloatArray(embedding)
        val minSimilarityToExisting = existingReps.minOfOrNull { rep ->
            val repEmbedding = FaceConverters.byteArrayToFloatArray(rep.embedding)
            clusteringService.calculateCosineSimilarity(newEmbedding, repEmbedding)
        } ?: 1f

        val worstRep = existingReps.minByOrNull { it.qualityScore }
        if (minSimilarityToExisting < 0.80f && worstRep != null && qualityScore > worstRep.qualityScore * 1.1f) {
            clusterRepresentativeDao.delete(worstRep)
            val representative = ClusterRepresentativeEntity(
                id = UUID.randomUUID().toString(),
                clusterId = clusterId,
                faceId = faceId,
                embedding = embedding,
                qualityScore = qualityScore,
                rank = worstRep.rank,
                eulerY = eulerY ?: 0f,
                eulerZ = eulerZ ?: 0f,
                poseCategory = poseCategoryName,
                createdAt = System.currentTimeMillis()
            )
            clusterRepresentativeDao.insert(representative)
            Log.v(TAG, "Replaced diverse low-quality representative in cluster $clusterId")
        }
    }

    /**
     * Record a cluster operation for undo support.
     */
    private suspend fun recordClusterHistory(
        operation: String,
        clusterId: String,
        sourceClusterId: String? = null,
        faceId: String? = null,
        previousName: String? = null,
        undoData: String? = null
    ) {
        val history = ClusterHistoryEntity(
            historyId = UUID.randomUUID().toString(),
            operationType = operation,
            timestamp = System.currentTimeMillis(),
            clusterId = clusterId,
            sourceClusterId = sourceClusterId,
            faceId = faceId,
            previousName = previousName,
            undoData = undoData,
            canUndo = true,
            expiresAt = System.currentTimeMillis() + HISTORY_EXPIRY_MS
        )
        clusterHistoryDao.insert(history)
    }

    /**
     * Calculate updated centroid using weighted average.
     */
    private fun calculateUpdatedCentroid(
        currentCentroidBytes: ByteArray?,
        newEmbedding: FloatArray,
        currentSize: Int
    ): ByteArray {
        if (currentCentroidBytes == null) {
            return FaceConverters.floatArrayToByteArray(newEmbedding)
        }

        val currentCentroid = FaceConverters.byteArrayToFloatArray(currentCentroidBytes)
        val newSize = currentSize + 1
        val newCentroid = FloatArray(currentCentroid.size)

        for (i in currentCentroid.indices) {
            newCentroid[i] = (currentCentroid[i] * currentSize + newEmbedding[i]) / newSize
        }

        // Normalize
        var norm = 0f
        for (v in newCentroid) {
            norm += v * v
        }
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (i in newCentroid.indices) {
                newCentroid[i] /= norm
            }
        }

        return FaceConverters.floatArrayToByteArray(newCentroid)
    }

    /**
     * Calculate updated centroid with adjustable weight.
     *
     * @param currentCentroidBytes The current centroid as bytes
     * @param newEmbedding The new face embedding to incorporate
     * @param currentSize Current number of faces in cluster
     * @param weight How much influence the new embedding should have (0.0 to 1.0)
     *               - 1.0 = full weight (equivalent to normal running average)
     *               - 0.5 = half weight (less influence on centroid)
     *               - 0.0 = no weight (centroid unchanged)
     *
     * This allows lower-confidence matches to still improve the centroid
     * without having disproportionate influence that could cause drift.
     */
    private fun calculateWeightedCentroidUpdate(
        currentCentroidBytes: ByteArray?,
        newEmbedding: FloatArray,
        currentSize: Int,
        weight: Float
    ): ByteArray {
        if (currentCentroidBytes == null) {
            return FaceConverters.floatArrayToByteArray(newEmbedding)
        }

        if (weight <= 0f) {
            return currentCentroidBytes
        }

        val currentCentroid = FaceConverters.byteArrayToFloatArray(currentCentroidBytes)
        val newCentroid = FloatArray(currentCentroid.size)

        // Weighted update: the weight scales how much the new embedding influences the result
        // With weight=1.0, this is equivalent to standard running average
        // With weight=0.5, the new embedding has half the influence
        val effectiveNewContribution = weight
        val totalWeight = currentSize + effectiveNewContribution

        for (i in currentCentroid.indices) {
            newCentroid[i] = (currentCentroid[i] * currentSize + newEmbedding[i] * effectiveNewContribution) / totalWeight
        }

        // Normalize to unit length
        var norm = 0f
        for (v in newCentroid) {
            norm += v * v
        }
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (i in newCentroid.indices) {
                newCentroid[i] /= norm
            }
        }

        return FaceConverters.floatArrayToByteArray(newCentroid)
    }

    /**
     * Recalculate cluster centroid by averaging all face embeddings.
     * Used after merging multiple clusters into one.
     *
     * @param clusterId The cluster to recalculate centroid for
     */
    private suspend fun recalculateCentroid(clusterId: String) {
        val faces = detectedFaceDao.getFacesWithEmbeddingsByClusterId(clusterId)
        if (faces.isEmpty()) {
            Log.w(TAG, "Cannot recalculate centroid for cluster $clusterId: no faces with embeddings")
            return
        }

        // Average all embeddings
        val dimension = faces.first().embedding?.let {
            FaceConverters.byteArrayToFloatArray(it).size
        } ?: return

        val centroid = FloatArray(dimension)
        var validCount = 0

        for (face in faces) {
            val embedding = face.embedding?.let {
                FaceConverters.byteArrayToFloatArray(it)
            } ?: continue

            for (i in centroid.indices) {
                centroid[i] += embedding[i]
            }
            validCount++
        }

        if (validCount == 0) {
            Log.w(TAG, "No valid embeddings found for cluster $clusterId")
            return
        }

        // Compute average
        for (i in centroid.indices) {
            centroid[i] = centroid[i] / validCount.toFloat()
        }

        // Normalize to unit length
        var norm = 0f
        for (v in centroid) {
            norm += v * v
        }
        norm = kotlin.math.sqrt(norm)
        if (norm > 0) {
            for (i in centroid.indices) {
                centroid[i] /= norm
            }
        }

        // Update cluster
        val centroidBytes = FaceConverters.floatArrayToByteArray(centroid)
        faceClusterDao.updateCentroid(clusterId, centroidBytes)
        Log.d(TAG, "Recalculated centroid for cluster $clusterId from $validCount faces")
    }

    /**
     * Update person's photo count.
     */
    private suspend fun updatePersonPhotoCount(personId: String) {
        val photoUris = detectedFaceDao.getPhotoUrisForPerson(personId)
        personDao.updatePhotoCount(personId, photoUris.size)
    }

    // ============ Constraint Operations ============

    /**
     * Create CANNOT_LINK constraints between all faces in the same photo.
     *
     * CRITICAL INSIGHT: Faces detected in the SAME PHOTO are ALWAYS different people.
     * This is a hard constraint that prevents wrong merges without relying on thresholds.
     *
     * @param faces List of faces from the same photo
     * @return List of created constraint IDs
     */
    private suspend fun createSamePhotoConstraints(
        faces: List<TwoPassClusteringService.FaceData>
    ): List<String> {
        if (faces.size < 2) return emptyList()

        val constraintIds = mutableListOf<String>()
        val currentTime = System.currentTimeMillis()

        // Create CANNOT_LINK between every pair of faces in this photo
        for (i in faces.indices) {
            for (j in i + 1 until faces.size) {
                val faceId1 = faces[i].faceId
                val faceId2 = faces[j].faceId

                // Skip if constraint already exists
                if (clusteringConstraintDao.hasCannotLink(faceId1, faceId2)) {
                    continue
                }

                val constraint = ClusteringConstraintEntity(
                    constraintId = UUID.randomUUID().toString(),
                    constraintType = ConstraintType.CANNOT_LINK,
                    faceId1 = faceId1,
                    faceId2 = faceId2,
                    createdAt = currentTime,
                    createdBy = "same_photo"  // Mark source for debugging
                )

                clusteringConstraintDao.insert(constraint)
                constraintIds.add(constraint.constraintId)

                Log.v(TAG, "Created same-photo CANNOT_LINK: ${faceId1.take(8)} <-> ${faceId2.take(8)}")
            }
        }

        return constraintIds
    }

    /**
     * Get a map of CANNOT_LINK constraints for a list of faces.
     *
     * @param faceIds List of face IDs to check
     * @return Map of faceId -> Set of face IDs that cannot be in the same cluster
     */
    private suspend fun getCannotLinkMapForFaces(faceIds: List<String>): Map<String, Set<String>> {
        val cannotLinkMap = mutableMapOf<String, MutableSet<String>>()

        for (faceId in faceIds) {
            val cannotLinkFaces = clusteringConstraintDao.getCannotLinkFaceIds(faceId)
            if (cannotLinkFaces.isNotEmpty()) {
                cannotLinkMap[faceId] = cannotLinkFaces.toMutableSet()
            }
        }

        return cannotLinkMap
    }

    /**
     * Check if assigning a face to a cluster would violate any CANNOT_LINK constraints.
     *
     * @param faceId The face being assigned
     * @param clusterId The target cluster
     * @return true if assignment would violate a constraint
     */
    suspend fun wouldViolateConstraint(faceId: String, clusterId: String): Boolean {
        // Get all faces currently in the cluster
        val clusterFaces = detectedFaceDao.getFaceIdsInCluster(clusterId)
        if (clusterFaces.isEmpty()) return false

        // Get all faces that cannot be in the same cluster as faceId
        val cannotLinkFaces = clusteringConstraintDao.getCannotLinkFaceIds(faceId)
        if (cannotLinkFaces.isEmpty()) return false

        // Check if any face in the cluster is in the cannot-link set
        val violation = clusterFaces.any { it in cannotLinkFaces }
        if (violation) {
            Log.d(TAG, "Constraint violation: face $faceId cannot join cluster $clusterId")
        }
        return violation
    }

    /**
     * Get faces in a cluster that have CANNOT_LINK constraints with a given face.
     *
     * @param faceId The face to check
     * @param clusterId The cluster to check against
     * @return List of face IDs in the cluster that conflict with faceId
     */
    suspend fun getConflictingFacesInCluster(faceId: String, clusterId: String): List<String> {
        val clusterFaces = detectedFaceDao.getFaceIdsInCluster(clusterId)
        val cannotLinkFaces = clusteringConstraintDao.getCannotLinkFaceIds(faceId).toSet()
        return clusterFaces.filter { it in cannotLinkFaces }
    }

    // ============ Person Operations ============

    fun getPersonsWithFaceFlow(): Flow<List<PersonWithFace>> = personDao.getPersonsWithFaceFlow()

    suspend fun getPersonsWithFace(): List<PersonWithFace> = personDao.getPersonsWithFace()

    suspend fun getPersonWithFace(personId: String): PersonWithFace? = personDao.getPersonWithFace(personId)

    suspend fun updatePersonName(personId: String, name: String?) {
        personDao.updateName(personId, name)
    }

    suspend fun hidePersons(personId: String) {
        personDao.updateHidden(personId, true)
    }

    suspend fun showPerson(personId: String) {
        personDao.updateHidden(personId, false)
    }

    /**
     * Update person's name and birthday together.
     *
     * @param personId The person's ID
     * @param name The new name (or null to clear)
     * @param birthday The birthday in "MM-DD" format (e.g., "01-15" for January 15th), or null to clear
     */
    suspend fun updatePersonProfile(personId: String, name: String?, birthday: String?) {
        personDao.updateNameAndBirthday(personId, name, birthday)
        Log.d(TAG, "Updated person $personId profile: name=$name, birthday=$birthday")
    }

    /**
     * Update person's birthday only.
     *
     * @param personId The person's ID
     * @param birthday The birthday in "MM-DD" format (e.g., "01-15" for January 15th), or null to clear
     */
    suspend fun updatePersonBirthday(personId: String, birthday: String?) {
        personDao.updateBirthday(personId, birthday)
    }

    /**
     * Get persons whose birthday is today.
     *
     * @param todayMMDD Today's date in "MM-DD" format
     * @return List of persons with birthday today
     */
    suspend fun getPersonsWithBirthdayToday(todayMMDD: String): List<PersonEntity> {
        return personDao.getPersonsWithBirthdayToday(todayMMDD)
    }

    /**
     * Get all persons who have birthdays set (for scheduling notifications).
     */
    suspend fun getPersonsWithBirthdays(): List<PersonEntity> {
        return personDao.getPersonsWithBirthdays()
    }

    /**
     * Get persons with upcoming birthdays in the next N days.
     *
     * @param days Number of days to look ahead
     * @return List of persons with upcoming birthdays
     */
    suspend fun getPersonsWithUpcomingBirthdays(days: Int): List<PersonEntity> {
        val calendar = java.util.Calendar.getInstance()
        val dates = mutableListOf<String>()

        for (i in 0 until days) {
            val month = calendar.get(java.util.Calendar.MONTH) + 1
            val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
            dates.add(String.format("%02d-%02d", month, day))
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        return personDao.getPersonsWithUpcomingBirthdays(dates)
    }

    /**
     * Get a person entity by ID.
     */
    suspend fun getPerson(personId: String): PersonEntity? {
        return personDao.getById(personId)
    }

    /**
     * Get all photos containing a specific person.
     * Returns all photos where this person appears, regardless of other people in the photo.
     */
    suspend fun getPhotosForPerson(personId: String): List<String> {
        return detectedFaceDao.getPhotoUrisForPerson(personId)
    }

    /**
     * Get solo photos for a person - photos where ONLY this person appears.
     * Useful for "Only them" filter feature.
     */
    suspend fun getSoloPhotosForPerson(personId: String): List<String> {
        return detectedFaceDao.getSoloPhotoUrisForPerson(personId)
    }

    // ============ Medoid (Best Representative) Face Selection ============

    /**
     * Calculate the medoid face for a person - the face most similar to all other faces.
     * This provides the best representative thumbnail for the cluster.
     *
     * Algorithm:
     * 1. Get all faces with embeddings for this person
     * 2. For each face, calculate average similarity to all other faces
     * 3. Return the face with highest average similarity (the medoid)
     *
     * @return The face ID of the medoid, or null if no faces found
     */
    suspend fun calculateMedoidFace(personId: String): String? = withContext(Dispatchers.Default) {
        try {
            // Get all faces for this person with embeddings
            val faces = detectedFaceDao.getFacesWithEmbeddingsForPerson(personId)

            if (faces.isEmpty()) {
                Log.w(TAG, "No faces found for person $personId")
                return@withContext null
            }

            if (faces.size == 1) {
                Log.d(TAG, "Only one face for person $personId, using it as medoid")
                return@withContext faces.first().faceId
            }

            // Convert embeddings
            val faceEmbeddings = faces.mapNotNull { face ->
                face.embedding?.let { bytes ->
                    FaceConverters.byteArrayToFloatArray(bytes)?.let { embedding ->
                        face.faceId to embedding
                    }
                }
            }

            if (faceEmbeddings.size < 2) {
                Log.w(TAG, "Not enough faces with embeddings for medoid calculation")
                return@withContext faces.firstOrNull()?.faceId
            }

            // Calculate average similarity for each face
            var bestFaceId: String? = null
            var bestAvgSimilarity = -1f

            for ((faceId, embedding) in faceEmbeddings) {
                var totalSimilarity = 0f
                var count = 0

                for ((otherFaceId, otherEmbedding) in faceEmbeddings) {
                    if (faceId != otherFaceId) {
                        val similarity = cosineSimilarity(embedding, otherEmbedding)
                        totalSimilarity += similarity
                        count++
                    }
                }

                val avgSimilarity = if (count > 0) totalSimilarity / count else 0f

                if (avgSimilarity > bestAvgSimilarity) {
                    bestAvgSimilarity = avgSimilarity
                    bestFaceId = faceId
                }
            }

            Log.d(TAG, "Medoid for person $personId: faceId=$bestFaceId, avgSimilarity=$bestAvgSimilarity")
            bestFaceId
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating medoid for person $personId", e)
            null
        }
    }

    /**
     * Update the representative face for a person to the medoid (most representative face).
     */
    suspend fun updatePersonRepresentativeToMedoid(personId: String): Boolean {
        val medoidFaceId = calculateMedoidFace(personId)
        if (medoidFaceId != null) {
            personDao.updateRepresentativeFace(personId, medoidFaceId)
            Log.i(TAG, "Updated representative face for person $personId to medoid $medoidFaceId")
            return true
        }
        return false
    }

    /**
     * Update representative faces for all persons to their medoids.
     * Call this after scanning is complete for best thumbnails.
     */
    suspend fun updateAllRepresentativesToMedoids(): Int {
        var updatedCount = 0
        val persons = personDao.getAll()

        Log.i(TAG, "Updating representative faces for ${persons.size} persons to medoids...")

        for (person in persons) {
            if (updatePersonRepresentativeToMedoid(person.personId)) {
                updatedCount++
            }
        }

        Log.i(TAG, "Updated $updatedCount representative faces to medoids")
        return updatedCount
    }

    /**
     * Reassign thumbnail for a person to the best available face.
     * Prioritizes faces with:
     * 1. Visible eyes (eyeVisibilityScore > 0)
     * 2. High quality score
     * 3. High match score (similarity to cluster)
     *
     * @return true if thumbnail was updated, false otherwise
     */
    suspend fun reassignThumbnail(personId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // First try to get best face with visible eyes
            var bestFace = detectedFaceDao.getBestFaceForThumbnail(personId)

            // If no face with eyes, fall back to highest quality face
            if (bestFace == null) {
                val faces = detectedFaceDao.getFacesForPerson(personId)
                bestFace = faces.maxByOrNull { it.qualityScore }
            }

            if (bestFace != null) {
                personDao.updateRepresentativeFace(personId, bestFace.faceId)
                Log.i(TAG, "Reassigned thumbnail for person $personId to face ${bestFace.faceId} (quality=${bestFace.qualityScore}, eyes=${bestFace.eyeVisibilityScore})")
                return@withContext true
            }

            Log.w(TAG, "No faces found to reassign thumbnail for person $personId")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error reassigning thumbnail for person $personId", e)
            false
        }
    }

    /**
     * Reassign thumbnails for all persons to their best available faces.
     * Call this to fix any thumbnails showing non-face content.
     *
     * @return number of thumbnails updated
     */
    suspend fun reassignAllThumbnails(): Int = withContext(Dispatchers.IO) {
        var updatedCount = 0
        val persons = personDao.getAll()

        Log.i(TAG, "Reassigning thumbnails for ${persons.size} persons...")

        for (person in persons) {
            if (reassignThumbnail(person.personId)) {
                updatedCount++
            }
        }

        Log.i(TAG, "Reassigned $updatedCount thumbnails")
        updatedCount
    }

    /**
     * Get photos for a person ordered by match score (best matches first).
     * Photos with faces most similar to the cluster centroid appear first.
     */
    suspend fun getPhotosForPersonByMatchScore(personId: String): List<String> {
        return detectedFaceDao.getFacesForPersonByMatchScore(personId)
            .map { it.photoUri }
            .distinct()
    }

    /**
     * Get all faces for a person, ordered by quality score (best first).
     * Used for thumbnail selection UI.
     */
    suspend fun getFacesForPerson(personId: String): List<DetectedFaceEntity> {
        return detectedFaceDao.getFacesForPerson(personId)
            .sortedByDescending { it.qualityScore }
    }

    /**
     * Set a specific face as the thumbnail/representative for a person.
     * @param personId The person to update
     * @param faceId The face to use as thumbnail
     * @return true if updated successfully
     */
    suspend fun setPersonThumbnail(personId: String, faceId: String): Boolean {
        return try {
            personDao.updateRepresentativeFace(personId, faceId)
            Log.i(TAG, "Set thumbnail for person $personId to face $faceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting thumbnail for person $personId", e)
            false
        }
    }

    /**
     * Calculate cosine similarity between two embeddings.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }

    // ============ Photo Filtering Operations ============

    suspend fun getPhotosContainingAllPersons(personIds: List<String>): List<String> {
        if (personIds.isEmpty()) return emptyList()
        if (personIds.size == 1) return getPhotosForPerson(personIds.first())
        return detectedFaceDao.getPhotoUrisContainingAllPersons(personIds, personIds.size)
    }

    // ============ Cluster Operations ============

    /**
     * Merge two persons (clusters) with transaction support and history tracking.
     */
    suspend fun mergePersons(sourcePersonId: String, targetPersonId: String): Boolean {
        return try {
            database.withTransaction {
                val sourceClusters = faceClusterDao.getByPersonId(sourcePersonId)
                val sourcePerson = personDao.getById(sourcePersonId)
                val targetPerson = personDao.getById(targetPersonId)

                for (cluster in sourceClusters) {
                    faceClusterDao.assignToPerson(cluster.clusterId, targetPersonId)

                    // Record history for each cluster reassignment
                    recordClusterHistory(
                        operation = ClusterOperation.MERGE,
                        clusterId = cluster.clusterId,
                        sourceClusterId = sourcePersonId,
                        previousName = sourcePerson?.name
                    )
                }

                sourcePerson?.let { personDao.delete(it) }
                updatePersonPhotoCount(targetPersonId)

                // Add cannot-link constraint to prevent re-merging if user undoes
                // (will be added if user marks them as different people)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to merge persons: $sourcePersonId -> $targetPersonId", e)
            false
        }
    }

    suspend fun mergeMultiplePersons(personIds: List<String>): Boolean {
        if (personIds.size < 2) return false

        val targetPersonId = personIds.first()
        var allSucceeded = true
        for (i in 1 until personIds.size) {
            if (!mergePersons(personIds[i], targetPersonId)) {
                allSucceeded = false
            }
        }
        return allSucceeded
    }

    /**
     * Remove a photo from a person with history tracking for undo.
     * Handles cleanup of empty clusters and updates representative faces.
     */
    suspend fun removePhotoFromPerson(photoUri: String, personId: String): Boolean {
        val face = detectedFaceDao.getFaceByPhotoAndPerson(photoUri, personId)
        if (face != null) {
            val originalClusterId = face.clusterId

            // Record history for undo
            if (originalClusterId != null) {
                recordClusterHistory(
                    operation = ClusterOperation.MOVE_FACE,
                    clusterId = originalClusterId,
                    faceId = face.faceId
                )
            }

            // Remove face from cluster
            detectedFaceDao.removeFaceFromCluster(face.faceId)

            // Check if cluster is now empty and clean up
            if (originalClusterId != null) {
                val remainingFaces = detectedFaceDao.getFaceCountForCluster(originalClusterId)
                if (remainingFaces == 0) {
                    // Cluster is empty - soft delete it
                    faceClusterDao.softDelete(originalClusterId)
                    Log.d(TAG, "Soft-deleted empty cluster $originalClusterId")

                    // Check if person has any remaining clusters
                    val remainingClusters = faceClusterDao.getByPersonId(personId)
                    if (remainingClusters.isEmpty()) {
                        // No more clusters for this person - soft delete the person
                        personDao.softDelete(personId)
                        Log.d(TAG, "Soft-deleted person $personId (no remaining clusters)")
                        return true
                    }
                } else {
                    // Cluster still has faces - update representative if needed
                    val person = personDao.getById(personId)
                    if (person?.representativeFaceId == face.faceId) {
                        // The removed face was the representative - find a new one
                        val newRepFace = detectedFaceDao.getBestFaceForCluster(originalClusterId)
                        if (newRepFace != null) {
                            personDao.updateRepresentativeFace(personId, newRepFace.faceId)
                            Log.d(TAG, "Updated representative face for person $personId to ${newRepFace.faceId}")
                        }
                    }
                }
            }

            updatePersonPhotoCount(personId)
            return true
        }
        return false
    }

    /**
     * Find merge candidates using HNSW index for O(log n) search.
     */
    suspend fun findMergeCandidates(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        ensureIndexInitialized()
        clusteringService.findMergeCandidatesWithIndex(
            embeddingIndex = embeddingIndex,
            threshold = FaceClusteringService.DEFAULT_MERGE_THRESHOLD
        )
    }

    /**
     * Get suggested merges for user confirmation.
     */
    suspend fun findSuggestedMerges(): List<FaceClusteringService.MergeSuggestion> {
        val clusters = faceClusterDao.getAll()
        return clusteringService.findSuggestedMerges(clusters)
    }

    /**
     * Add a cannot-link constraint between two faces.
     * Prevents them from ever being in the same cluster.
     */
    suspend fun addCannotLinkConstraint(faceId1: String, faceId2: String) {
        val constraint = ClusteringConstraintEntity(
            constraintId = UUID.randomUUID().toString(),
            constraintType = ConstraintType.CANNOT_LINK,
            faceId1 = faceId1,
            faceId2 = faceId2,
            createdAt = System.currentTimeMillis(),
            createdBy = "user"
        )
        clusteringConstraintDao.insert(constraint)
        Log.i(TAG, "Added cannot-link constraint: $faceId1 <-> $faceId2")
    }

    /**
     * Add a must-link constraint between two faces.
     * Forces them to be in the same cluster.
     */
    suspend fun addMustLinkConstraint(faceId1: String, faceId2: String) {
        val constraint = ClusteringConstraintEntity(
            constraintId = UUID.randomUUID().toString(),
            constraintType = ConstraintType.MUST_LINK,
            faceId1 = faceId1,
            faceId2 = faceId2,
            createdAt = System.currentTimeMillis(),
            createdBy = "user"
        )
        clusteringConstraintDao.insert(constraint)
        Log.i(TAG, "Added must-link constraint: $faceId1 <-> $faceId2")
    }

    /**
     * Get recent undo-able operations.
     */
    suspend fun getUndoableOperations(limit: Int = 10): List<ClusterHistoryEntity> {
        return clusterHistoryDao.getRecentUndoable(limit)
    }

    /**
     * Undo the last cluster operation.
     * Returns true if undo was successful.
     */
    suspend fun undoLastOperation(): Boolean {
        val lastOperation = clusterHistoryDao.getRecentUndoable(1).firstOrNull()
            ?: return false

        return try {
            when (lastOperation.operationType) {
                ClusterOperation.MERGE -> undoMerge(lastOperation)
                ClusterOperation.MOVE_FACE -> undoMoveFace(lastOperation)
                ClusterOperation.RENAME -> undoRename(lastOperation)
                else -> {
                    Log.w(TAG, "Cannot undo operation type: ${lastOperation.operationType}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to undo operation: ${lastOperation.historyId}", e)
            false
        }
    }

    private suspend fun undoMerge(history: ClusterHistoryEntity): Boolean {
        // For merge, we need to separate the clusters again
        // This is complex because we need to recreate the source cluster
        // and move the faces back. For now, mark as cannot undo.
        clusterHistoryDao.markAsUndone(history.historyId)
        Log.w(TAG, "Merge undo not yet fully implemented - marked as processed")
        return false
    }

    private suspend fun undoMoveFace(history: ClusterHistoryEntity): Boolean {
        val faceId = history.faceId ?: return false
        val originalClusterId = history.clusterId

        database.withTransaction {
            detectedFaceDao.updateClusterId(faceId, originalClusterId)
            clusterHistoryDao.markAsUndone(history.historyId)
        }

        Log.i(TAG, "Undid face move: $faceId back to cluster $originalClusterId")
        return true
    }

    private suspend fun undoRename(history: ClusterHistoryEntity): Boolean {
        val clusterId = history.clusterId
        val previousName = history.previousName

        val cluster = faceClusterDao.getById(clusterId) ?: return false
        val personId = cluster.personId ?: return false

        database.withTransaction {
            personDao.updateName(personId, previousName)
            clusterHistoryDao.markAsUndone(history.historyId)
        }

        Log.i(TAG, "Undid rename: person $personId back to $previousName")
        return true
    }

    /**
     * Merge clusters internally with transaction support.
     * All database operations are atomic - either all succeed or all fail.
     */
    private suspend fun mergeClustersInternal(sourceClusterId: String, targetClusterId: String): Boolean {
        return try {
            database.withTransaction {
                val sourceCluster = faceClusterDao.getById(sourceClusterId)
                val targetCluster = faceClusterDao.getById(targetClusterId)

                if (sourceCluster == null || targetCluster == null) {
                    Log.w(TAG, "Cluster not found for merge")
                    return@withTransaction false
                }

                // Calculate merged centroid
                val mergedCentroid = clusteringService.calculateMergedCentroid(sourceCluster, targetCluster)

                // Update all faces from source to target
                detectedFaceDao.updateAllClusterIds(sourceClusterId, targetClusterId)

                // Merge representatives - keep top quality from both clusters
                val sourceReps = clusterRepresentativeDao.getRepresentativesForCluster(sourceClusterId)
                val targetReps = clusterRepresentativeDao.getRepresentativesForCluster(targetClusterId)
                val allReps = (sourceReps + targetReps).sortedByDescending { it.qualityScore }
                    .take(FaceClusteringService.MAX_REPRESENTATIVES)

                // Delete old representatives and insert merged set
                clusterRepresentativeDao.deleteByClusterId(sourceClusterId)
                clusterRepresentativeDao.deleteByClusterId(targetClusterId)
                allReps.forEachIndexed { index, rep ->
                    clusterRepresentativeDao.insert(
                        rep.copy(clusterId = targetClusterId, rank = index + 1)
                    )
                }

                // Update target cluster
                val newFaceCount = sourceCluster.faceCount + targetCluster.faceCount
                val mergedCentroidBytes = mergedCentroid?.let { FaceConverters.floatArrayToByteArray(it) }
                faceClusterDao.update(targetCluster.copy(
                    centroidEmbedding = mergedCentroidBytes,
                    faceCount = newFaceCount,
                    updatedAt = System.currentTimeMillis()
                ))

                // Update HNSW index
                embeddingIndex.removeCluster(sourceClusterId)
                if (mergedCentroid != null) {
                    embeddingIndex.addCluster(targetClusterId, mergedCentroid)
                }

                // Delete source cluster
                faceClusterDao.deleteById(sourceClusterId)

                // Record history
                recordClusterHistory(
                    operation = ClusterOperation.MERGE,
                    clusterId = targetClusterId,
                    sourceClusterId = sourceClusterId
                )

                Log.d(TAG, "Merged cluster $sourceClusterId into $targetClusterId (new count: $newFaceCount)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transaction failed for cluster merge: $sourceClusterId -> $targetClusterId", e)
            false
        }
    }

    /**
     * Auto-merge similar clusters after scan.
     * Runs multiple passes until no more merges are found.
     */
    suspend fun autoMergeSimilarClusters(): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting auto-merge of similar clusters...")
        var totalMergeCount = 0
        var passCount = 0
        val maxPasses = 5  // Prevent infinite loops

        while (passCount < maxPasses) {
            passCount++
            val candidates = findMergeCandidates()

            if (candidates.isEmpty()) {
                Log.d(TAG, "Pass $passCount: No merge candidates found")
                break
            }

            Log.d(TAG, "Pass $passCount: Found ${candidates.size} merge candidates")

            var passMergeCount = 0
            val processedClusters = mutableSetOf<String>()

            for ((sourceClusterId, targetClusterId) in candidates) {
                if (sourceClusterId in processedClusters || targetClusterId in processedClusters) {
                    continue
                }

                try {
                    val sourceCluster = faceClusterDao.getById(sourceClusterId)
                    val targetCluster = faceClusterDao.getById(targetClusterId)

                    if (sourceCluster == null || targetCluster == null) continue

                    val sourcePersonId = sourceCluster.personId
                    val targetPersonId = targetCluster.personId

                    if (mergeClustersInternal(sourceClusterId, targetClusterId)) {
                        processedClusters.add(sourceClusterId)
                        processedClusters.add(targetClusterId)

                        // Merge persons
                        if (sourcePersonId != null && targetPersonId != null && sourcePersonId != targetPersonId) {
                            mergePersons(sourcePersonId, targetPersonId)
                            Log.d(TAG, "Auto-merged person $sourcePersonId into $targetPersonId")
                        }

                        passMergeCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error merging clusters", e)
                }
            }

            totalMergeCount += passMergeCount
            Log.d(TAG, "Pass $passCount complete: merged $passMergeCount clusters")

            // If no merges this pass, stop
            if (passMergeCount == 0) {
                break
            }
        }

        Log.d(TAG, "Auto-merge complete: merged $totalMergeCount clusters in $passCount passes")
        totalMergeCount
    }

    /**
     * Merge clusters transitively using connected components.
     * If A matches B and B matches C, merge all three even if A-C similarity is low.
     * This fixes the problem where same person is split across multiple clusters
     * due to variations in lighting, angle, or expression.
     *
     * @param threshold Minimum similarity to create edge in similarity graph (default 0.30)
     * @return Number of clusters merged
     */
    suspend fun mergeTransitiveClusters(threshold: Float = 0.30f): Int = withContext(Dispatchers.IO) {
        val allClusters = faceClusterDao.getAll()
        if (allClusters.size < 2) {
            Log.d(TAG, "Transitive merge: not enough clusters (${allClusters.size})")
            return@withContext 0
        }

        Log.i(TAG, "Running transitive merge on ${allClusters.size} clusters at threshold $threshold...")

        // Find connected components at low threshold
        val components = clusteringService.findTransitiveMergeCandidates(
            clusters = allClusters,
            threshold = threshold
        )

        if (components.isEmpty()) {
            Log.d(TAG, "Transitive merge: no components found to merge")
            return@withContext 0
        }

        var mergedCount = 0
        for ((representativeId, clusterIds) in components) {
            if (clusterIds.size < 2) continue

            // Get the cluster with most faces as target
            val clustersToMerge = clusterIds.mapNotNull { faceClusterDao.getById(it) }
            val targetCluster = clustersToMerge.maxByOrNull { it.faceCount } ?: continue
            val sourceClusters = clustersToMerge.filter { it.clusterId != targetCluster.clusterId }

            Log.d(TAG, "Transitive merge: ${sourceClusters.size} clusters -> ${targetCluster.clusterId}")

            // Merge each source into target
            for (source in sourceClusters) {
                try {
                    // Move all faces from source to target
                    detectedFaceDao.updateAllClusterIds(source.clusterId, targetCluster.clusterId)

                    // Move representatives if the DAO supports it
                    try {
                        clusterRepresentativeDao.moveToCluster(source.clusterId, targetCluster.clusterId)
                    } catch (e: Exception) {
                        // Method may not exist - just delete source representatives
                        clusterRepresentativeDao.deleteByClusterId(source.clusterId)
                    }

                    // Handle person merging
                    val sourcePerson = source.personId
                    val targetPerson = targetCluster.personId

                    if (sourcePerson != null && targetPerson != null && sourcePerson != targetPerson) {
                        // Update all clusters from source person to target person
                        faceClusterDao.getByPersonId(sourcePerson).forEach { cluster ->
                            faceClusterDao.assignToPerson(cluster.clusterId, targetPerson)
                        }
                        // Delete source person
                        personDao.deleteById(sourcePerson)
                        Log.d(TAG, "Merged person $sourcePerson into $targetPerson")
                    } else if (sourcePerson != null && targetPerson == null) {
                        // Target has no person, assign source's person
                        faceClusterDao.assignToPerson(targetCluster.clusterId, sourcePerson)
                    }

                    // Delete source cluster
                    faceClusterDao.deleteById(source.clusterId)

                    // Remove from HNSW index
                    try {
                        embeddingIndex.removeCluster(source.clusterId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove cluster ${source.clusterId} from index", e)
                    }

                    mergedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to merge cluster ${source.clusterId} into ${targetCluster.clusterId}", e)
                }
            }

            // Recalculate target cluster centroid from all its faces
            try {
                recalculateCentroid(targetCluster.clusterId)

                // Update face count
                val newFaceCount = detectedFaceDao.getFaceCountForCluster(targetCluster.clusterId)
                faceClusterDao.updateFaceCount(targetCluster.clusterId, newFaceCount)

                // Update HNSW index with new centroid
                val updatedCluster = faceClusterDao.getById(targetCluster.clusterId)
                updatedCluster?.centroidEmbedding?.let { centroid ->
                    val embedding = FaceConverters.byteArrayToFloatArray(centroid)
                    embeddingIndex.removeCluster(targetCluster.clusterId)
                    embeddingIndex.addCluster(targetCluster.clusterId, embedding)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update target cluster ${targetCluster.clusterId} after merge", e)
            }
        }

        Log.i(TAG, "Transitive merge complete: merged $mergedCount clusters from ${components.size} components")
        mergedCount
    }

    /**
     * Run Chinese Whispers graph clustering on all faces.
     * This directly clusters face embeddings instead of using centroid-based matching,
     * which handles pose variation much better.
     *
     * @param similarityThreshold Minimum similarity to create edge (default 0.65 for high confidence)
     * @return Number of merge operations performed
     */
    suspend fun runChineseWhispersClustering(similarityThreshold: Float = 0.40f): Int = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting Chinese Whispers clustering...")

        // Step 1: Get all faces with embeddings
        val allFaces = detectedFaceDao.getUnclusteredFaces() +
            faceClusterDao.getAll().flatMap { cluster ->
                detectedFaceDao.getFacesWithEmbeddingsByClusterId(cluster.clusterId)
            }

        if (allFaces.size < 2) {
            Log.d(TAG, "Chinese Whispers: not enough faces (${allFaces.size})")
            return@withContext 0
        }

        // Step 2: Convert to embedding pairs
        val facePairs = allFaces.mapNotNull { face ->
            face.embedding?.let { embBytes ->
                face.faceId to FaceConverters.byteArrayToFloatArray(embBytes)
            }
        }

        Log.d(TAG, "Chinese Whispers: processing ${facePairs.size} faces")

        // Step 3: Run Chinese Whispers
        val chineseWhispers = ChineseWhispersClustering(
            similarityThreshold = similarityThreshold,
            maxIterations = 50
        )
        val cwResult = chineseWhispers.cluster(facePairs)

        // Step 4: Build face -> existing cluster mapping
        val faceToExistingCluster = allFaces
            .filter { it.clusterId != null }
            .associate { it.faceId to it.clusterId!! }

        // Step 5: Find which existing clusters should merge
        val mergeSets = chineseWhispers.findMergeSets(faceToExistingCluster, cwResult)

        if (mergeSets.isEmpty()) {
            Log.d(TAG, "Chinese Whispers: no merge sets found")
            return@withContext 0
        }

        Log.i(TAG, "Chinese Whispers: found ${mergeSets.size} merge sets")

        // Step 6: Execute merges
        var mergeCount = 0
        for (clusterIds in mergeSets) {
            val clusterList = clusterIds.toList()
            if (clusterList.size < 2) continue

            // Get clusters and find the one with most faces as target
            val clusters = clusterList.mapNotNull { faceClusterDao.getById(it) }
            val targetCluster = clusters.maxByOrNull { it.faceCount } ?: continue
            val sourceClusters = clusters.filter { it.clusterId != targetCluster.clusterId }

            Log.d(TAG, "Chinese Whispers merge: ${sourceClusters.size} clusters -> ${targetCluster.clusterId}")

            for (source in sourceClusters) {
                try {
                    // Move faces
                    detectedFaceDao.updateAllClusterIds(source.clusterId, targetCluster.clusterId)

                    // Move representatives
                    try {
                        clusterRepresentativeDao.moveToCluster(source.clusterId, targetCluster.clusterId)
                    } catch (e: Exception) {
                        clusterRepresentativeDao.deleteByClusterId(source.clusterId)
                    }

                    // Handle person merging
                    val sourcePerson = source.personId
                    val targetPerson = targetCluster.personId

                    if (sourcePerson != null && targetPerson != null && sourcePerson != targetPerson) {
                        faceClusterDao.getByPersonId(sourcePerson).forEach { cluster ->
                            faceClusterDao.assignToPerson(cluster.clusterId, targetPerson)
                        }
                        personDao.deleteById(sourcePerson)
                        Log.d(TAG, "CW merged person $sourcePerson into $targetPerson")
                    } else if (sourcePerson != null && targetPerson == null) {
                        faceClusterDao.assignToPerson(targetCluster.clusterId, sourcePerson)
                    }

                    // Delete source cluster
                    faceClusterDao.deleteById(source.clusterId)

                    // Remove from HNSW index
                    try {
                        embeddingIndex.removeCluster(source.clusterId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove cluster ${source.clusterId} from index", e)
                    }

                    mergeCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to merge cluster ${source.clusterId}", e)
                }
            }

            // Update target cluster
            try {
                recalculateCentroid(targetCluster.clusterId)
                val newFaceCount = detectedFaceDao.getFaceCountForCluster(targetCluster.clusterId)
                faceClusterDao.updateFaceCount(targetCluster.clusterId, newFaceCount)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update target cluster after CW merge", e)
            }
        }

        Log.i(TAG, "Chinese Whispers complete: merged $mergeCount clusters")
        mergeCount
    }

    // ============ Statistics ============

    suspend fun getTotalFacesCount(): Int = detectedFaceDao.getTotalCount()

    suspend fun getVisiblePersonsCount(): Int = personDao.getVisibleCount()

    suspend fun getClustersCount(): Int = faceClusterDao.getTotalCount()

    /**
     * Recalculate photo counts for all persons.
     * Should be called after bulk operations like auto-merge.
     * Uses individual updates to trigger Room Flow notifications.
     */
    suspend fun recalculateAllPhotoCounts() {
        val persons = personDao.getAll()
        Log.d(TAG, "Recalculating photo counts for ${persons.size} persons")

        // Also check for orphaned clusters (clusters without person)
        val allClusters = faceClusterDao.getAll()
        val orphanedClusters = allClusters.filter { it.personId == null }
        Log.w(TAG, "Found ${orphanedClusters.size} clusters without personId out of ${allClusters.size} total")

        // Check total faces
        val totalFaces = detectedFaceDao.getTotalCount()
        val clusteredFaces = detectedFaceDao.getClusteredCount()
        Log.d(TAG, "Total faces: $totalFaces, Clustered faces: $clusteredFaces")

        var updatedCount = 0
        for (person in persons) {
            val photoUris = detectedFaceDao.getPhotoUrisForPerson(person.personId)
            val newCount = photoUris.size
            Log.d(TAG, "Person ${person.personId}: current=${person.photoCount}, calculated=$newCount")
            if (newCount != person.photoCount) {
                personDao.updatePhotoCount(person.personId, newCount)
                Log.d(TAG, "Updated photo count for ${person.personId}: ${person.photoCount} -> $newCount")
                updatedCount++
            }
        }
        Log.d(TAG, "Recalculated photo counts: $updatedCount persons updated")
    }

    // ============ Cleanup ============

    /**
     * Clear all face data with transaction support.
     */
    suspend fun clearAllData() {
        database.withTransaction {
            // Clear all tables
            detectedFaceDao.deleteAll()
            clusterRepresentativeDao.deleteAll()
            clusterHistoryDao.deleteAll()
            clusteringConstraintDao.deleteAll()
            faceClusterDao.deleteAll()
            personDao.deleteAll()
            scannedPhotoDao.deleteAll()
            scanProgressDao.clear()
        }

        // Clear HNSW index
        try {
            embeddingIndex.clear()
            indexInitialized = false
            Log.i(TAG, "Cleared all face data and index")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear embedding index", e)
        }
    }

    /**
     * Cleanup expired history entries.
     */
    suspend fun cleanupExpiredHistory() {
        val now = System.currentTimeMillis()
        clusterHistoryDao.deleteExpired(now)
        Log.i(TAG, "Cleaned up expired history entries")
    }

    /**
     * Fix orphaned clusters that don't have a person assigned.
     * This can happen if the person creation fails or there's a race condition.
     *
     * @return Number of orphaned clusters that were fixed
     */
    suspend fun fixOrphanedClusters(): Int = withContext(Dispatchers.IO) {
        val orphanedClusters = faceClusterDao.getUnassignedClusters()
        if (orphanedClusters.isEmpty()) {
            Log.d(TAG, "No orphaned clusters found")
            return@withContext 0
        }

        Log.i(TAG, "Found ${orphanedClusters.size} orphaned clusters, fixing...")
        var fixedCount = 0

        for (cluster in orphanedClusters) {
            try {
                // Get the best quality face from this cluster
                val bestFace = detectedFaceDao.getBestFaceForCluster(cluster.clusterId)
                if (bestFace == null) {
                    Log.w(TAG, "Cluster ${cluster.clusterId} has no faces, deleting...")
                    faceClusterDao.deleteById(cluster.clusterId)
                    continue
                }

                // Get photo count for this cluster
                val photoCount = detectedFaceDao.getPhotoCountForCluster(cluster.clusterId)

                // Get next available display number
                val displayNumber = personDao.getNextDisplayNumber()

                // Create a person for this cluster
                val person = PersonEntity(
                    personId = UUID.randomUUID().toString(),
                    name = null,
                    representativeFaceId = bestFace.faceId,
                    photoCount = photoCount,
                    isHidden = false,
                    createdAt = System.currentTimeMillis(),
                    displayNumber = displayNumber
                )
                personDao.insert(person)

                // Link cluster to person
                faceClusterDao.assignToPerson(cluster.clusterId, person.personId)

                // Verify the link worked
                val verifiedCluster = faceClusterDao.getById(cluster.clusterId)
                if (verifiedCluster?.personId == person.personId) {
                    fixedCount++
                    Log.d(TAG, "Fixed orphaned cluster ${cluster.clusterId} -> person ${person.personId}")
                } else {
                    Log.e(TAG, "Failed to link cluster ${cluster.clusterId} to person ${person.personId}")
                    // Try once more
                    faceClusterDao.assignToPerson(cluster.clusterId, person.personId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fixing orphaned cluster ${cluster.clusterId}", e)
            }
        }

        Log.i(TAG, "Fixed $fixedCount orphaned clusters out of ${orphanedClusters.size}")
        return@withContext fixedCount
    }

    /**
     * Rebuild the HNSW index from database.
     * Useful after database restore or corruption.
     */
    suspend fun rebuildIndex() = indexMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Rebuilding face embedding index...")
                embeddingIndex.clear()

                val clusters = faceClusterDao.getAll()
                embeddingIndex.buildFromClusters(clusters)
                indexInitialized = true

                Log.i(TAG, "Index rebuilt with ${embeddingIndex.size()} clusters")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebuild index", e)
            }
        }
    }

    // ============ Index Synchronization ============

    /**
     * Verify integrity of the HNSW index against the database.
     *
     * Returns information about any discrepancies:
     * - Clusters in database but not in index (missing)
     * - Clusters in index but not in database (orphaned)
     *
     * Use this to detect and diagnose index corruption or desync.
     */
    suspend fun verifyIndexIntegrity(): IndexIntegrityResult = withContext(Dispatchers.IO) {
        ensureIndexInitialized()

        val dbClusterIds = faceClusterDao.getAll()
            .filter { it.centroidEmbedding != null }
            .map { it.clusterId }
            .toSet()

        val integrityResult = embeddingIndex.verifyIntegrity(dbClusterIds)

        Log.i(TAG, "Index integrity check: " +
                "valid=${integrityResult.isValid}, " +
                "indexSize=${integrityResult.indexSize}, " +
                "dbSize=${integrityResult.dbSize}, " +
                "missing=${integrityResult.missingInIndex.size}, " +
                "orphaned=${integrityResult.orphanedInIndex.size}")

        if (!integrityResult.isValid) {
            Log.w(TAG, "Index integrity issues found:")
            if (integrityResult.missingInIndex.isNotEmpty()) {
                Log.w(TAG, "  Missing in index: ${integrityResult.missingInIndex.take(10)}")
            }
            if (integrityResult.orphanedInIndex.isNotEmpty()) {
                Log.w(TAG, "  Orphaned in index: ${integrityResult.orphanedInIndex.take(10)}")
            }
        }

        IndexIntegrityResult(
            isValid = integrityResult.isValid,
            needsRebuild = integrityResult.needsRebuild,
            missingInIndex = integrityResult.missingInIndex.size,
            orphanedInIndex = integrityResult.orphanedInIndex.size,
            indexSize = integrityResult.indexSize,
            databaseSize = integrityResult.dbSize
        )
    }

    /**
     * Sync the HNSW index with the database, fixing any discrepancies.
     *
     * This is more efficient than a full rebuild when only a few clusters
     * are out of sync. For major discrepancies, use rebuildIndex() instead.
     *
     * @return Number of clusters fixed
     */
    suspend fun syncIndexWithDatabase(): Int = indexMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureIndexInitialized()

            val dbClusters = faceClusterDao.getAll()
                .filter { it.centroidEmbedding != null }
            val dbClusterIds = dbClusters.map { it.clusterId }.toSet()

            val integrityResult = embeddingIndex.verifyIntegrity(dbClusterIds)

            if (integrityResult.isValid) {
                Log.d(TAG, "Index already in sync with database")
                return@withContext 0
            }

            var fixedCount = 0

            // Remove orphaned entries from index
            for (orphanedId in integrityResult.orphanedInIndex) {
                try {
                    embeddingIndex.removeCluster(orphanedId)
                    fixedCount++
                    Log.d(TAG, "Removed orphaned cluster from index: $orphanedId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove orphaned cluster: $orphanedId", e)
                }
            }

            // Add missing entries to index
            val missingClusters = dbClusters.filter { it.clusterId in integrityResult.missingInIndex }
            for (cluster in missingClusters) {
                try {
                    val embedding = cluster.centroidEmbedding?.let {
                        FaceConverters.byteArrayToFloatArray(it)
                    }
                    if (embedding != null) {
                        embeddingIndex.addCluster(cluster.clusterId, embedding)
                        fixedCount++
                        Log.d(TAG, "Added missing cluster to index: ${cluster.clusterId}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add missing cluster: ${cluster.clusterId}", e)
                }
            }

            // Save updated index
            try {
                embeddingIndex.saveIndex()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save index after sync", e)
            }

            Log.i(TAG, "Index sync complete: fixed $fixedCount clusters")
            fixedCount
        }
    }

    /**
     * Ensure index is in sync after a merge/split operation.
     * Should be called after any operation that modifies clusters.
     *
     * @param removedClusterIds Clusters that were removed/merged
     * @param addedOrUpdatedClusters Clusters that were added or had their centroid updated
     */
    suspend fun syncIndexAfterOperation(
        removedClusterIds: List<String> = emptyList(),
        addedOrUpdatedClusters: List<FaceClusterEntity> = emptyList()
    ) = indexMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureIndexInitialized()

            // Remove deleted clusters
            for (clusterId in removedClusterIds) {
                try {
                    embeddingIndex.removeCluster(clusterId)
                    Log.v(TAG, "Removed cluster from index: $clusterId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove cluster from index: $clusterId", e)
                }
            }

            // Add or update clusters
            for (cluster in addedOrUpdatedClusters) {
                val embedding = cluster.centroidEmbedding?.let {
                    FaceConverters.byteArrayToFloatArray(it)
                }
                if (embedding != null) {
                    try {
                        embeddingIndex.addCluster(cluster.clusterId, embedding)
                        Log.v(TAG, "Updated cluster in index: ${cluster.clusterId}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update cluster in index: ${cluster.clusterId}", e)
                    }
                }
            }

            // Save index after batch operations
            if (removedClusterIds.isNotEmpty() || addedOrUpdatedClusters.isNotEmpty()) {
                try {
                    embeddingIndex.saveIndex()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save index after operation sync", e)
                }
            }
        }
    }

    /**
     * Result of index integrity verification.
     */
    data class IndexIntegrityResult(
        val isValid: Boolean,
        val needsRebuild: Boolean,
        val missingInIndex: Int,
        val orphanedInIndex: Int,
        val indexSize: Int,
        val databaseSize: Int
    )

    // ============ Cluster Refinement Pipeline ============

    /**
     * Detect clusters that might be incorrectly merged (contain multiple people).
     *
     * Analyzes intra-cluster variance and uses simple clustering to find
     * potential subgroups within a cluster.
     *
     * High variance indicates faces that are quite different from each other,
     * suggesting they might not all be the same person.
     *
     * @param minFacesForAnalysis Minimum faces in cluster to analyze (need enough data)
     * @param varianceThreshold Maximum acceptable intra-cluster variance
     * @return List of clusters that might need to be split
     */
    suspend fun detectPotentialSplits(
        minFacesForAnalysis: Int = 5,
        varianceThreshold: Float = 0.08f  // Clusters with variance > this are suspicious
    ): List<SplitCandidate> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Analyzing clusters for potential splits...")

        val clusters = faceClusterDao.getAll().filter { it.faceCount >= minFacesForAnalysis }
        val candidates = mutableListOf<SplitCandidate>()

        for (cluster in clusters) {
            try {
                val faces = detectedFaceDao.getFacesByClusterId(cluster.clusterId)
                    .filter { it.embedding != null }

                if (faces.size < minFacesForAnalysis) continue

                // Calculate intra-cluster variance
                val centroidBytes = cluster.centroidEmbedding ?: continue
                val centroid = FaceConverters.byteArrayToFloatArray(centroidBytes)

                var sumSquaredDist = 0f
                var minSimilarity = 1f
                var maxSimilarity = 0f

                for (face in faces) {
                    val embedding = FaceConverters.byteArrayToFloatArray(face.embedding!!)
                    val similarity = clusteringService.calculateCosineSimilarity(embedding, centroid)
                    val distance = 1f - similarity

                    sumSquaredDist += distance * distance
                    minSimilarity = minOf(minSimilarity, similarity)
                    maxSimilarity = maxOf(maxSimilarity, similarity)
                }

                val variance = sumSquaredDist / faces.size
                val similarityRange = maxSimilarity - minSimilarity

                // High variance or large similarity range suggests potential split
                if (variance > varianceThreshold || similarityRange > 0.35f) {
                    // Try to identify subgroups using simple clustering
                    val subgroups = detectSubgroups(faces, cluster.clusterId)

                    if (subgroups.size > 1) {
                        val confidence = calculateSplitConfidence(variance, similarityRange, subgroups.size)
                        candidates.add(
                            SplitCandidate(
                                clusterId = cluster.clusterId,
                                personId = cluster.personId,
                                faceCount = faces.size,
                                variance = variance,
                                similarityRange = similarityRange,
                                suggestedSubgroups = subgroups.size,
                                confidence = confidence,
                                shouldAutoSplit = confidence >= 0.70f
                            )
                        )
                        Log.d(TAG, "Potential split: cluster ${cluster.clusterId}, " +
                                "variance=${"%.4f".format(variance)}, " +
                                "range=${"%.3f".format(similarityRange)}, " +
                                "subgroups=${subgroups.size}, " +
                                "confidence=${"%.2f".format(confidence)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing cluster ${cluster.clusterId} for splits", e)
            }
        }

        Log.i(TAG, "Found ${candidates.size} potential split candidates")
        candidates.sortedByDescending { it.confidence }
    }

    /**
     * Detect subgroups within a cluster using simple clustering.
     *
     * Uses a greedy approach: for each face, find closest existing group
     * or create new group if too different from all existing groups.
     */
    private fun detectSubgroups(
        faces: List<DetectedFaceEntity>,
        clusterId: String
    ): List<List<String>> {
        if (faces.size < 3) return listOf(faces.map { it.faceId })

        val subgroupThreshold = 0.50f  // Faces must be this similar to join a subgroup
        val subgroups = mutableListOf<MutableList<DetectedFaceEntity>>()

        // Sort by quality to start with best faces as group seeds
        val sortedFaces = faces.sortedByDescending { it.confidence }

        for (face in sortedFaces) {
            val embedding = FaceConverters.byteArrayToFloatArray(face.embedding!!)
            var bestGroup: MutableList<DetectedFaceEntity>? = null
            var bestSimilarity = 0f

            // Find best matching existing group
            for (group in subgroups) {
                // Calculate average similarity to group members
                var sumSimilarity = 0f
                for (member in group) {
                    val memberEmbedding = FaceConverters.byteArrayToFloatArray(member.embedding!!)
                    sumSimilarity += clusteringService.calculateCosineSimilarity(embedding, memberEmbedding)
                }
                val avgSimilarity = sumSimilarity / group.size

                if (avgSimilarity > bestSimilarity) {
                    bestSimilarity = avgSimilarity
                    bestGroup = group
                }
            }

            // Join best group if similar enough, otherwise create new group
            if (bestSimilarity >= subgroupThreshold && bestGroup != null) {
                bestGroup.add(face)
            } else {
                subgroups.add(mutableListOf(face))
            }
        }

        // Filter out very small subgroups (likely noise)
        val significantGroups = subgroups.filter { it.size >= 2 }

        return if (significantGroups.isEmpty()) {
            listOf(faces.map { it.faceId })
        } else {
            significantGroups.map { group -> group.map { it.faceId } }
        }
    }

    /**
     * Calculate confidence that a cluster should be split.
     */
    private fun calculateSplitConfidence(
        variance: Float,
        similarityRange: Float,
        subgroupCount: Int
    ): Float {
        // Higher variance = more confident split is needed
        val varianceScore = (variance - 0.05f).coerceIn(0f, 0.15f) / 0.15f

        // Larger similarity range = more confident
        val rangeScore = (similarityRange - 0.25f).coerceIn(0f, 0.35f) / 0.35f

        // More subgroups = more confident
        val subgroupScore = when {
            subgroupCount >= 3 -> 1.0f
            subgroupCount == 2 -> 0.7f
            else -> 0.3f
        }

        return (varianceScore * 0.4f + rangeScore * 0.3f + subgroupScore * 0.3f)
    }

    /**
     * Detect clusters that might be the same person but were split.
     *
     * Finds pairs of clusters with high centroid-to-centroid similarity
     * and validates by comparing representative faces.
     *
     * @param minSimilarity Minimum similarity to consider as potential merge
     * @return List of cluster pairs that might be the same person
     */
    suspend fun detectPotentialMerges(
        minSimilarity: Float = 0.40f
    ): List<MergeCandidate> = withContext(Dispatchers.IO) {
        ensureIndexInitialized()

        Log.d(TAG, "Analyzing clusters for potential merges...")

        val clusters = faceClusterDao.getAll().filter { it.centroidEmbedding != null }
        val candidates = mutableListOf<MergeCandidate>()
        val processed = mutableSetOf<Pair<String, String>>()

        for (cluster in clusters) {
            try {
                // Use HNSW to find similar clusters efficiently
                val matches = embeddingIndex.findMergeCandidates(cluster.clusterId, minSimilarity)

                for (match in matches) {
                    // Skip already processed pairs
                    val pair = if (cluster.clusterId < match.clusterId) {
                        cluster.clusterId to match.clusterId
                    } else {
                        match.clusterId to cluster.clusterId
                    }
                    if (pair in processed) continue
                    processed.add(pair)

                    // Get representatives for cross-validation
                    val reps1 = clusterRepresentativeDao.getRepresentativesForCluster(cluster.clusterId)
                    val reps2 = clusterRepresentativeDao.getRepresentativesForCluster(match.clusterId)

                    // Calculate best representative-to-representative similarity
                    var bestRepSimilarity = 0f
                    for (rep1 in reps1) {
                        for (rep2 in reps2) {
                            val embedding1 = FaceConverters.byteArrayToFloatArray(rep1.embedding)
                            val embedding2 = FaceConverters.byteArrayToFloatArray(rep2.embedding)
                            val sim = clusteringService.calculateCosineSimilarity(embedding1, embedding2)
                            bestRepSimilarity = maxOf(bestRepSimilarity, sim)
                        }
                    }

                    // If representative similarity is also high, this is a strong merge candidate
                    if (bestRepSimilarity >= minSimilarity) {
                        val cluster2 = faceClusterDao.getById(match.clusterId)
                        val confidence = calculateMergeConfidence(match.similarity, bestRepSimilarity)

                        candidates.add(
                            MergeCandidate(
                                cluster1Id = cluster.clusterId,
                                cluster2Id = match.clusterId,
                                person1Id = cluster.personId,
                                person2Id = cluster2?.personId,
                                centroidSimilarity = match.similarity,
                                representativeSimilarity = bestRepSimilarity,
                                cluster1Size = cluster.faceCount,
                                cluster2Size = cluster2?.faceCount ?: 0,
                                confidence = confidence,
                                shouldAutoMerge = confidence >= 0.70f  // High confidence required for auto-merge
                            )
                        )

                        Log.d(TAG, "Potential merge: ${cluster.clusterId} <-> ${match.clusterId}, " +
                                "centroid=${"%.3f".format(match.similarity)}, " +
                                "rep=${"%.3f".format(bestRepSimilarity)}, " +
                                "confidence=${"%.2f".format(confidence)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing cluster ${cluster.clusterId} for merges", e)
            }
        }

        Log.i(TAG, "Found ${candidates.size} potential merge candidates")
        candidates.sortedByDescending { it.confidence }
    }

    /**
     * Calculate confidence that two clusters should be merged.
     */
    private fun calculateMergeConfidence(
        centroidSimilarity: Float,
        representativeSimilarity: Float
    ): Float {
        // Both centroid and representative similarity matter
        // Representative similarity is weighted more heavily as it's more reliable
        val centroidScore = (centroidSimilarity - 0.50f).coerceIn(0f, 0.30f) / 0.30f
        val repScore = (representativeSimilarity - 0.55f).coerceIn(0f, 0.25f) / 0.25f

        return centroidScore * 0.3f + repScore * 0.7f
    }

    /**
     * Run the full cluster refinement pipeline after a scan.
     *
     * This performs:
     * 1. Auto-merge high-confidence merge candidates
     * 2. Detect potential splits (for user review, not auto-applied)
     * 3. Fix any index desync
     * 4. Recalculate photo counts
     *
     * @return Summary of refinement actions taken
     */
    suspend fun runRefinementPipeline(): RefinementResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting cluster refinement pipeline...")

        var autoMergeCount = 0
        var splitCandidatesFound = 0
        var indexFixCount = 0

        try {
            // Step 1: Auto-merge DISABLED - batch clustering already handles grouping
            // The previous auto-merge was too aggressive, merging different people together
            val mergeCandidates = detectPotentialMerges(minSimilarity = 0.55f)
            Log.d(TAG, "Found ${mergeCandidates.size} merge candidates (auto-merge DISABLED, for review only)")

            // Auto-merge disabled - batch clustering creates correct groups
            // Users can manually merge if needed via UI

            // Step 1.5: Chinese Whispers DISABLED - was too aggressive
            // Previously merging 55+ clusters down to 5, different people together
            Log.i(TAG, "Chinese Whispers: DISABLED (batch clustering handles grouping)")

            // Step 2: Detect potential splits (for user review)
            val splitCandidates = detectPotentialSplits()
            splitCandidatesFound = splitCandidates.size

            // Note: We don't auto-split - this requires user confirmation
            // The splitCandidates are returned for UI display

            // Step 3: Sync index with database
            indexFixCount = syncIndexWithDatabase()

            // Step 4: Recalculate photo counts
            recalculateAllPhotoCounts()

            // Step 5: Fix any remaining orphaned clusters
            val orphanedFixed = fixOrphanedClusters()

            Log.i(TAG, "Refinement pipeline complete: " +
                    "merged=$autoMergeCount, " +
                    "splitCandidates=$splitCandidatesFound, " +
                    "indexFixes=$indexFixCount, " +
                    "orphanedFixed=$orphanedFixed")

            RefinementResult(
                autoMergesApplied = autoMergeCount,
                splitCandidatesFound = splitCandidatesFound,
                indexDiscrepanciesFixed = indexFixCount,
                orphanedClustersFixed = orphanedFixed,
                mergeCandidatesForReview = mergeCandidates.filter { !it.shouldAutoMerge },
                splitCandidatesForReview = splitCandidates.filter { !it.shouldAutoSplit }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during refinement pipeline", e)
            RefinementResult(
                autoMergesApplied = autoMergeCount,
                splitCandidatesFound = splitCandidatesFound,
                indexDiscrepanciesFixed = indexFixCount,
                orphanedClustersFixed = 0,
                mergeCandidatesForReview = emptyList(),
                splitCandidatesForReview = emptyList(),
                error = e.message
            )
        }
    }

    /**
     * Candidate for cluster split (might contain multiple people).
     */
    data class SplitCandidate(
        val clusterId: String,
        val personId: String?,
        val faceCount: Int,
        val variance: Float,
        val similarityRange: Float,
        val suggestedSubgroups: Int,
        val confidence: Float,  // 0-1, higher = more confident split is needed
        val shouldAutoSplit: Boolean
    )

    /**
     * Candidate for cluster merge (might be same person).
     */
    data class MergeCandidate(
        val cluster1Id: String,
        val cluster2Id: String,
        val person1Id: String?,
        val person2Id: String?,
        val centroidSimilarity: Float,
        val representativeSimilarity: Float,
        val cluster1Size: Int,
        val cluster2Size: Int,
        val confidence: Float,  // 0-1, higher = more confident merge is needed
        val shouldAutoMerge: Boolean
    )

    /**
     * Result of running the refinement pipeline.
     */
    data class RefinementResult(
        val autoMergesApplied: Int,
        val splitCandidatesFound: Int,
        val indexDiscrepanciesFixed: Int,
        val orphanedClustersFixed: Int,
        val mergeCandidatesForReview: List<MergeCandidate>,
        val splitCandidatesForReview: List<SplitCandidate>,
        val error: String? = null
    )

    fun close() {
        detectionService.close()
        embeddingGenerator.close()
    }

    /**
     * Result of processing a photo.
     */
    sealed class ProcessingResult {
        data class Success(val facesDetected: Int, val newClusters: Int) : ProcessingResult()
        object NoFaces : ProcessingResult()
        data class Error(val message: String) : ProcessingResult()
    }
}
