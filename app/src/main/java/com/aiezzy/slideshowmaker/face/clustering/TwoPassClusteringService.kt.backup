package com.aiezzy.slideshowmaker.face.clustering

import android.util.Log
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.dao.ClusterAnchorDao
import com.aiezzy.slideshowmaker.data.face.dao.ClusterStatisticsDao
import com.aiezzy.slideshowmaker.data.face.entities.*
import com.aiezzy.slideshowmaker.face.clustering.index.AnchorEmbeddingIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-Pass Clustering Service for production-grade face grouping.
 *
 * This service implements the two-pass clustering strategy:
 *
 * PASS 1: HIGH PRECISION (Purity Focus)
 * - Threshold: >= 0.70 (SAFE_SAME zone only)
 * - Goal: Create definite, pure clusters
 * - Action: Only commit faces with high confidence
 * - Defers: All uncertain faces for Pass 2
 *
 * PASS 2: CONTROLLED RECALL (Expansion Focus)
 * - Threshold: >= 0.55 with multi-anchor support
 * - Rule: NEVER merge clusters in Pass 2
 * - Requires: 2+ supporting anchors for uncertain matches
 * - Goal: Safely expand clusters using established anchors
 *
 * Key principles:
 * 1. Only ANCHOR-quality faces can create new clusters
 * 2. Low-quality faces can join but never influence cluster identity
 * 3. UNCERTAIN zone faces are NEVER assigned immediately
 * 4. Cross-pose bridging helps link split clusters
 */
@Singleton
class TwoPassClusteringService @Inject constructor(
    private val clusteringService: FaceClusteringService,
    private val anchorIndex: AnchorEmbeddingIndex,
    private val anchorDao: ClusterAnchorDao,
    private val statisticsDao: ClusterStatisticsDao
) {
    companion object {
        private const val TAG = "TwoPassClustering"

        // Pass 1 thresholds (high precision)
        private const val PASS1_HIGH_THRESHOLD = ClusteringZones.SAFE_SAME_THRESHOLD  // 0.60

        // Pass 2 thresholds (controlled recall)
        private const val PASS2_HIGH_CONFIDENCE = 0.62f   // High confidence for single anchor
        private const val PASS2_MEDIUM_THRESHOLD = 0.55f  // Medium threshold with multi-anchor
        private const val PASS2_MIN_SUPPORTING_ANCHORS = 2  // Require 2 supporting anchors

        // Maximum deferred faces to hold before forcing resolution
        private const val MAX_DEFERRED_FACES = 500

        // Maximum time to hold deferred faces (10 minutes)
        private const val MAX_DEFER_TIME_MS = 600_000L

        // ACCURACY FIX: Minimum similarity for Pass 2 low-confidence assignment
        // Raised from 0.52 to prevent false positives
        private const val PASS2_MIN_ASSIGNMENT_THRESHOLD = 0.55f

        // ACCURACY FIX: Required evidence gap for uncertain assignments
        private const val PASS2_EVIDENCE_GAP_REQUIRED = 0.10f
    }

    // Mutex for thread-safe access to deferred faces list
    private val deferredMutex = Mutex()

    // Deferred faces waiting for more evidence
    private val deferredFaces = mutableListOf<DeferredFace>()

    /**
     * Result of processing a batch of faces through Pass 1.
     */
    data class Pass1BatchResult(
        val assignedFaces: Map<String, String>,    // faceId -> clusterId
        val newClusters: List<String>,             // newly created cluster IDs
        val deferredFaces: List<DeferredFace>,     // faces deferred to Pass 2
        val newAnchors: List<ClusterAnchorEntity>, // new anchors created
        val displayOnlyFaces: List<String>         // faces visible but not clustered
    )

    /**
     * Result of Pass 2 processing.
     */
    data class Pass2BatchResult(
        val newAssignments: Map<String, String>,   // faceId -> clusterId
        val stillDeferred: List<DeferredFace>,     // faces still without assignment
        val suggestedMerges: List<PoseBridge>,     // potential cluster merges found
        val forcedNewClusters: List<String>        // clusters created for long-deferred faces
    )

    /**
     * Face data prepared for clustering.
     */
    data class FaceData(
        val faceId: String,
        val embedding: FloatArray,
        val qualityScore: Float,
        val sharpnessScore: Float,
        val eyeVisibilityScore: Float,
        val eulerY: Float?,
        val eulerZ: Float?,
        val photoUri: String,
        val photoTimestamp: Long?
    ) {
        val eligibility: AnchorEligibility
            get() = when {
                qualityScore >= 65f && sharpnessScore >= 15f &&
                        (eulerY == null || kotlin.math.abs(eulerY) <= 20f) &&
                        eyeVisibilityScore >= 7f -> AnchorEligibility.QUALIFIED_ANCHOR
                qualityScore >= 50f && sharpnessScore >= 10f -> AnchorEligibility.CLUSTERING_ONLY
                qualityScore >= 35f -> AnchorEligibility.DISPLAY_ONLY
                else -> AnchorEligibility.REJECTED
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FaceData) return false
            return faceId == other.faceId
        }

        override fun hashCode(): Int = faceId.hashCode()
    }

    /**
     * Process a batch of faces through Pass 1 (High Precision).
     *
     * This pass only commits faces with >= 0.70 similarity (SAFE_SAME zone).
     * All other faces are deferred to Pass 2.
     *
     * CRITICAL: Respects CANNOT_LINK constraints to prevent wrong merges.
     * Faces that appear together in the same photo are automatically constrained
     * to be in different clusters.
     *
     * @param faces List of faces to process (sorted by quality desc recommended)
     * @param temporalHint Optional temporal context for boosting
     * @param cannotLinkMap Map of face ID -> Set of face IDs that cannot be in the same cluster
     * @return Pass1BatchResult with assignments and deferred faces
     */
    suspend fun pass1HighPrecision(
        faces: List<FaceData>,
        temporalHint: TemporalHint? = null,
        cannotLinkMap: Map<String, Set<String>> = emptyMap()
    ): Pass1BatchResult = withContext(Dispatchers.Default) {
        val assigned = mutableMapOf<String, String>()
        val newClusters = mutableListOf<String>()
        val deferred = mutableListOf<DeferredFace>()
        val newAnchors = mutableListOf<ClusterAnchorEntity>()
        val displayOnly = mutableListOf<String>()

        // Get all active anchors from database for matching
        val dbAnchors = anchorDao.getAllActiveAnchors().toMutableList()

        // Track newly created anchors in this batch (CRITICAL: use these for matching too!)
        val batchNewAnchors = mutableListOf<ClusterAnchorEntity>()

        // Get cluster statistics for adaptive thresholds
        val allStats = statisticsDao.getAll()
        val statsMap = allStats.associateBy { it.clusterId }.toMutableMap()

        // CRITICAL: Track which faces from this batch are assigned to which clusters
        // This is used to check CANNOT_LINK constraints during clustering
        val clusterToAssignedFaces = mutableMapOf<String, MutableSet<String>>()

        Log.i(TAG, "Pass 1: Processing ${faces.size} faces against ${dbAnchors.size} DB anchors " +
                "(batch anchors will be added dynamically, constraints=${cannotLinkMap.size})")

        // Sort faces by quality (best first)
        val sortedFaces = faces.sortedByDescending { it.qualityScore }

        for (face in sortedFaces) {
            // CRITICAL: Combine DB anchors with newly created batch anchors for matching
            val allAnchors = dbAnchors + batchNewAnchors
            // Skip rejected faces
            if (face.eligibility == AnchorEligibility.REJECTED) {
                continue
            }

            // Display-only faces are visible but not clustered
            if (face.eligibility == AnchorEligibility.DISPLAY_ONLY) {
                displayOnly.add(face.faceId)
                continue
            }

            // Make clustering decision using anchor matching
            val decision = clusteringService.makeAnchorClusteringDecision(
                faceEmbedding = face.embedding,
                faceQuality = face.qualityScore,
                anchors = allAnchors,
                clusterStatistics = statsMap,
                temporalHint = temporalHint
            )

            // CRITICAL: Get the cannot-link faces for this face
            val cannotLinkFaces = cannotLinkMap[face.faceId] ?: emptySet()

            // Helper to check if a cluster violates constraints
            fun wouldViolateConstraint(clusterId: String): Boolean {
                if (cannotLinkFaces.isEmpty()) return false
                val facesInCluster = clusterToAssignedFaces[clusterId] ?: return false
                return facesInCluster.any { it in cannotLinkFaces }
            }

            // Helper to find first valid match that doesn't violate constraints
            fun findValidMatch(matches: List<AnchorMatch>, minSimilarity: Float): AnchorMatch? {
                for (match in matches) {
                    if (match.similarity >= minSimilarity && !wouldViolateConstraint(match.clusterId)) {
                        return match
                    }
                }
                return null
            }

            when (decision.zone) {
                ClusteringDecisionZone.SAFE_SAME -> {
                    // High confidence match - but check constraints first!
                    val bestMatch = decision.bestAnchorMatch!!

                    // Check if this assignment would violate a constraint
                    val validMatch = if (wouldViolateConstraint(bestMatch.clusterId)) {
                        // Try to find another valid match
                        Log.d(TAG, "Pass 1: Constraint violation for ${face.faceId.take(8)} -> ${bestMatch.clusterId.take(8)}, " +
                                "finding alternative...")
                        findValidMatch(decision.allAnchorMatches, ClusteringZones.SAFE_SAME_THRESHOLD)
                    } else {
                        bestMatch
                    }

                    if (validMatch != null) {
                        val clusterId = validMatch.clusterId
                        assigned[face.faceId] = clusterId

                        // Track this assignment for constraint checking
                        clusterToAssignedFaces.getOrPut(clusterId) { mutableSetOf() }.add(face.faceId)

                        // If anchor-quality, add as anchor (to BOTH lists for batch matching)
                        if (face.eligibility == AnchorEligibility.QUALIFIED_ANCHOR) {
                            val anchor = createAnchorFromFaceData(face, clusterId)
                            if (anchor != null) {
                                newAnchors.add(anchor)
                                batchNewAnchors.add(anchor)  // CRITICAL: Make available for same-batch matching
                            }
                        }

                        Log.v(TAG, "Pass 1: Assigned ${face.faceId.take(8)} to cluster ${clusterId.take(8)} " +
                                "(sim=${"%.3f".format(validMatch.similarity)})")
                    } else {
                        // All matches violate constraints - create new cluster if anchor-quality
                        if (face.eligibility == AnchorEligibility.QUALIFIED_ANCHOR) {
                            val newClusterId = UUID.randomUUID().toString()
                            newClusters.add(newClusterId)
                            assigned[face.faceId] = newClusterId
                            clusterToAssignedFaces.getOrPut(newClusterId) { mutableSetOf() }.add(face.faceId)

                            val anchor = createAnchorFromFaceData(face, newClusterId)
                            if (anchor != null) {
                                newAnchors.add(anchor)
                                batchNewAnchors.add(anchor)
                            }

                            Log.d(TAG, "Pass 1: Constraint forced new cluster ${newClusterId.take(8)} for ${face.faceId.take(8)}")
                        } else {
                            // Non-anchor with no valid match - defer
                            val deferredFace = DeferredFace(
                                faceId = face.faceId,
                                embedding = FaceConverters.floatArrayToByteArray(face.embedding),
                                qualityScore = face.qualityScore,
                                poseCategory = FaceClusteringService.PoseCategory.fromEulerY(face.eulerY ?: 0f).name,
                                photoUri = face.photoUri,
                                candidateClusterId = null,
                                candidateSimilarity = 0f,
                                allMatches = emptyList(),
                                timestamp = System.currentTimeMillis(),
                                photoTimestamp = face.photoTimestamp
                            )
                            deferred.add(deferredFace)
                        }
                    }
                }

                ClusteringDecisionZone.UNCERTAIN -> {
                    // Uncertain match - defer to Pass 2
                    // Filter out violating matches from candidate list
                    val validMatches = decision.allAnchorMatches.filter { !wouldViolateConstraint(it.clusterId) }
                    val bestValidMatch = validMatches.firstOrNull()

                    val deferredFace = DeferredFace(
                        faceId = face.faceId,
                        embedding = FaceConverters.floatArrayToByteArray(face.embedding),
                        qualityScore = face.qualityScore,
                        poseCategory = FaceClusteringService.PoseCategory.fromEulerY(face.eulerY ?: 0f).name,
                        photoUri = face.photoUri,
                        candidateClusterId = bestValidMatch?.clusterId,
                        candidateSimilarity = bestValidMatch?.similarity ?: 0f,
                        allMatches = validMatches,  // Only include valid matches
                        timestamp = System.currentTimeMillis(),
                        photoTimestamp = face.photoTimestamp
                    )
                    deferred.add(deferredFace)

                    Log.v(TAG, "Pass 1: Deferred ${face.faceId.take(8)} " +
                            "(sim=${"%.3f".format(decision.bestAnchorMatch?.similarity ?: 0f)})")
                }

                ClusteringDecisionZone.SAFE_DIFFERENT -> {
                    // No match found - create new cluster if anchor-quality
                    if (face.eligibility == AnchorEligibility.QUALIFIED_ANCHOR) {
                        val newClusterId = UUID.randomUUID().toString()
                        newClusters.add(newClusterId)
                        assigned[face.faceId] = newClusterId

                        // Track this assignment for constraint checking
                        clusterToAssignedFaces.getOrPut(newClusterId) { mutableSetOf() }.add(face.faceId)

                        // Create anchor for new cluster (BOTH lists for same-batch matching)
                        val anchor = createAnchorFromFaceData(face, newClusterId)
                        if (anchor != null) {
                            newAnchors.add(anchor)
                            batchNewAnchors.add(anchor)  // CRITICAL: Make available for same-batch matching
                        }

                        Log.v(TAG, "Pass 1: Created new cluster ${newClusterId.take(8)} for ${face.faceId.take(8)} " +
                                "(batchAnchors=${batchNewAnchors.size})")
                    } else {
                        // Non-anchor face with no match - defer for potential later assignment
                        // Filter matches to exclude constrained clusters
                        val validMatches = decision.allAnchorMatches.filter { !wouldViolateConstraint(it.clusterId) }

                        val deferredFace = DeferredFace(
                            faceId = face.faceId,
                            embedding = FaceConverters.floatArrayToByteArray(face.embedding),
                            qualityScore = face.qualityScore,
                            poseCategory = FaceClusteringService.PoseCategory.fromEulerY(face.eulerY ?: 0f).name,
                            photoUri = face.photoUri,
                            candidateClusterId = validMatches.firstOrNull()?.clusterId,
                            candidateSimilarity = validMatches.firstOrNull()?.similarity ?: 0f,
                            allMatches = validMatches,
                            timestamp = System.currentTimeMillis(),
                            photoTimestamp = face.photoTimestamp
                        )
                        deferred.add(deferredFace)
                    }
                }
            }
        }

        // Add to global deferred list (thread-safe)
        deferredMutex.withLock {
            deferredFaces.addAll(deferred)
        }

        val constraintViolations = if (cannotLinkMap.isNotEmpty()) {
            ", constraints checked"
        } else ""

        Log.i(TAG, "Pass 1 complete: ${assigned.size} assigned, ${newClusters.size} new clusters, " +
                "${deferred.size} deferred, ${displayOnly.size} display-only$constraintViolations")

        Pass1BatchResult(
            assignedFaces = assigned,
            newClusters = newClusters,
            deferredFaces = deferred,
            newAnchors = newAnchors,
            displayOnlyFaces = displayOnly
        )
    }

    /**
     * Process deferred faces through Pass 2 (Controlled Recall).
     *
     * This pass uses multi-anchor matching to resolve uncertain faces:
     * - Requires 2+ supporting anchors for assignment
     * - Never merges clusters (only adds faces to existing clusters)
     * - Creates new clusters for long-deferred anchor-quality faces
     *
     * @param temporalHint Optional temporal context for boosting
     * @return Pass2BatchResult with new assignments
     */
    suspend fun pass2ControlledRecall(
        temporalHint: TemporalHint? = null
    ): Pass2BatchResult = withContext(Dispatchers.Default) {
        val newAssignments = mutableMapOf<String, String>()
        val stillDeferred = mutableListOf<DeferredFace>()
        val suggestedMerges = mutableListOf<PoseBridge>()
        val forcedNewClusters = mutableListOf<String>()

        // Get fresh anchors (may have been added in Pass 1)
        val allAnchors = anchorDao.getAllActiveAnchors()

        // Get cluster statistics for adaptive thresholds
        val allStats = statisticsDao.getAll()
        val statsMap = allStats.associateBy { it.clusterId }

        val currentTime = System.currentTimeMillis()

        // Thread-safe copy of deferred faces
        val facesToProcess = deferredMutex.withLock { deferredFaces.toList() }

        Log.i(TAG, "Pass 2: Processing ${facesToProcess.size} deferred faces")

        val processedFaceIds = mutableSetOf<String>()

        for (face in facesToProcess) {
            if (face.faceId in processedFaceIds) continue
            processedFaceIds.add(face.faceId)

            val faceAge = currentTime - face.timestamp

            // Convert embedding from ByteArray to FloatArray
            val faceEmbedding = FaceConverters.byteArrayToFloatArray(face.embedding)

            // Re-match against all anchors (including newly added ones)
            val decision = clusteringService.makeAnchorClusteringDecision(
                faceEmbedding = faceEmbedding,
                faceQuality = face.qualityScore,
                anchors = allAnchors,
                clusterStatistics = statsMap,
                temporalHint = temporalHint
            )

            val bestMatch = decision.bestAnchorMatch

            // More aggressive assignment logic for Pass 2
            val canAssign = when {
                // SAFE_SAME: Always assign
                decision.zone == ClusteringDecisionZone.SAFE_SAME -> true

                // UNCERTAIN with sufficient evidence: Assign
                decision.zone == ClusteringDecisionZone.UNCERTAIN &&
                        !decision.requiresMoreEvidence -> true

                // High-confidence single anchor: Assign (new - more aggressive)
                bestMatch != null && bestMatch.similarity >= PASS2_HIGH_CONFIDENCE -> true

                // Multi-anchor support at lower threshold: Assign
                decision.zone == ClusteringDecisionZone.UNCERTAIN &&
                        bestMatch != null &&
                        bestMatch.similarity >= PASS2_MEDIUM_THRESHOLD &&
                        decision.supportingAnchorCount >= PASS2_MIN_SUPPORTING_ANCHORS -> true

                // Any match above minimum threshold: Assign (most aggressive)
                bestMatch != null && bestMatch.similarity >= PASS2_MEDIUM_THRESHOLD -> true

                // Long-deferred: Force decision
                faceAge >= MAX_DEFER_TIME_MS -> true

                else -> false
            }

            // Lower the minimum similarity threshold for assignment
            val minAssignThreshold = PASS2_MEDIUM_THRESHOLD

            if (canAssign && bestMatch != null && bestMatch.similarity >= minAssignThreshold) {
                // ACCURACY FIX: For uncertain zone, also require evidence gap
                val shouldAssign = if (decision.zone == ClusteringDecisionZone.UNCERTAIN) {
                    // Must have sufficient gap between best and second-best match
                    val evidenceGap = decision.evidenceGap
                    val hasGoodEvidence = evidenceGap >= PASS2_EVIDENCE_GAP_REQUIRED ||
                            decision.supportingAnchorCount >= PASS2_MIN_SUPPORTING_ANCHORS ||
                            bestMatch.similarity >= PASS2_HIGH_CONFIDENCE
                    hasGoodEvidence
                } else {
                    true  // SAFE_SAME always OK
                }

                if (shouldAssign) {
                    newAssignments[face.faceId] = bestMatch.clusterId
                    Log.v(TAG, "Pass 2: Assigned ${face.faceId.take(8)} to ${bestMatch.clusterId.take(8)} " +
                            "(sim=${"%.3f".format(bestMatch.similarity)}, gap=${"%.3f".format(decision.evidenceGap)}, " +
                            "support=${decision.supportingAnchorCount})")
                } else {
                    // Not enough evidence - keep deferred
                    stillDeferred.add(face)
                    Log.v(TAG, "Pass 2: Insufficient evidence for ${face.faceId.take(8)} " +
                            "(sim=${"%.3f".format(bestMatch.similarity)}, gap=${"%.3f".format(decision.evidenceGap)})")
                }
            } else if (faceAge >= MAX_DEFER_TIME_MS && face.qualityScore >= 65f) {
                // Long-deferred anchor-quality face: Create new cluster
                val newClusterId = UUID.randomUUID().toString()
                forcedNewClusters.add(newClusterId)
                newAssignments[face.faceId] = newClusterId
                Log.v(TAG, "Pass 2: Forced new cluster ${newClusterId.take(8)} for ${face.faceId.take(8)}")
            } else if (bestMatch != null && bestMatch.similarity >= PASS2_MIN_ASSIGNMENT_THRESHOLD) {
                // ACCURACY FIX: Require evidence gap even for "lower confidence" matches
                // This prevents false positives from aggressive assignment
                val evidenceGap = decision.evidenceGap
                if (evidenceGap >= PASS2_EVIDENCE_GAP_REQUIRED) {
                    newAssignments[face.faceId] = bestMatch.clusterId
                    Log.v(TAG, "Pass 2: Gap-verified assign ${face.faceId.take(8)} to ${bestMatch.clusterId.take(8)} " +
                            "(sim=${"%.3f".format(bestMatch.similarity)}, gap=${"%.3f".format(evidenceGap)})")
                } else {
                    // No good evidence - create new cluster if anchor-quality, else keep deferred
                    if (face.qualityScore >= 65f) {
                        val newClusterId = UUID.randomUUID().toString()
                        forcedNewClusters.add(newClusterId)
                        newAssignments[face.faceId] = newClusterId
                        Log.v(TAG, "Pass 2: No evidence, new cluster ${newClusterId.take(8)} for ${face.faceId.take(8)}")
                    } else {
                        stillDeferred.add(face)
                    }
                }
            } else {
                stillDeferred.add(face)
            }
        }

        // Remove processed faces from deferred list (thread-safe)
        deferredMutex.withLock {
            deferredFaces.removeAll { it.faceId in processedFaceIds && it.faceId !in stillDeferred.map { f -> f.faceId } }
            deferredFaces.clear()
            deferredFaces.addAll(stillDeferred)
        }

        // Look for cross-pose bridges between clusters (potential merge suggestions)
        suggestedMerges.addAll(findCrossPoseMerges())

        Log.i(TAG, "Pass 2 complete: ${newAssignments.size} assigned, ${stillDeferred.size} still deferred, " +
                "${suggestedMerges.size} merge suggestions")

        Pass2BatchResult(
            newAssignments = newAssignments,
            stillDeferred = stillDeferred,
            suggestedMerges = suggestedMerges,
            forcedNewClusters = forcedNewClusters
        )
    }

    /**
     * Process a single photo's faces through the two-pass system.
     *
     * Convenience method for processing faces from a single photo.
     * Runs Pass 1 immediately and schedules Pass 2 for later.
     *
     * @param faces List of faces to process
     * @param temporalHint Optional temporal context
     * @param cannotLinkMap Map of face ID -> Set of face IDs that cannot be in the same cluster
     */
    suspend fun processPhotoFaces(
        faces: List<FaceData>,
        temporalHint: TemporalHint? = null,
        cannotLinkMap: Map<String, Set<String>> = emptyMap()
    ): Pass1BatchResult {
        val pass1Result = pass1HighPrecision(faces, temporalHint, cannotLinkMap)

        // If we have many deferred faces, trigger Pass 2
        if (deferredFaces.size >= MAX_DEFERRED_FACES) {
            Log.i(TAG, "Deferred face threshold reached, triggering Pass 2")
            pass2ControlledRecall(temporalHint)
        }

        return pass1Result
    }

    /**
     * Finalize clustering for all deferred faces.
     *
     * Call this at the end of a scan to resolve all remaining deferred faces.
     */
    suspend fun finalizeAllDeferred(): Pass2BatchResult {
        Log.i(TAG, "Finalizing ${deferredFaces.size} deferred faces")
        return pass2ControlledRecall()
    }

    /**
     * Get count of currently deferred faces (thread-safe).
     */
    suspend fun getDeferredCount(): Int = deferredMutex.withLock { deferredFaces.size }

    /**
     * Get all deferred faces (for debugging/UI) - thread-safe.
     */
    suspend fun getDeferredFaces(): List<DeferredFace> = deferredMutex.withLock { deferredFaces.toList() }

    /**
     * Clear all deferred faces (thread-safe).
     */
    suspend fun clearDeferred() {
        deferredMutex.withLock { deferredFaces.clear() }
    }

    /**
     * Find potential cross-pose merges between clusters.
     */
    private suspend fun findCrossPoseMerges(): List<PoseBridge> {
        val clusterIds = anchorDao.getAllClusterIdsWithAnchors()
        val poseBridges = mutableListOf<PoseBridge>()

        // Compare each pair of clusters
        for (i in clusterIds.indices) {
            val anchors1 = anchorDao.getActiveAnchorsForCluster(clusterIds[i])

            for (j in i + 1 until clusterIds.size) {
                val anchors2 = anchorDao.getActiveAnchorsForCluster(clusterIds[j])

                val bridge = clusteringService.findCrossPoseBridge(
                    cluster1Id = clusterIds[i],
                    cluster1Anchors = anchors1,
                    cluster2Id = clusterIds[j],
                    cluster2Anchors = anchors2
                )

                if (bridge != null && bridge.isLikelySamePerson) {
                    poseBridges.add(
                        PoseBridge(
                            clusterIdA = bridge.cluster1Id,
                            clusterIdB = bridge.cluster2Id,
                            anchorIdA = bridge.anchor1Id,
                            anchorIdB = bridge.anchor2Id,
                            similarity = bridge.similarity,
                            poseA = bridge.pose1,
                            poseB = bridge.pose2,
                            confidence = bridge.confidence
                        )
                    )
                }
            }
        }

        return poseBridges
    }

    /**
     * Create an anchor entity from FaceData.
     */
    private fun createAnchorFromFaceData(face: FaceData, clusterId: String): ClusterAnchorEntity? {
        val poseCategory = FaceClusteringService.PoseCategory.fromEulerY(face.eulerY ?: 0f).name

        return ClusterAnchorEntity(
            anchorId = UUID.randomUUID().toString(),
            clusterId = clusterId,
            faceId = face.faceId,
            embedding = FaceConverters.floatArrayToByteArray(face.embedding),
            qualityScore = face.qualityScore,
            sharpnessScore = face.sharpnessScore,
            eyeVisibilityScore = face.eyeVisibilityScore,
            poseCategory = poseCategory,
            eulerY = face.eulerY ?: 0f,
            eulerZ = face.eulerZ ?: 0f,
            intraClusterMeanSimilarity = 0f,
            isActive = true,
            createdAt = System.currentTimeMillis(),
            lastMatchedAt = 0L,
            matchCount = 0
        )
    }

    /**
     * Update cluster statistics after Pass 1.
     *
     * Recalculates intra-cluster similarity for adaptive thresholds.
     */
    suspend fun updateClusterStatistics(clusterIds: List<String>) = withContext(Dispatchers.Default) {
        for (clusterId in clusterIds) {
            val anchors = anchorDao.getAnchorsForStatistics(clusterId)
            if (anchors.size < 2) continue

            val stats = clusteringService.calculateClusterStatisticsFromAnchors(
                clusterId = clusterId,
                anchors = anchors,
                totalFaceCount = anchors.size // This should come from face count
            )

            if (stats != null) {
                statisticsDao.upsert(stats)
                Log.v(TAG, "Updated stats for cluster ${clusterId.take(8)}: " +
                        "mean=${"%.3f".format(stats.meanSimilarity)}, " +
                        "threshold=${"%.3f".format(stats.acceptanceThreshold)}")
            }
        }
    }

    /**
     * Rebuild anchor index from database.
     */
    suspend fun rebuildAnchorIndex() = withContext(Dispatchers.Default) {
        val allAnchors = anchorDao.getAllActiveAnchors()
        anchorIndex.buildFromAnchors(allAnchors)
        Log.i(TAG, "Rebuilt anchor index with ${allAnchors.size} anchors")
    }
}
