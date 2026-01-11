package com.aiezzy.slideshowmaker.face.clustering

import android.util.Log
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.entities.AnchorEligibility
import com.aiezzy.slideshowmaker.data.face.entities.AnchorMatch
import com.aiezzy.slideshowmaker.data.face.entities.AnchorMatchDecision
import com.aiezzy.slideshowmaker.data.face.entities.ClusterAnchorEntity
import com.aiezzy.slideshowmaker.data.face.entities.ClusterRepresentativeEntity
import com.aiezzy.slideshowmaker.data.face.entities.ClusterStatisticsEntity
import com.aiezzy.slideshowmaker.data.face.entities.ClusteringDecisionZone
import com.aiezzy.slideshowmaker.data.face.entities.ClusteringZones
import com.aiezzy.slideshowmaker.data.face.entities.DetectedFaceEntity
import com.aiezzy.slideshowmaker.data.face.entities.FaceClusterEntity
import com.aiezzy.slideshowmaker.data.face.entities.TemporalHint
import com.aiezzy.slideshowmaker.face.clustering.index.FaceEmbeddingIndex
import com.aiezzy.slideshowmaker.face.embedding.FaceEmbeddingGenerator
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Production-grade face clustering service with HNSW indexing.
 *
 * Key features:
 * - O(log n) cluster lookup using HNSW index
 * - Multiple representative embeddings per cluster (no centroid drift)
 * - Quality-weighted similarity for better matching
 * - Constraint-aware clustering (must-link/cannot-link)
 * - Transitive merge detection for split cluster recovery
 * - Configurable thresholds for different use cases
 *
 * Matching algorithm:
 * 1. Use HNSW index to find candidate clusters in O(log n)
 * 2. Match against all representatives of candidate clusters
 * 3. Use best representative match (not centroid) for assignment
 * 4. Check constraints before final assignment
 */
class FaceClusteringService(
    private val embeddingGenerator: FaceEmbeddingGenerator
) {

    companion object {
        private const val TAG = "FaceClusteringService"

        /**
         * MULTI-STAGE CLUSTERING THRESHOLDS
         *
         * Enterprise-grade clustering using confidence zones instead of single threshold.
         * This approach prevents both:
         * - False merges (different people in same group)
         * - False splits (same person in multiple groups)
         *
         * The key insight: Uncertain faces (0.30-0.45 similarity) are STAGED
         * for batch verification instead of being immediately assigned.
         *
         * FaceNet 512-dim cosine similarity interpretation:
         * - >= 0.55: Definite same person (auto-assign immediately)
         * - 0.45-0.55: Likely same person (auto-assign with medium confidence)
         * - 0.30-0.45: UNCERTAIN ZONE - stage for batch verification
         * - < 0.30: Different person (create new cluster)
         *
         * Note: These thresholds are calibrated for MobileFaceNet which produces
         * lower similarity scores than FaceNet-512. Quality filtering prevents
         * bad faces from causing false merges.
         */

        // ============ STAGE 1: Initial Assignment Thresholds ============
        /** >= 0.65: Definite same person - auto-assign with HIGH confidence */
        const val DEFINITE_SAME_PERSON = 0.62f

        /** >= 0.55: Likely same person - auto-assign with MEDIUM confidence */
        const val LIKELY_SAME_PERSON = 0.52f

        /** Upper bound of uncertain zone */
        const val UNCERTAIN_ZONE_HIGH = 0.52f

        /** Lower bound of uncertain zone - faces in 0.45-0.55 are staged */
        const val UNCERTAIN_ZONE_LOW = 0.42f

        /** < 0.45: Different person - create new cluster */
        const val DEFINITELY_DIFFERENT = 0.42f

        // ============ STAGE 2: Cluster Merge Thresholds ============
        /** Centroid similarity threshold for auto-merge during refinement */
        const val CLUSTER_MERGE_THRESHOLD = 0.75f

        /** Threshold for showing merge suggestion to user */
        const val SUGGESTED_MERGE_THRESHOLD = 0.45f

        // ============ STAGE 3: Staging Verification ============
        /** Minimum gap between best and second-best match to resolve conflict */
        const val MIN_DECISION_GAP = 0.08f

        /** Maximum time a face can stay in staging before forced decision (ms) */
        const val MAX_STAGING_TIME_MS = 60_000L  // 1 minute

        // ============ Quality Requirements ============
        /** Minimum quality for a face to update cluster centroid */
        const val MIN_QUALITY_FOR_CENTROID = 0.60f

        /** Minimum quality for representative selection */
        const val MIN_REPRESENTATIVE_QUALITY = 0.55f

        // ============ Other Configuration ============
        /** Maximum representatives per cluster for pose diversity */
        const val MAX_REPRESENTATIVES = 7

        /** Maximum faces to evaluate for medoid calculation */
        private const val MAX_MEDOID_CANDIDATES = 20

        /** Quality weight in similarity calculation (0-1) - REDUCED for threshold stability */
        private const val QUALITY_WEIGHT = 0.10f  // Reduced from 0.20 to prevent crossing threshold zones

        /** Maximum quality boost to prevent crossing threshold boundaries */
        private const val MAX_QUALITY_BOOST = 0.08f  // Clamp boost to ±0.08

        /** Number of candidate clusters from HNSW for initial search */
        const val HNSW_CANDIDATES = 25

        /** Extended candidates for second-pass search */
        const val HNSW_EXTENDED_CANDIDATES = 50

        // ============ NEW: Multi-Anchor Matching ============
        /** Number of representatives to match against (top N by quality) */
        const val MULTI_ANCHOR_COUNT = 3

        // ============ NEW: Adaptive Threshold Configuration ============
        /** Cluster size thresholds for maturity levels */
        const val MATURE_CLUSTER_SIZE = 10
        const val GROWING_CLUSTER_SIZE = 5

        /** Threshold adjustments for cluster maturity (applied to base thresholds) */
        const val NEW_CLUSTER_THRESHOLD_BOOST = 0.05f      // Stricter for new clusters
        const val MATURE_CLUSTER_THRESHOLD_REDUCTION = 0.05f  // More lenient for established clusters

        // ============ Legacy Compatibility ============
        @Deprecated("Use LIKELY_SAME_PERSON instead", ReplaceWith("LIKELY_SAME_PERSON"))
        const val SAME_PERSON_THRESHOLD = LIKELY_SAME_PERSON

        @Deprecated("Use DEFINITE_SAME_PERSON instead", ReplaceWith("DEFINITE_SAME_PERSON"))
        const val HIGH_CONFIDENCE_THRESHOLD = DEFINITE_SAME_PERSON

        @Deprecated("Use CLUSTER_MERGE_THRESHOLD instead", ReplaceWith("CLUSTER_MERGE_THRESHOLD"))
        const val MERGE_THRESHOLD = CLUSTER_MERGE_THRESHOLD

        @Deprecated("Use DEFINITE_SAME_PERSON instead", ReplaceWith("DEFINITE_SAME_PERSON"))
        const val DEFINITE_MATCH_THRESHOLD = DEFINITE_SAME_PERSON

        const val DEFAULT_MERGE_THRESHOLD = CLUSTER_MERGE_THRESHOLD
    }

    /**
     * Match confidence levels for multi-stage clustering decisions.
     *
     * The key insight is the STAGED level - uncertain faces are held
     * for batch verification instead of making immediate decisions.
     */
    enum class MatchConfidence {
        /** >= 0.70: Definite match - auto-assign immediately, update centroid */
        HIGH,

        /** >= 0.60: Likely match - auto-assign, update centroid if quality > 0.60 */
        MEDIUM,

        /** 0.45-0.60: Uncertain - STAGE for batch verification, don't commit yet */
        STAGED,

        /** < 0.45: Different person - create new cluster */
        NEW_CLUSTER;

        companion object {
            /**
             * Determine confidence level from similarity score.
             * This is the core decision function for multi-stage clustering.
             */
            fun fromSimilarity(similarity: Float): MatchConfidence {
                return when {
                    similarity >= DEFINITE_SAME_PERSON -> HIGH
                    similarity >= LIKELY_SAME_PERSON -> MEDIUM
                    similarity >= DEFINITELY_DIFFERENT -> STAGED
                    else -> NEW_CLUSTER
                }
            }

            /**
             * Check if this confidence should update the cluster centroid.
             * Only HIGH confidence and MEDIUM with good quality should update.
             */
            fun shouldUpdateCentroid(confidence: MatchConfidence, quality: Float): Boolean {
                return when (confidence) {
                    HIGH -> true  // Always update for high confidence
                    MEDIUM -> quality >= MIN_QUALITY_FOR_CENTROID  // Only if quality is good
                    STAGED -> false  // Never update from staged faces
                    NEW_CLUSTER -> false  // N/A
                }
            }
        }
    }

    /**
     * Result of clustering decision with staging support.
     */
    data class ClusteringDecision(
        val confidence: MatchConfidence,
        val bestClusterId: String?,
        val bestSimilarity: Float,
        val secondBestClusterId: String? = null,
        val secondBestSimilarity: Float = 0f,
        val shouldStage: Boolean = false,
        val hasConflict: Boolean = false  // Multiple clusters with similar similarity
    ) {
        /** Whether this decision can be committed immediately */
        val canCommitImmediately: Boolean
            get() = confidence == MatchConfidence.HIGH || confidence == MatchConfidence.MEDIUM

        /** Whether this decision should create a new cluster */
        val shouldCreateNewCluster: Boolean
            get() = confidence == MatchConfidence.NEW_CLUSTER

        /** Gap between best and second-best match */
        val decisionGap: Float
            get() = bestSimilarity - secondBestSimilarity
    }

    /**
     * Pose categories for diverse representative selection.
     *
     * Ensures representatives cover different head orientations:
     * - FRONTAL: Direct face view (-15° to 15°)
     * - SLIGHT_LEFT/RIGHT: Mild turn (15° to 35°)
     * - PROFILE_LEFT/RIGHT: Strong turn (35° to 90°)
     *
     * By selecting representatives from each category, we improve
     * matching accuracy for faces at various angles.
     */
    enum class PoseCategory(val yawMin: Float, val yawMax: Float) {
        FRONTAL(-15f, 15f),
        SLIGHT_LEFT(-35f, -15f),
        SLIGHT_RIGHT(15f, 35f),
        PROFILE_LEFT(-90f, -35f),
        PROFILE_RIGHT(35f, 90f);

        companion object {
            /**
             * Determine pose category from euler Y angle (yaw).
             */
            fun fromEulerY(eulerY: Float): PoseCategory {
                return when {
                    eulerY >= -15f && eulerY <= 15f -> FRONTAL
                    eulerY > 15f && eulerY <= 35f -> SLIGHT_RIGHT
                    eulerY < -15f && eulerY >= -35f -> SLIGHT_LEFT
                    eulerY > 35f -> PROFILE_RIGHT
                    eulerY < -35f -> PROFILE_LEFT
                    else -> FRONTAL  // Default fallback
                }
            }

            /**
             * Get all categories in priority order for representative selection.
             * Frontal is most important, followed by slight angles, then profiles.
             */
            fun priorityOrder(): List<PoseCategory> = listOf(
                FRONTAL,
                SLIGHT_LEFT,
                SLIGHT_RIGHT,
                PROFILE_LEFT,
                PROFILE_RIGHT
            )
        }
    }

    /**
     * NEW: Cluster maturity levels for adaptive thresholding.
     *
     * The key insight: New clusters need stricter thresholds (more certainty required),
     * while established clusters can be more lenient (we're confident about who this person is).
     *
     * This prevents:
     * - False merges in new clusters (different person added too easily)
     * - False splits in mature clusters (same person rejected unnecessarily)
     */
    enum class ClusterMaturity {
        /** New cluster with 1-4 faces - need high certainty to add more faces */
        NEW,

        /** Growing cluster with 5-9 faces - standard thresholds */
        GROWING,

        /** Mature cluster with 10+ faces - can be more lenient */
        MATURE;

        companion object {
            /**
             * Determine cluster maturity from face count.
             */
            fun fromFaceCount(faceCount: Int): ClusterMaturity {
                return when {
                    faceCount >= MATURE_CLUSTER_SIZE -> MATURE
                    faceCount >= GROWING_CLUSTER_SIZE -> GROWING
                    else -> NEW
                }
            }
        }
    }

    /**
     * NEW: Adaptive thresholds based on cluster maturity.
     *
     * Returns (highThreshold, mediumThreshold) pair adjusted for cluster maturity.
     *
     * @param maturity The cluster's maturity level
     * @return Pair of (HIGH confidence threshold, MEDIUM confidence threshold)
     */
    fun getAdaptiveThresholds(maturity: ClusterMaturity): Pair<Float, Float> {
        return when (maturity) {
            ClusterMaturity.NEW -> {
                // Stricter for new clusters - need more certainty
                val high = DEFINITE_SAME_PERSON + NEW_CLUSTER_THRESHOLD_BOOST
                val medium = LIKELY_SAME_PERSON + NEW_CLUSTER_THRESHOLD_BOOST
                Pair(high, medium)
            }
            ClusterMaturity.GROWING -> {
                // Standard thresholds
                Pair(DEFINITE_SAME_PERSON, LIKELY_SAME_PERSON)
            }
            ClusterMaturity.MATURE -> {
                // More lenient for established clusters
                val high = DEFINITE_SAME_PERSON - MATURE_CLUSTER_THRESHOLD_REDUCTION
                val medium = LIKELY_SAME_PERSON - MATURE_CLUSTER_THRESHOLD_REDUCTION
                Pair(high, medium)
            }
        }
    }

    /**
     * NEW: Get adaptive threshold for a specific cluster.
     *
     * Convenience method that determines maturity and returns thresholds.
     */
    fun getAdaptiveThresholdsForCluster(cluster: FaceClusterEntity): Pair<Float, Float> {
        val maturity = ClusterMaturity.fromFaceCount(cluster.faceCount)
        return getAdaptiveThresholds(maturity)
    }

    /**
     * NEW: Multi-anchor matching against top representatives.
     *
     * Instead of comparing against centroid (mathematical average), compare against
     * actual high-quality faces (representatives). This prevents the "uncanny valley"
     * effect where the centroid doesn't look like any real face.
     *
     * @param faceEmbedding The new face to match
     * @param faceQuality Quality score of the new face
     * @param representatives List of cluster representatives
     * @return Best similarity score among top N representatives
     */
    fun matchAgainstMultipleAnchors(
        faceEmbedding: FloatArray,
        faceQuality: Float,
        representatives: List<ClusterRepresentativeEntity>
    ): Float {
        if (representatives.isEmpty()) return 0f

        // Take top N representatives by quality
        val topReps = representatives
            .sortedByDescending { it.qualityScore }
            .take(MULTI_ANCHOR_COUNT)

        // Find best match among top representatives
        return topReps.maxOfOrNull { rep ->
            val repEmbedding = FaceConverters.byteArrayToFloatArray(rep.embedding)
            calculateWeightedSimilarity(faceEmbedding, repEmbedding, faceQuality)
        } ?: 0f
    }

    /**
     * Calculate cosine similarity between two embeddings.
     * Public method for external use (e.g., from FaceRepository).
     */
    fun calculateCosineSimilarity(embedding1: FloatArray, embedding2: FloatArray): Float {
        return embeddingGenerator.calculateSimilarity(embedding1, embedding2)
    }

    /**
     * Result of clustering a single face.
     */
    /**
     * Result of clustering decision with multi-stage support.
     */
    data class ClusterResult(
        val clusterId: String,
        val isNewCluster: Boolean,
        val similarity: Float,
        val confidence: ClusterConfidence,
        // NEW: Staging support fields
        val shouldStage: Boolean = false,           // Whether to hold in staging buffer
        val hasConflict: Boolean = false,           // Multiple clusters with similar similarity
        val secondBestClusterId: String? = null,    // For conflict detection
        val secondBestSimilarity: Float = 0f
    ) {
        enum class ClusterConfidence {
            HIGH,        // >= 0.70 - definite match
            MEDIUM,      // >= 0.60 - likely match
            STAGED,      // 0.45-0.60 - uncertain, held for verification
            NEW_CLUSTER  // < 0.45 or created new cluster
        }

        /** Decision gap for conflict analysis */
        val decisionGap: Float
            get() = similarity - secondBestSimilarity

        /** Whether this result can be committed immediately (not staged) */
        val canCommitImmediately: Boolean
            get() = !shouldStage && confidence != ClusterConfidence.STAGED
    }

    /**
     * Cluster a face against existing clusters.
     *
     * MULTI-STAGE CLUSTERING ALGORITHM:
     *
     * Stage 1 (Immediate):
     * - >= 0.70 similarity: HIGH confidence - assign immediately
     * - >= 0.60 similarity: MEDIUM confidence - assign immediately
     * - 0.45-0.60: STAGED - uncertain, hold for batch verification
     * - < 0.45: NEW_CLUSTER - create new cluster
     *
     * Stage 2 (Verification - handled by verifyStagedFace):
     * - Compare staged face against ALL representatives (not just centroid)
     * - Check for conflicting matches
     * - Resolve based on decision gap
     *
     * @param faceEmbedding The embedding of the new face
     * @param faceQuality Quality score of the face (0-100)
     * @param existingClusters List of existing clusters
     * @return ClusterResult with assigned cluster ID and staging info
     */
    fun clusterFace(
        faceEmbedding: FloatArray,
        faceQuality: Float = 50f,
        existingClusters: List<FaceClusterEntity>
    ): ClusterResult {
        if (existingClusters.isEmpty()) {
            return ClusterResult(
                clusterId = UUID.randomUUID().toString(),
                isNewCluster = true,
                similarity = 1.0f,
                confidence = ClusterResult.ClusterConfidence.NEW_CLUSTER
            )
        }

        // Find best and second-best matching clusters for conflict detection
        var bestClusterId: String? = null
        var bestSimilarity = 0f
        var secondBestClusterId: String? = null
        var secondBestSimilarity = 0f

        for (cluster in existingClusters) {
            val centroidBytes = cluster.centroidEmbedding ?: continue
            val centroidEmbedding = FaceConverters.byteArrayToFloatArray(centroidBytes)

            val similarity = calculateWeightedSimilarity(
                faceEmbedding, centroidEmbedding, faceQuality
            )

            if (similarity > bestSimilarity) {
                // Current best becomes second best
                secondBestSimilarity = bestSimilarity
                secondBestClusterId = bestClusterId
                // New best
                bestSimilarity = similarity
                bestClusterId = cluster.clusterId
            } else if (similarity > secondBestSimilarity) {
                secondBestSimilarity = similarity
                secondBestClusterId = cluster.clusterId
            }
        }

        // Calculate decision gap for conflict detection
        val decisionGap = bestSimilarity - secondBestSimilarity
        val hasConflict = decisionGap < MIN_DECISION_GAP && secondBestSimilarity >= DEFINITELY_DIFFERENT

        // Determine confidence level using multi-stage thresholds
        val matchConfidence = MatchConfidence.fromSimilarity(bestSimilarity)

        Log.d(TAG, "Clustering decision: similarity=${"%.3f".format(bestSimilarity)}, " +
                "confidence=$matchConfidence, " +
                "gap=${"%.3f".format(decisionGap)}, " +
                "conflict=$hasConflict")

        return when (matchConfidence) {
            MatchConfidence.HIGH -> {
                // Definite match - assign immediately
                ClusterResult(
                    clusterId = bestClusterId!!,
                    isNewCluster = false,
                    similarity = bestSimilarity,
                    confidence = ClusterResult.ClusterConfidence.HIGH,
                    shouldStage = false
                )
            }
            MatchConfidence.MEDIUM -> {
                // Likely match - assign immediately, but track quality for centroid update
                ClusterResult(
                    clusterId = bestClusterId!!,
                    isNewCluster = false,
                    similarity = bestSimilarity,
                    confidence = ClusterResult.ClusterConfidence.MEDIUM,
                    shouldStage = false
                )
            }
            MatchConfidence.STAGED -> {
                // Uncertain - STAGE for batch verification
                // Don't commit yet, let the staging buffer handle it
                ClusterResult(
                    clusterId = bestClusterId ?: UUID.randomUUID().toString(),
                    isNewCluster = bestClusterId == null,
                    similarity = bestSimilarity,
                    confidence = ClusterResult.ClusterConfidence.STAGED,
                    shouldStage = true,
                    hasConflict = hasConflict,
                    secondBestClusterId = secondBestClusterId,
                    secondBestSimilarity = secondBestSimilarity
                )
            }
            MatchConfidence.NEW_CLUSTER -> {
                // Different person - create new cluster
                ClusterResult(
                    clusterId = UUID.randomUUID().toString(),
                    isNewCluster = true,
                    similarity = bestSimilarity,
                    confidence = ClusterResult.ClusterConfidence.NEW_CLUSTER,
                    shouldStage = false
                )
            }
        }
    }

    /**
     * Verify a staged face against all cluster representatives.
     *
     * This is the Stage 2 verification for faces in the uncertain zone (0.45-0.60).
     * Instead of just checking centroids, we check against all representatives
     * to get a more robust match.
     *
     * @param faceEmbedding The staged face embedding
     * @param faceQuality Quality score of the face
     * @param candidateClusterId The cluster suggested in Stage 1
     * @param representatives All representatives for the candidate cluster
     * @param allClusters All clusters for conflict checking
     * @return ClusteringDecision with final verdict
     */
    fun verifyStagedFace(
        faceEmbedding: FloatArray,
        faceQuality: Float,
        candidateClusterId: String,
        representatives: List<ClusterRepresentativeEntity>,
        allClusters: List<FaceClusterEntity>
    ): ClusteringDecision {
        // Match against all representatives of the candidate cluster
        var bestRepSimilarity = 0f
        for (rep in representatives) {
            val repEmbedding = FaceConverters.byteArrayToFloatArray(rep.embedding)
            val similarity = calculateWeightedSimilarity(faceEmbedding, repEmbedding, faceQuality)
            if (similarity > bestRepSimilarity) {
                bestRepSimilarity = similarity
            }
        }

        // Also check other clusters to detect conflicts
        var bestOtherClusterId: String? = null
        var bestOtherSimilarity = 0f

        for (cluster in allClusters) {
            if (cluster.clusterId == candidateClusterId) continue

            val centroidBytes = cluster.centroidEmbedding ?: continue
            val centroidEmbedding = FaceConverters.byteArrayToFloatArray(centroidBytes)
            val similarity = calculateWeightedSimilarity(faceEmbedding, centroidEmbedding, faceQuality)

            if (similarity > bestOtherSimilarity) {
                bestOtherSimilarity = similarity
                bestOtherClusterId = cluster.clusterId
            }
        }

        // Decision logic
        val decisionGap = bestRepSimilarity - bestOtherSimilarity
        val hasConflict = decisionGap < MIN_DECISION_GAP && bestOtherSimilarity >= DEFINITELY_DIFFERENT

        // Use the representative match if it's better than original centroid match
        val finalSimilarity = bestRepSimilarity
        val confidence = MatchConfidence.fromSimilarity(finalSimilarity)

        // Upgrade from STAGED to MEDIUM if representative match is strong enough
        val finalConfidence = when {
            finalSimilarity >= LIKELY_SAME_PERSON && !hasConflict -> MatchConfidence.MEDIUM
            finalSimilarity >= DEFINITE_SAME_PERSON -> MatchConfidence.HIGH
            hasConflict -> MatchConfidence.STAGED  // Keep staged if conflict
            finalSimilarity < DEFINITELY_DIFFERENT -> MatchConfidence.NEW_CLUSTER
            else -> confidence
        }

        Log.d(TAG, "Staged verification: repSimilarity=${"%.3f".format(bestRepSimilarity)}, " +
                "otherSimilarity=${"%.3f".format(bestOtherSimilarity)}, " +
                "gap=${"%.3f".format(decisionGap)}, " +
                "finalConfidence=$finalConfidence")

        return ClusteringDecision(
            confidence = finalConfidence,
            bestClusterId = if (finalConfidence != MatchConfidence.NEW_CLUSTER) candidateClusterId else null,
            bestSimilarity = finalSimilarity,
            secondBestClusterId = bestOtherClusterId,
            secondBestSimilarity = bestOtherSimilarity,
            shouldStage = finalConfidence == MatchConfidence.STAGED,
            hasConflict = hasConflict
        )
    }

    // ============================================================================
    // ANCHOR-BASED THREE-ZONE CLUSTERING SYSTEM
    // ============================================================================

    /**
     * Determine anchor eligibility for a face based on quality metrics.
     *
     * Only QUALIFIED_ANCHOR faces can:
     * - Create new clusters
     * - Be matched against
     * - Define cluster identity
     *
     * Thresholds:
     * - QUALIFIED_ANCHOR: Quality >= 65, Sharpness >= 15, Pose <= 20°, Eyes >= 7
     * - CLUSTERING_ONLY: Quality >= 50, Sharpness >= 10
     * - DISPLAY_ONLY: Quality >= 35
     * - REJECTED: Quality < 35
     */
    fun determineAnchorEligibility(
        overallScore: Float,
        sharpnessScore: Float,
        eulerY: Float?,
        eyeVisibilityScore: Float
    ): AnchorEligibility {
        val absYaw = abs(eulerY ?: 45f)

        return when {
            overallScore >= 65f && sharpnessScore >= 15f &&
                    absYaw <= 20f && eyeVisibilityScore >= 7f -> AnchorEligibility.QUALIFIED_ANCHOR

            overallScore >= 50f && sharpnessScore >= 10f -> AnchorEligibility.CLUSTERING_ONLY

            overallScore >= 35f -> AnchorEligibility.DISPLAY_ONLY

            else -> AnchorEligibility.REJECTED
        }
    }

    /**
     * Determine anchor eligibility from a DetectedFaceEntity.
     */
    fun determineAnchorEligibility(face: DetectedFaceEntity): AnchorEligibility {
        return determineAnchorEligibility(
            overallScore = face.qualityScore,
            sharpnessScore = face.sharpnessScore,
            eulerY = face.eulerY,
            eyeVisibilityScore = face.eyeVisibilityScore
        )
    }

    /**
     * Make a clustering decision using the three-zone model with anchor matching.
     *
     * The THREE-ZONE MODEL:
     * - SAFE_SAME (>= 0.70): Commit immediately to cluster
     * - UNCERTAIN (0.50-0.70): NEVER assign immediately - defer for evidence
     * - SAFE_DIFFERENT (< 0.50): Create new cluster (if anchor-quality) or defer
     *
     * Key principle: UNCERTAIN faces must wait for:
     * - Multiple anchor matches (2+ anchors above 0.50)
     * - Temporal evidence (same photo session)
     * - Cross-pose bridging (intermediate pose links)
     *
     * @param faceEmbedding The embedding of the face to cluster
     * @param faceQuality Quality score of the face (0-100)
     * @param anchors All available anchors to match against
     * @param clusterStatistics Per-cluster adaptive thresholds (optional)
     * @param temporalHint Temporal context hint for boosting uncertain matches
     * @return AnchorMatchDecision with zone, matches, and recommendation
     */
    fun makeAnchorClusteringDecision(
        faceEmbedding: FloatArray,
        faceQuality: Float,
        anchors: List<ClusterAnchorEntity>,
        clusterStatistics: Map<String, ClusterStatisticsEntity> = emptyMap(),
        temporalHint: TemporalHint? = null
    ): AnchorMatchDecision {
        if (anchors.isEmpty()) {
            // No anchors exist - this is first face or isolated cluster situation
            return AnchorMatchDecision(
                zone = ClusteringDecisionZone.SAFE_DIFFERENT,
                bestAnchorMatch = null,
                allAnchorMatches = emptyList(),
                requiresMoreEvidence = false,
                evidenceGap = 0f
            )
        }

        // Match against all anchors
        val allMatches = mutableListOf<AnchorMatch>()
        for (anchor in anchors) {
            if (!anchor.isActive) continue

            val anchorEmbedding = FaceConverters.byteArrayToFloatArray(anchor.embedding)
            val similarity = calculateWeightedSimilarity(faceEmbedding, anchorEmbedding, faceQuality)

            // Get adaptive threshold for this cluster if available
            val adaptiveThreshold = clusterStatistics[anchor.clusterId]?.acceptanceThreshold

            allMatches.add(
                AnchorMatch(
                    clusterId = anchor.clusterId,
                    anchorId = anchor.anchorId,
                    similarity = similarity,
                    poseCategory = anchor.poseCategory,
                    anchorQuality = anchor.qualityScore
                )
            )
        }

        // Sort by similarity (best first)
        allMatches.sortByDescending { it.similarity }

        val bestMatch = allMatches.firstOrNull()
        val secondBestMatch = allMatches.getOrNull(1)

        // Calculate evidence gap between best and second-best match
        val evidenceGap = if (bestMatch != null && secondBestMatch != null) {
            // If second-best is from a different cluster, it's a conflict indicator
            if (bestMatch.clusterId != secondBestMatch.clusterId) {
                bestMatch.similarity - secondBestMatch.similarity
            } else {
                // Same cluster - gap to next different cluster
                val differentClusterMatch = allMatches.firstOrNull { it.clusterId != bestMatch.clusterId }
                if (differentClusterMatch != null) {
                    bestMatch.similarity - differentClusterMatch.similarity
                } else {
                    1.0f  // No other clusters, full gap
                }
            }
        } else {
            1.0f  // Only one match, full gap
        }

        // Apply temporal boost if applicable
        val boostedSimilarity = if (temporalHint != null && bestMatch != null) {
            bestMatch.similarity + ClusteringZones.SESSION_TEMPORAL_BOOST
        } else {
            bestMatch?.similarity ?: 0f
        }

        // Count supporting anchors for the best match cluster
        val supportingAnchorCount = if (bestMatch != null) {
            allMatches.count {
                it.clusterId == bestMatch.clusterId &&
                        it.similarity >= ClusteringZones.UNCERTAIN_LOW
            }
        } else {
            0
        }

        // Determine decision zone based on boosted similarity
        val zone = determineClusteringZone(boostedSimilarity)

        // Check if we have enough evidence to commit in uncertain zone
        val requiresMoreEvidence = when (zone) {
            ClusteringDecisionZone.SAFE_SAME -> false  // Always can commit
            ClusteringDecisionZone.UNCERTAIN -> {
                // Can only commit if:
                // 1. Multiple supporting anchors (2+)
                // 2. Sufficient evidence gap
                val hasMultipleSupport = supportingAnchorCount >= ClusteringZones.MIN_SUPPORTING_ANCHORS
                val hasSufficientGap = evidenceGap >= ClusteringZones.MIN_EVIDENCE_GAP
                !(hasMultipleSupport && hasSufficientGap)
            }
            ClusteringDecisionZone.SAFE_DIFFERENT -> false  // Create new or defer
        }

        Log.d(TAG, "Anchor decision: zone=$zone, " +
                "bestSim=${"%.3f".format(bestMatch?.similarity ?: 0f)}, " +
                "boosted=${"%.3f".format(boostedSimilarity)}, " +
                "gap=${"%.3f".format(evidenceGap)}, " +
                "supporting=$supportingAnchorCount, " +
                "needsEvidence=$requiresMoreEvidence")

        return AnchorMatchDecision(
            zone = zone,
            bestAnchorMatch = bestMatch,
            allAnchorMatches = allMatches,
            requiresMoreEvidence = requiresMoreEvidence,
            evidenceGap = evidenceGap,
            temporalHint = temporalHint,
            supportingAnchorCount = supportingAnchorCount
        )
    }

    /**
     * Determine the clustering zone for a similarity score.
     */
    fun determineClusteringZone(similarity: Float): ClusteringDecisionZone {
        return when {
            similarity >= ClusteringZones.SAFE_SAME_THRESHOLD -> ClusteringDecisionZone.SAFE_SAME
            similarity >= ClusteringZones.SAFE_DIFFERENT_THRESHOLD -> ClusteringDecisionZone.UNCERTAIN
            else -> ClusteringDecisionZone.SAFE_DIFFERENT
        }
    }

    /**
     * Match a face against anchors for a specific cluster.
     *
     * @param faceEmbedding The face embedding to match
     * @param faceQuality Quality score of the face
     * @param anchors Anchors belonging to one cluster
     * @param clusterStats Optional statistics for adaptive threshold
     * @return Best similarity among all anchors
     */
    fun matchAgainstClusterAnchors(
        faceEmbedding: FloatArray,
        faceQuality: Float,
        anchors: List<ClusterAnchorEntity>,
        clusterStats: ClusterStatisticsEntity? = null
    ): Float {
        if (anchors.isEmpty()) return 0f

        val activeAnchors = anchors.filter { it.isActive }
        if (activeAnchors.isEmpty()) return 0f

        var bestSimilarity = 0f
        for (anchor in activeAnchors) {
            val anchorEmbedding = FaceConverters.byteArrayToFloatArray(anchor.embedding)
            val similarity = calculateWeightedSimilarity(faceEmbedding, anchorEmbedding, faceQuality)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
            }
        }

        return bestSimilarity
    }

    /**
     * Calculate adaptive acceptance threshold for a cluster based on its statistics.
     *
     * Formula: mean - 2 * stddev, clamped to [0.45, 0.65]
     *
     * This means:
     * - Consistent clusters (low stddev) get tighter thresholds
     * - Variable clusters (high stddev, e.g., glasses/no-glasses) get looser thresholds
     */
    fun calculateAdaptiveThreshold(stats: ClusterStatisticsEntity): Float {
        val threshold = stats.meanSimilarity - 2 * stats.similarityStdDev
        return threshold.coerceIn(0.45f, 0.65f)
    }

    /**
     * Calculate cluster statistics from anchor embeddings.
     *
     * Computes pairwise similarity statistics for adaptive thresholding:
     * - Mean similarity
     * - Variance and standard deviation
     * - Min and max similarity
     *
     * @param anchors Anchors in the cluster (need at least 2 for statistics)
     * @return ClusterStatisticsEntity or null if not enough anchors
     */
    fun calculateClusterStatisticsFromAnchors(
        clusterId: String,
        anchors: List<ClusterAnchorEntity>,
        totalFaceCount: Int
    ): ClusterStatisticsEntity? {
        if (anchors.size < 2) return null

        val activeAnchors = anchors.filter { it.isActive && it.embedding.isNotEmpty() }
        if (activeAnchors.size < 2) return null

        // Calculate all pairwise similarities
        val similarities = mutableListOf<Float>()
        for (i in activeAnchors.indices) {
            val embeddingI = FaceConverters.byteArrayToFloatArray(activeAnchors[i].embedding)
            for (j in i + 1 until activeAnchors.size) {
                val embeddingJ = FaceConverters.byteArrayToFloatArray(activeAnchors[j].embedding)
                val similarity = embeddingGenerator.calculateSimilarity(embeddingI, embeddingJ)
                similarities.add(similarity)
            }
        }

        if (similarities.isEmpty()) return null

        // Calculate statistics
        val mean = similarities.average().toFloat()
        val variance = similarities.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = sqrt(variance)
        val minSim = similarities.minOrNull() ?: 0f
        val maxSim = similarities.maxOrNull() ?: 0f

        // Calculate adaptive threshold
        val acceptanceThreshold = (mean - 2 * stdDev).coerceIn(0.45f, 0.65f)

        // Calculate pose distribution
        val poseDistribution = activeAnchors
            .groupingBy { it.poseCategory }
            .eachCount()
            .entries
            .joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }

        return ClusterStatisticsEntity(
            clusterId = clusterId,
            meanSimilarity = mean,
            similarityVariance = variance,
            similarityStdDev = stdDev,
            minSimilarity = minSim,
            maxSimilarity = maxSim,
            acceptanceThreshold = acceptanceThreshold,
            anchorCount = activeAnchors.size,
            totalFaceCount = totalFaceCount,
            poseDistribution = poseDistribution,
            lastUpdatedAt = System.currentTimeMillis(),
            sampleCount = similarities.size
        )
    }

    /**
     * Check if a face should be promoted to anchor status.
     *
     * Only QUALIFIED_ANCHOR faces can become anchors:
     * - Quality >= 65
     * - Sharpness >= 15
     * - Pose (yaw) <= 20°
     * - Eye visibility >= 7
     */
    fun shouldPromoteToAnchor(face: DetectedFaceEntity): Boolean {
        return determineAnchorEligibility(face) == AnchorEligibility.QUALIFIED_ANCHOR
    }

    /**
     * Create an anchor entity from a detected face.
     *
     * @param face The detected face to convert
     * @param clusterId The cluster this anchor belongs to
     * @return ClusterAnchorEntity ready for insertion
     */
    fun createAnchorFromFace(
        face: DetectedFaceEntity,
        clusterId: String
    ): ClusterAnchorEntity? {
        val embedding = face.embedding ?: return null

        val poseCategory = PoseCategory.fromEulerY(face.eulerY ?: 0f).name

        return ClusterAnchorEntity(
            anchorId = UUID.randomUUID().toString(),
            clusterId = clusterId,
            faceId = face.faceId,
            embedding = embedding,
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
     * Find cross-pose bridges between clusters.
     *
     * Cross-pose bridging connects frontal and profile views through intermediate poses:
     * FRONTAL ↔ SLIGHT_LEFT ↔ PROFILE_LEFT
     *
     * This helps identify same-person clusters that were split due to pose variation.
     *
     * @param cluster1Anchors Anchors from first cluster
     * @param cluster2Anchors Anchors from second cluster
     * @param minimumSimilarity Minimum similarity for a valid bridge (default 0.55)
     * @return Best cross-pose bridge or null if no valid bridge found
     */
    fun findCrossPoseBridge(
        cluster1Id: String,
        cluster1Anchors: List<ClusterAnchorEntity>,
        cluster2Id: String,
        cluster2Anchors: List<ClusterAnchorEntity>,
        minimumSimilarity: Float = 0.55f
    ): CrossPoseBridge? {
        if (cluster1Anchors.isEmpty() || cluster2Anchors.isEmpty()) return null

        var bestBridge: CrossPoseBridge? = null
        var bestConfidence = 0f

        for (anchor1 in cluster1Anchors.filter { it.isActive }) {
            val pose1 = PoseCategory.valueOf(anchor1.poseCategory)
            val embedding1 = FaceConverters.byteArrayToFloatArray(anchor1.embedding)

            for (anchor2 in cluster2Anchors.filter { it.isActive }) {
                val pose2 = PoseCategory.valueOf(anchor2.poseCategory)

                val embedding2 = FaceConverters.byteArrayToFloatArray(anchor2.embedding)
                val similarity = embeddingGenerator.calculateSimilarity(embedding1, embedding2)

                // Allow any pose combination - same person can have frontal and profile views
                // Adjacent poses get confidence boost, but non-adjacent are still valid
                if (similarity >= minimumSimilarity) {
                    // Confidence is based on both similarity and pose adjacency quality
                    val confidence = calculateBridgeConfidence(similarity, pose1, pose2)

                    if (confidence > bestConfidence) {
                        bestConfidence = confidence
                        bestBridge = CrossPoseBridge(
                            cluster1Id = cluster1Id,
                            cluster2Id = cluster2Id,
                            anchor1Id = anchor1.anchorId,
                            anchor2Id = anchor2.anchorId,
                            similarity = similarity,
                            pose1 = pose1.name,
                            pose2 = pose2.name,
                            confidence = confidence
                        )
                    }
                }
            }
        }

        return bestBridge
    }

    /**
     * Cross-pose bridge between two clusters.
     */
    data class CrossPoseBridge(
        val cluster1Id: String,
        val cluster2Id: String,
        val anchor1Id: String,
        val anchor2Id: String,
        val similarity: Float,
        val pose1: String,
        val pose2: String,
        val confidence: Float
    ) {
        val isLikelySamePerson: Boolean
            get() = similarity >= 0.60f && confidence >= 0.65f
    }

    /**
     * Check if two poses are adjacent (can form a valid bridge).
     */
    private fun arePosesAdjacent(pose1: PoseCategory, pose2: PoseCategory): Boolean {
        return when (pose1) {
            PoseCategory.FRONTAL -> pose2 in listOf(
                PoseCategory.FRONTAL, PoseCategory.SLIGHT_LEFT, PoseCategory.SLIGHT_RIGHT
            )
            PoseCategory.SLIGHT_LEFT -> pose2 in listOf(
                PoseCategory.FRONTAL, PoseCategory.SLIGHT_LEFT, PoseCategory.PROFILE_LEFT
            )
            PoseCategory.SLIGHT_RIGHT -> pose2 in listOf(
                PoseCategory.FRONTAL, PoseCategory.SLIGHT_RIGHT, PoseCategory.PROFILE_RIGHT
            )
            PoseCategory.PROFILE_LEFT -> pose2 in listOf(
                PoseCategory.SLIGHT_LEFT, PoseCategory.PROFILE_LEFT
            )
            PoseCategory.PROFILE_RIGHT -> pose2 in listOf(
                PoseCategory.SLIGHT_RIGHT, PoseCategory.PROFILE_RIGHT
            )
        }
    }

    /**
     * Calculate bridge confidence based on similarity and pose adjacency.
     */
    private fun calculateBridgeConfidence(similarity: Float, pose1: PoseCategory, pose2: PoseCategory): Float {
        // Base confidence from similarity (more weight on similarity)
        val baseConfidence = similarity

        // Bonus for same or adjacent poses
        val poseBonus = when {
            pose1 == pose2 -> 0.15f
            // FRONTAL to SLIGHT is very reliable
            pose1 == PoseCategory.FRONTAL && pose2 in listOf(PoseCategory.SLIGHT_LEFT, PoseCategory.SLIGHT_RIGHT) -> 0.12f
            pose2 == PoseCategory.FRONTAL && pose1 in listOf(PoseCategory.SLIGHT_LEFT, PoseCategory.SLIGHT_RIGHT) -> 0.12f
            // SLIGHT to PROFILE is fairly reliable
            arePosesAdjacent(pose1, pose2) -> 0.08f
            // Non-adjacent poses (frontal to profile) - still valid but lower confidence
            else -> 0.03f
        }

        return (baseConfidence + poseBonus).coerceIn(0f, 1f)
    }

    /**
     * Calculate similarity with quality weighting.
     *
     * REDUCED weighting (±8% max) to prevent crossing threshold zones:
     * - Quality 100%: +0.08 boost (was +0.25)
     * - Quality 50%: no change
     * - Quality 0%: -0.08 penalty (was -0.25)
     *
     * The boost is CLAMPED to MAX_QUALITY_BOOST to ensure:
     * - A borderline match (0.52) can't be boosted to safe match (0.60)
     * - Threshold zones remain stable regardless of face quality
     *
     * This helps prevent low-quality faces from creating false matches
     * while still giving a small advantage to high-quality matches.
     */
    private fun calculateWeightedSimilarity(
        embedding1: FloatArray,
        embedding2: FloatArray,
        quality: Float
    ): Float {
        val baseSimilarity = embeddingGenerator.calculateSimilarity(embedding1, embedding2)

        // Quality factor: normalize to 0-1 and apply weight
        // With QUALITY_WEIGHT=0.10 and clamp, this gives ±0.08 range max
        val qualityFactor = (quality / 100f).coerceIn(0f, 1f)
        val rawBoost = (qualityFactor - 0.5f) * QUALITY_WEIGHT * 2f

        // CLAMP the boost to prevent crossing threshold zones
        val qualityBoost = rawBoost.coerceIn(-MAX_QUALITY_BOOST, MAX_QUALITY_BOOST)

        // Apply clamped boost - similarity can't be pushed across zone boundaries by quality alone
        return (baseSimilarity + qualityBoost).coerceIn(-1f, 1f)
    }

    /**
     * Find clusters that should be merged based on centroid similarity.
     * Returns pairs of (source, target) cluster IDs.
     */
    fun findMergeCandidates(
        clusters: List<FaceClusterEntity>,
        threshold: Float = MERGE_THRESHOLD
    ): List<Pair<String, String>> {
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
                if (similarity >= threshold) {
                    // Put smaller cluster as source (will be merged into larger)
                    val pair = if (cluster1.faceCount < cluster2.faceCount) {
                        cluster1.clusterId to cluster2.clusterId
                    } else {
                        cluster2.clusterId to cluster1.clusterId
                    }
                    mergeCandidates.add(pair)
                    Log.d(TAG, "Merge candidate: ${pair.first} -> ${pair.second}, similarity=${"%.3f".format(similarity)}")
                }
            }
        }

        return mergeCandidates
    }

    /**
     * Find clusters that might be the same person but below auto-merge threshold.
     * These are suggested to the user for manual confirmation.
     */
    fun findSuggestedMerges(
        clusters: List<FaceClusterEntity>
    ): List<MergeSuggestion> {
        val suggestions = mutableListOf<MergeSuggestion>()

        for (i in clusters.indices) {
            val cluster1 = clusters[i]
            val centroid1Bytes = cluster1.centroidEmbedding ?: continue
            val centroid1 = FaceConverters.byteArrayToFloatArray(centroid1Bytes)

            for (j in i + 1 until clusters.size) {
                val cluster2 = clusters[j]
                val centroid2Bytes = cluster2.centroidEmbedding ?: continue
                val centroid2 = FaceConverters.byteArrayToFloatArray(centroid2Bytes)

                val similarity = embeddingGenerator.calculateSimilarity(centroid1, centroid2)

                // Suggest if between suggested threshold and auto-merge threshold
                if (similarity >= SUGGESTED_MERGE_THRESHOLD && similarity < MERGE_THRESHOLD) {
                    suggestions.add(
                        MergeSuggestion(
                            cluster1Id = cluster1.clusterId,
                            cluster2Id = cluster2.clusterId,
                            similarity = similarity,
                            confidence = calculateMergeConfidence(similarity)
                        )
                    )
                }
            }
        }

        return suggestions.sortedByDescending { it.similarity }
    }

    /**
     * Merge suggestion with confidence level.
     */
    data class MergeSuggestion(
        val cluster1Id: String,
        val cluster2Id: String,
        val similarity: Float,
        val confidence: String // "likely", "possible", "uncertain"
    )

    private fun calculateMergeConfidence(similarity: Float): String {
        return when {
            similarity >= 0.62f -> "likely"
            similarity >= 0.58f -> "possible"
            else -> "uncertain"
        }
    }

    /**
     * Calculate merged centroid for two clusters.
     * Uses weighted average based on cluster sizes.
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
        val weight1 = cluster1.faceCount.toFloat() / totalSize
        val weight2 = cluster2.faceCount.toFloat() / totalSize

        val mergedCentroid = FloatArray(centroid1.size)
        for (i in centroid1.indices) {
            mergedCentroid[i] = centroid1[i] * weight1 + centroid2[i] * weight2
        }

        return normalizeEmbedding(mergedCentroid)
    }

    /**
     * Find the best representative face for a cluster.
     * The representative is the face most similar to the cluster centroid.
     */
    fun findRepresentativeFace(
        faces: List<DetectedFaceEntity>,
        clusterCentroid: ByteArray
    ): String? {
        if (faces.isEmpty()) return null

        val centroid = FaceConverters.byteArrayToFloatArray(clusterCentroid)

        // Sample faces if too many (for performance)
        val candidateFaces = if (faces.size > MAX_MEDOID_CANDIDATES) {
            // Prioritize higher confidence faces
            faces.sortedByDescending { it.confidence }.take(MAX_MEDOID_CANDIDATES)
        } else {
            faces
        }

        var bestFaceId: String? = null
        var bestScore = Float.MIN_VALUE

        for (face in candidateFaces) {
            val faceEmbeddingBytes = face.embedding ?: continue
            val faceEmbedding = FaceConverters.byteArrayToFloatArray(faceEmbeddingBytes)

            // Score combines similarity to centroid and face quality
            val similarity = embeddingGenerator.calculateSimilarity(faceEmbedding, centroid)
            val qualityNormalized = face.confidence / 100f
            val score = similarity * 0.7f + qualityNormalized * 0.3f

            if (score > bestScore) {
                bestScore = score
                bestFaceId = face.faceId
            }
        }

        return bestFaceId
    }

    /**
     * Calculate similarity between a face and a cluster.
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
     * Recalculate cluster centroid from all faces.
     * More accurate than running average but more expensive.
     */
    fun recalculateCentroid(faces: List<DetectedFaceEntity>): FloatArray? {
        if (faces.isEmpty()) return null

        val validFaces = faces.filter { it.embedding != null }
        if (validFaces.isEmpty()) return null

        // Get first embedding to determine size
        val firstEmbedding = FaceConverters.byteArrayToFloatArray(validFaces[0].embedding!!)
        val centroid = FloatArray(firstEmbedding.size)

        // Sum all embeddings
        for (face in validFaces) {
            val embedding = FaceConverters.byteArrayToFloatArray(face.embedding!!)
            for (i in centroid.indices) {
                centroid[i] += embedding[i]
            }
        }

        // Average
        val count = validFaces.size.toFloat()
        for (i in centroid.indices) {
            centroid[i] = centroid[i] / count
        }

        return normalizeEmbedding(centroid)
    }

    /**
     * L2 normalize embedding to unit length.
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
     * Check if similarity indicates a high-confidence match.
     */
    fun isHighConfidenceMatch(similarity: Float): Boolean {
        return similarity >= HIGH_CONFIDENCE_THRESHOLD
    }

    /**
     * Get cluster quality metrics for debugging.
     */
    data class ClusterMetrics(
        val clusterId: String,
        val faceCount: Int,
        val averageIntraClusterSimilarity: Float,
        val centroidQuality: Float
    )

    fun analyzeCluster(
        clusterId: String,
        faces: List<DetectedFaceEntity>,
        centroid: ByteArray?
    ): ClusterMetrics {
        if (faces.isEmpty() || centroid == null) {
            return ClusterMetrics(clusterId, 0, 0f, 0f)
        }

        val centroidEmbedding = FaceConverters.byteArrayToFloatArray(centroid)
        var totalSimilarity = 0f
        var validCount = 0

        for (face in faces) {
            val embeddingBytes = face.embedding ?: continue
            val embedding = FaceConverters.byteArrayToFloatArray(embeddingBytes)
            totalSimilarity += embeddingGenerator.calculateSimilarity(embedding, centroidEmbedding)
            validCount++
        }

        val avgSimilarity = if (validCount > 0) totalSimilarity / validCount else 0f
        val centroidQuality = avgSimilarity * 100f  // Higher = tighter cluster

        return ClusterMetrics(
            clusterId = clusterId,
            faceCount = faces.size,
            averageIntraClusterSimilarity = avgSimilarity,
            centroidQuality = centroidQuality
        )
    }

    // ============ Enhanced Methods with HNSW Index and Representatives ============

    /**
     * Extended cluster result with representative match info.
     */
    data class EnhancedClusterResult(
        val clusterId: String,
        val isNewCluster: Boolean,
        val similarity: Float,
        val confidence: ClusterResult.ClusterConfidence,
        val matchedRepresentativeId: String? = null,
        val candidatesChecked: Int = 0
    )

    /**
     * Cluster a face using HNSW index for O(log n) lookup.
     *
     * Uses two-stage search for improved accuracy:
     * - Stage 1: Search top 25 candidates for quick match
     * - Stage 2: If best < HIGH confidence, search 50 candidates for thorough search
     *
     * This prevents good matches from being missed when the centroid-based HNSW
     * search ranks them lower than they should be (e.g., pose variations).
     *
     * @param faceEmbedding The embedding of the new face
     * @param faceQuality Quality score of the face (0-100)
     * @param embeddingIndex The HNSW index for cluster lookup
     * @param representativesProvider Function to get representatives for a cluster
     * @param cannotLinkFaceIds Face IDs that this face cannot be grouped with
     * @return EnhancedClusterResult with detailed match info
     */
    suspend fun clusterFaceWithIndex(
        faceEmbedding: FloatArray,
        faceQuality: Float = 50f,
        embeddingIndex: FaceEmbeddingIndex,
        representativesProvider: suspend (String) -> List<ClusterRepresentativeEntity>,
        cannotLinkFaceIds: Set<String> = emptySet()
    ): EnhancedClusterResult {
        if (embeddingIndex.isEmpty()) {
            return EnhancedClusterResult(
                clusterId = UUID.randomUUID().toString(),
                isNewCluster = true,
                similarity = 1.0f,
                confidence = ClusterResult.ClusterConfidence.NEW_CLUSTER
            )
        }

        // Stage 1: Initial search with standard candidate count
        var searchResult = searchCandidates(
            faceEmbedding = faceEmbedding,
            faceQuality = faceQuality,
            embeddingIndex = embeddingIndex,
            representativesProvider = representativesProvider,
            cannotLinkFaceIds = cannotLinkFaceIds,
            candidateCount = HNSW_CANDIDATES
        )

        // Stage 2: If not high confidence, do extended search for better accuracy
        // This catches cases where the true match has a different pose and ranks lower
        if (searchResult.bestSimilarity < HIGH_CONFIDENCE_THRESHOLD && searchResult.bestSimilarity > 0.20f) {
            Log.d(TAG, "Stage 1 similarity ${searchResult.bestSimilarity} < HIGH threshold, " +
                    "performing extended search with $HNSW_EXTENDED_CANDIDATES candidates")

            val extendedResult = searchCandidates(
                faceEmbedding = faceEmbedding,
                faceQuality = faceQuality,
                embeddingIndex = embeddingIndex,
                representativesProvider = representativesProvider,
                cannotLinkFaceIds = cannotLinkFaceIds,
                candidateCount = HNSW_EXTENDED_CANDIDATES,
                alreadyChecked = searchResult.checkedClusterIds
            )

            // Use extended result if it found a better match
            if (extendedResult.bestSimilarity > searchResult.bestSimilarity) {
                Log.d(TAG, "Extended search improved similarity: " +
                        "${searchResult.bestSimilarity} -> ${extendedResult.bestSimilarity}")
                searchResult = extendedResult
            }
        }

        val totalChecked = searchResult.checkedCount

        Log.d(TAG, "HNSW match: checked $totalChecked clusters, " +
                "best similarity=${"%.3f".format(searchResult.bestSimilarity)}, " +
                "threshold=$SAME_PERSON_THRESHOLD")

        // Capture values for smart cast (searchResult is a var so can't smart cast directly)
        val bestClusterId = searchResult.bestClusterId
        val bestSimilarity = searchResult.bestSimilarity
        val bestRepresentativeId = searchResult.bestRepresentativeId

        return when {
            bestSimilarity >= DEFINITE_MATCH_THRESHOLD && bestClusterId != null -> {
                EnhancedClusterResult(
                    clusterId = bestClusterId,
                    isNewCluster = false,
                    similarity = bestSimilarity,
                    confidence = ClusterResult.ClusterConfidence.HIGH,  // DEFINITE maps to HIGH for confidence enum
                    matchedRepresentativeId = bestRepresentativeId,
                    candidatesChecked = totalChecked
                )
            }
            bestSimilarity >= HIGH_CONFIDENCE_THRESHOLD && bestClusterId != null -> {
                EnhancedClusterResult(
                    clusterId = bestClusterId,
                    isNewCluster = false,
                    similarity = bestSimilarity,
                    confidence = ClusterResult.ClusterConfidence.HIGH,
                    matchedRepresentativeId = bestRepresentativeId,
                    candidatesChecked = totalChecked
                )
            }
            bestSimilarity >= SAME_PERSON_THRESHOLD && bestClusterId != null -> {
                EnhancedClusterResult(
                    clusterId = bestClusterId,
                    isNewCluster = false,
                    similarity = bestSimilarity,
                    confidence = ClusterResult.ClusterConfidence.MEDIUM,
                    matchedRepresentativeId = bestRepresentativeId,
                    candidatesChecked = totalChecked
                )
            }
            else -> {
                EnhancedClusterResult(
                    clusterId = UUID.randomUUID().toString(),
                    isNewCluster = true,
                    similarity = bestSimilarity,
                    confidence = ClusterResult.ClusterConfidence.NEW_CLUSTER,
                    candidatesChecked = totalChecked
                )
            }
        }
    }

    /**
     * Internal search result for two-stage search.
     */
    private data class CandidateSearchResult(
        val bestClusterId: String?,
        val bestSimilarity: Float,
        val bestRepresentativeId: String?,
        val checkedCount: Int,
        val checkedClusterIds: Set<String>
    )

    /**
     * Search through HNSW candidates and match against representatives.
     *
     * @param alreadyChecked Cluster IDs to skip (already checked in previous stage)
     */
    private suspend fun searchCandidates(
        faceEmbedding: FloatArray,
        faceQuality: Float,
        embeddingIndex: FaceEmbeddingIndex,
        representativesProvider: suspend (String) -> List<ClusterRepresentativeEntity>,
        cannotLinkFaceIds: Set<String>,
        candidateCount: Int,
        alreadyChecked: Set<String> = emptySet()
    ): CandidateSearchResult {
        // Find candidate clusters using HNSW (O(log n))
        val candidates = embeddingIndex.findNearestClusters(faceEmbedding, candidateCount)

        var bestClusterId: String? = null
        var bestSimilarity = 0f
        var bestRepresentativeId: String? = null
        var checkedCount = 0
        val checkedClusterIds = mutableSetOf<String>()

        for (candidate in candidates) {
            // Skip already checked clusters from previous stage
            if (candidate.clusterId in alreadyChecked) {
                continue
            }

            // Use very low threshold to not miss any potential matches
            // HNSW returns candidates sorted by CENTROID similarity, but representatives may match better
            if (candidate.similarity < 0.15f) {
                break
            }

            checkedCount++
            checkedClusterIds.add(candidate.clusterId)

            // Get representatives for this cluster
            val representatives = representativesProvider(candidate.clusterId)

            if (representatives.isEmpty()) {
                // Use the pre-computed centroid similarity from HNSW candidate
                val similarity = candidate.similarity
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestClusterId = candidate.clusterId
                }
                continue
            }

            // Match against all representatives
            for (rep in representatives) {
                // Check cannot-link constraints
                if (rep.faceId in cannotLinkFaceIds) {
                    Log.v(TAG, "Skipping cluster ${candidate.clusterId} due to cannot-link constraint")
                    continue
                }

                val repEmbedding = FaceConverters.byteArrayToFloatArray(rep.embedding)
                val similarity = calculateWeightedSimilarity(faceEmbedding, repEmbedding, faceQuality)

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestClusterId = candidate.clusterId
                    bestRepresentativeId = rep.faceId
                }
            }
        }

        return CandidateSearchResult(
            bestClusterId = bestClusterId,
            bestSimilarity = bestSimilarity,
            bestRepresentativeId = bestRepresentativeId,
            checkedCount = checkedCount,
            checkedClusterIds = checkedClusterIds
        )
    }

    /**
     * Select the best representatives for a cluster from a list of faces.
     *
     * Selection criteria:
     * 1. Quality score (higher is better)
     * 2. Diversity (avoid too similar representatives)
     * 3. Proximity to cluster centroid
     *
     * @param faces All faces in the cluster
     * @param clusterCentroid The cluster centroid (for proximity scoring)
     * @param maxRepresentatives Maximum number of representatives to select
     * @return List of selected face IDs with their scores
     */
    fun selectRepresentatives(
        faces: List<DetectedFaceEntity>,
        clusterCentroid: ByteArray?,
        maxRepresentatives: Int = MAX_REPRESENTATIVES
    ): List<RepresentativeCandidate> {
        if (faces.isEmpty()) return emptyList()

        val validFaces = faces.filter { it.embedding != null }
        if (validFaces.isEmpty()) return emptyList()

        val centroid = clusterCentroid?.let { FaceConverters.byteArrayToFloatArray(it) }

        // Score all faces
        val scoredFaces = validFaces.map { face ->
            val embedding = FaceConverters.byteArrayToFloatArray(face.embedding!!)
            val qualityScore = face.confidence / 100f

            // Proximity to centroid (if available)
            val centroidSimilarity = centroid?.let {
                embeddingGenerator.calculateSimilarity(embedding, it)
            } ?: 0.5f

            // Combined score: quality (60%) + centroid proximity (40%)
            val combinedScore = qualityScore * 0.6f + centroidSimilarity * 0.4f

            RepresentativeCandidate(
                faceId = face.faceId,
                embedding = embedding,
                qualityScore = qualityScore,
                combinedScore = combinedScore
            )
        }.sortedByDescending { it.combinedScore }

        // Select diverse representatives using greedy algorithm
        val selected = mutableListOf<RepresentativeCandidate>()
        val diversityThreshold = 0.85f // Reject if too similar to already selected

        for (candidate in scoredFaces) {
            if (selected.size >= maxRepresentatives) break

            // Check diversity
            val isTooSimilar = selected.any { existing ->
                embeddingGenerator.calculateSimilarity(candidate.embedding, existing.embedding) > diversityThreshold
            }

            if (!isTooSimilar || selected.isEmpty()) {
                selected.add(candidate)
            }
        }

        // Assign ranks
        return selected.mapIndexed { index, candidate ->
            candidate.copy(rank = index)
        }
    }

    /**
     * Candidate for cluster representative.
     */
    data class RepresentativeCandidate(
        val faceId: String,
        val embedding: FloatArray,
        val qualityScore: Float,
        val combinedScore: Float,
        val rank: Int = 0
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RepresentativeCandidate
            return faceId == other.faceId
        }

        override fun hashCode(): Int = faceId.hashCode()
    }

    /**
     * Calculate similarity between a face and a cluster using representatives.
     * Returns the best match among all representatives.
     */
    fun calculateFaceClusterSimilarityWithReps(
        faceEmbedding: FloatArray,
        representatives: List<ClusterRepresentativeEntity>
    ): Float {
        if (representatives.isEmpty()) return 0f

        var bestSimilarity = 0f
        for (rep in representatives) {
            val repEmbedding = FaceConverters.byteArrayToFloatArray(rep.embedding)
            val similarity = embeddingGenerator.calculateSimilarity(faceEmbedding, repEmbedding)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
            }
        }
        return bestSimilarity
    }

    /**
     * Find merge candidates using HNSW index.
     * Much faster than O(n²) pairwise comparison.
     */
    suspend fun findMergeCandidatesWithIndex(
        embeddingIndex: FaceEmbeddingIndex,
        threshold: Float = MERGE_THRESHOLD
    ): List<Pair<String, String>> {
        val clusterIds = embeddingIndex.getAllClusterIds().toList()
        val mergeCandidates = mutableListOf<Pair<String, String>>()
        val processed = mutableSetOf<Pair<String, String>>()

        for (clusterId in clusterIds) {
            val matches = embeddingIndex.findMergeCandidates(clusterId, threshold)

            for (match in matches) {
                // Avoid duplicate pairs
                val pair = if (clusterId < match.clusterId) {
                    clusterId to match.clusterId
                } else {
                    match.clusterId to clusterId
                }

                if (pair !in processed) {
                    processed.add(pair)
                    mergeCandidates.add(pair)
                    Log.d(TAG, "Merge candidate (HNSW): ${pair.first} -> ${pair.second}, " +
                            "similarity=${"%.3f".format(match.similarity)}")
                }
            }
        }

        return mergeCandidates
    }

    /**
     * Find clusters that should be merged transitively using Union-Find.
     * Uses connected components in similarity graph.
     *
     * If A matches B (0.35) and B matches C (0.35), they form a connected component
     * and should all be merged, even if A-C direct similarity is only 0.25.
     *
     * @param clusters All clusters to analyze
     * @param threshold Minimum similarity to create graph edge (default 0.30)
     * @return Map of representative clusterId -> list of clusterIds in that component
     */
    fun findTransitiveMergeCandidates(
        clusters: List<FaceClusterEntity>,
        threshold: Float = 0.30f
    ): Map<String, List<String>> {
        if (clusters.size < 2) return emptyMap()

        val clusterIds = clusters.map { it.clusterId }
        val unionFind = UnionFind(clusterIds)

        Log.d(TAG, "Building transitive merge graph for ${clusters.size} clusters at threshold $threshold")

        // Build similarity graph - O(n²) but necessary for transitivity
        var edgeCount = 0
        for (i in clusters.indices) {
            val embeddingA = clusters[i].centroidEmbedding?.let {
                FaceConverters.byteArrayToFloatArray(it)
            } ?: continue

            for (j in i + 1 until clusters.size) {
                val embeddingB = clusters[j].centroidEmbedding?.let {
                    FaceConverters.byteArrayToFloatArray(it)
                } ?: continue

                val similarity = calculateCosineSimilarity(embeddingA, embeddingB)
                if (similarity >= threshold) {
                    unionFind.union(clusters[i].clusterId, clusters[j].clusterId)
                    edgeCount++
                    Log.v(TAG, "Transitive edge: ${clusters[i].clusterId.take(8)} <-> ${clusters[j].clusterId.take(8)} (sim=${"%.3f".format(similarity)})")
                }
            }
        }

        // Get connected components with 2+ clusters (need merging)
        val components = unionFind.getComponents()
            .filter { it.value.size > 1 }

        Log.i(TAG, "Transitive merge: $edgeCount edges, ${components.size} components to merge")

        return components
    }
}

/**
 * Union-Find (Disjoint Set Union) data structure for efficient connected component detection.
 * Used for transitive cluster merging - O(α(n)) per operation where α is inverse Ackermann.
 *
 * Features:
 * - Path compression: Flattens tree during find operations
 * - Union by rank: Keeps trees balanced
 */
class UnionFind(private val ids: List<String>) {
    private val idToIndex = ids.withIndex().associate { it.value to it.index }
    private val parent = IntArray(ids.size) { it }
    private val rank = IntArray(ids.size)

    /**
     * Find the representative (root) of the set containing the given id.
     */
    fun find(id: String): String {
        val idx = idToIndex[id] ?: return id
        return ids[findRoot(idx)]
    }

    private fun findRoot(x: Int): Int {
        if (parent[x] != x) {
            parent[x] = findRoot(parent[x]) // Path compression
        }
        return parent[x]
    }

    /**
     * Union the sets containing id1 and id2.
     */
    fun union(id1: String, id2: String) {
        val idx1 = idToIndex[id1] ?: return
        val idx2 = idToIndex[id2] ?: return
        val root1 = findRoot(idx1)
        val root2 = findRoot(idx2)
        if (root1 == root2) return

        // Union by rank - attach smaller tree under larger
        when {
            rank[root1] < rank[root2] -> parent[root1] = root2
            rank[root1] > rank[root2] -> parent[root2] = root1
            else -> {
                parent[root2] = root1
                rank[root1]++
            }
        }
    }

    /**
     * Get all connected components as a map of representative -> members.
     */
    fun getComponents(): Map<String, List<String>> {
        return ids.groupBy { find(it) }
    }

    /**
     * Check if two ids are in the same component.
     */
    fun connected(id1: String, id2: String): Boolean {
        return find(id1) == find(id2)
    }
}

/**
 * Chinese Whispers graph clustering algorithm.
 *
 * Used by dlib for face clustering. Works by iteratively propagating
 * labels through a similarity graph until convergence.
 *
 * Key advantages:
 * - No need to specify number of clusters
 * - Naturally finds clusters based on connectivity
 * - Handles pose variation by connecting similar faces directly
 *
 * @param similarityThreshold Minimum similarity to create edge (default 0.65 for high confidence)
 * @param maxIterations Maximum iterations before stopping (default 50)
 */
class ChineseWhispersClustering(
    private val similarityThreshold: Float = 0.40f,
    private val maxIterations: Int = 50
) {
    private val TAG = "ChineseWhispers"

    data class FaceNode(
        val faceId: String,
        val embedding: FloatArray,
        var clusterId: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FaceNode) return false
            return faceId == other.faceId
        }
        override fun hashCode(): Int = faceId.hashCode()
    }

    /**
     * Cluster faces using Chinese Whispers algorithm.
     *
     * @param faces List of (faceId, embedding) pairs
     * @return Map of faceId -> clusterId (integer)
     */
    fun cluster(faces: List<Pair<String, FloatArray>>): Map<String, Int> {
        if (faces.isEmpty()) return emptyMap()
        if (faces.size == 1) return mapOf(faces[0].first to 0)

        // Initialize each face in its own cluster
        val nodes = faces.mapIndexed { index, (faceId, embedding) ->
            FaceNode(faceId, embedding, index)
        }

        // Build adjacency list with similarity weights
        val adjacency = buildWeightedAdjacency(nodes)

        Log.d(TAG, "Built graph: ${nodes.size} nodes, ${adjacency.values.sumOf { it.size } / 2} edges")

        // Iterate until convergence
        var iteration = 0
        var changed = true

        while (changed && iteration < maxIterations) {
            changed = false
            iteration++

            // Process nodes in random order (important for algorithm)
            val shuffled = nodes.indices.shuffled()

            for (nodeIdx in shuffled) {
                val node = nodes[nodeIdx]
                val neighbors = adjacency[node.faceId] ?: continue

                if (neighbors.isEmpty()) continue

                // Count weighted votes for each cluster label from neighbors
                val clusterVotes = mutableMapOf<Int, Float>()
                for ((neighborIdx, weight) in neighbors) {
                    val neighborCluster = nodes[neighborIdx].clusterId
                    clusterVotes[neighborCluster] = (clusterVotes[neighborCluster] ?: 0f) + weight
                }

                // Find cluster with highest vote
                val bestCluster = clusterVotes.maxByOrNull { it.value }?.key

                if (bestCluster != null && bestCluster != node.clusterId) {
                    node.clusterId = bestCluster
                    changed = true
                }
            }

            if (iteration % 10 == 0) {
                val numClusters = nodes.map { it.clusterId }.distinct().size
                Log.d(TAG, "Iteration $iteration: $numClusters clusters")
            }
        }

        val numClusters = nodes.map { it.clusterId }.distinct().size
        Log.i(TAG, "Converged after $iteration iterations: $numClusters clusters from ${nodes.size} faces")

        return nodes.associate { it.faceId to it.clusterId }
    }

    /**
     * Build adjacency list with similarity weights.
     * Only connects faces with similarity >= threshold.
     */
    private fun buildWeightedAdjacency(
        nodes: List<FaceNode>
    ): Map<String, List<Pair<Int, Float>>> {
        val adjacency = mutableMapOf<String, MutableList<Pair<Int, Float>>>()

        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val similarity = cosineSimilarity(nodes[i].embedding, nodes[j].embedding)

                if (similarity >= similarityThreshold) {
                    adjacency.getOrPut(nodes[i].faceId) { mutableListOf() }
                        .add(j to similarity)
                    adjacency.getOrPut(nodes[j].faceId) { mutableListOf() }
                        .add(i to similarity)
                }
            }
        }

        return adjacency
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

    /**
     * Merge clustering result into existing cluster structure.
     * Returns map of existingClusterId -> newClusterId for clusters that should merge.
     *
     * @param faceToExistingCluster Map of faceId -> existing clusterId
     * @param chineseWhispersResult Map of faceId -> Chinese Whispers cluster label
     * @return List of cluster sets that should be merged (each set contains clusterIds that should become one)
     */
    fun findMergeSets(
        faceToExistingCluster: Map<String, String>,
        chineseWhispersResult: Map<String, Int>
    ): List<Set<String>> {
        // Group faces by Chinese Whispers cluster
        val cwClusterToFaces = chineseWhispersResult.entries
            .groupBy { it.value }
            .mapValues { (_, entries) -> entries.map { it.key } }

        val mergeSets = mutableListOf<Set<String>>()

        for ((_, faceIds) in cwClusterToFaces) {
            // Get existing cluster IDs for faces in this CW cluster
            val existingClusterIds = faceIds
                .mapNotNull { faceToExistingCluster[it] }
                .distinct()
                .toSet()

            // If multiple existing clusters map to one CW cluster, they should merge
            if (existingClusterIds.size > 1) {
                mergeSets.add(existingClusterIds)
                Log.d(TAG, "Found merge set: ${existingClusterIds.size} clusters -> one group")
            }
        }

        return mergeSets
    }
}


