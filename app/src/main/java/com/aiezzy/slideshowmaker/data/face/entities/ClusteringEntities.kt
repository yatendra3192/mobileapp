package com.aiezzy.slideshowmaker.data.face.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores multiple representative embeddings per cluster for better matching.
 *
 * Instead of using a single centroid (which can drift), we store the top N
 * best quality face embeddings from the cluster. New faces are matched against
 * all representatives, using the best match.
 *
 * This provides:
 * - Better handling of pose/lighting variations
 * - No centroid drift over time
 * - More robust matching for diverse clusters
 *
 * Pose diversity: Representatives are selected to cover different head orientations
 * (frontal, left, right, profile) to improve matching accuracy.
 */
@Entity(
    tableName = "cluster_representatives",
    foreignKeys = [
        ForeignKey(
            entity = FaceClusterEntity::class,
            parentColumns = ["clusterId"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["clusterId"]),
        Index(value = ["clusterId", "rank"]),
        Index(value = ["clusterId", "poseCategory"])  // For pose-diverse queries
    ]
)
data class ClusterRepresentativeEntity(
    @PrimaryKey
    val id: String,
    val clusterId: String,
    val faceId: String,
    val embedding: ByteArray,
    val qualityScore: Float,
    val rank: Int,  // 0 = best representative

    // Pose information for diversity selection
    val eulerY: Float = 0f,  // Yaw angle (-90 to 90, negative = left, positive = right)
    val eulerZ: Float = 0f,  // Roll angle
    val poseCategory: String = "FRONTAL",  // FRONTAL, SLIGHT_LEFT, SLIGHT_RIGHT, PROFILE_LEFT, PROFILE_RIGHT

    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClusterRepresentativeEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Audit log for cluster operations to enable undo functionality.
 *
 * Records all significant operations:
 * - MERGE: Two clusters merged into one
 * - SPLIT: Cluster split into multiple
 * - MOVE_FACE: Face moved from one cluster to another
 * - CREATE: New cluster created
 * - DELETE: Cluster deleted
 * - RENAME: Person name changed
 *
 * For undo, we store enough data to reverse the operation.
 */
@Entity(
    tableName = "cluster_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["operationType"]),
        Index(value = ["clusterId"])
    ]
)
data class ClusterHistoryEntity(
    @PrimaryKey
    val historyId: String,
    val operationType: String,  // MERGE, SPLIT, MOVE_FACE, CREATE, DELETE, RENAME
    val timestamp: Long,

    // Primary affected cluster
    val clusterId: String,

    // For MERGE: the cluster that was merged in
    val sourceClusterId: String? = null,

    // For MOVE_FACE: the face that was moved
    val faceId: String? = null,

    // For RENAME: the old name
    val previousName: String? = null,

    // JSON-serialized data for complex undo operations
    val undoData: String? = null,

    // Whether this operation can be undone
    val canUndo: Boolean = true,

    // TTL for auto-cleanup (e.g., can't undo after 7 days)
    val expiresAt: Long? = null
)

/**
 * User-defined clustering constraints.
 *
 * Supports two types of constraints:
 * - MUST_LINK: These faces must be in the same cluster (user confirmed same person)
 * - CANNOT_LINK: These faces must NOT be in the same cluster (user confirmed different people)
 *
 * Constraints override automatic clustering decisions.
 */
@Entity(
    tableName = "clustering_constraints",
    indices = [
        Index(value = ["faceId1"]),
        Index(value = ["faceId2"]),
        Index(value = ["constraintType"])
    ]
)
data class ClusteringConstraintEntity(
    @PrimaryKey
    val constraintId: String,
    val constraintType: String,  // MUST_LINK or CANNOT_LINK
    val faceId1: String,
    val faceId2: String,
    val createdAt: Long,
    val createdBy: String = "user"  // "user" or "auto"
)

/**
 * Constraint types for clustering.
 */
object ConstraintType {
    const val MUST_LINK = "MUST_LINK"
    const val CANNOT_LINK = "CANNOT_LINK"
}

/**
 * Cluster operation types for history.
 */
object ClusterOperation {
    const val MERGE = "MERGE"
    const val SPLIT = "SPLIT"
    const val MOVE_FACE = "MOVE_FACE"
    const val CREATE = "CREATE"
    const val DELETE = "DELETE"
    const val RENAME = "RENAME"
}

/**
 * Extended cluster info with representatives.
 */
data class ClusterWithRepresentatives(
    val clusterId: String,
    val personId: String?,
    val faceCount: Int,
    val representatives: List<ClusterRepresentativeEntity>
)

// ============================================================================
// ANCHOR-BASED CLUSTERING SYSTEM
// ============================================================================

/**
 * Three-zone decision model for face clustering.
 *
 * Key insight: UNCERTAIN faces should NEVER be assigned immediately.
 * They must wait for more evidence (multiple anchor matches, temporal context, etc.)
 *
 * IMPORTANT: Thresholds are model-specific. MobileFaceNet produces LOWER scores
 * than FaceNet, so we need different thresholds for each model.
 */
object ClusteringZones {
    // ============================================================================
    // SOURCE-AWARE THRESHOLDS
    // Different models produce different similarity score ranges.
    // Using FaceNet thresholds with MobileFaceNet causes false negatives (too strict).
    // Using MobileFaceNet thresholds with FaceNet causes false positives (too loose).
    // ============================================================================

    // --- FaceNet 512-dim Thresholds (Higher scores) ---
    /** SAFE_SAME for FaceNet: >= 0.68 */
    const val FACENET_SAFE_SAME = 0.62f
    /** UNCERTAIN zone lower bound for FaceNet */
    const val FACENET_UNCERTAIN_LOW = 0.35f
    /** Pass 2 high confidence for FaceNet */
    const val FACENET_PASS2_HIGH = 0.50f
    /** Pass 2 multi-anchor threshold for FaceNet */
    const val FACENET_PASS2_MULTI = 0.45f

    // --- MobileFaceNet 192-dim Thresholds (Lower scores) ---
    /** SAFE_SAME for MobileFaceNet: >= 0.58 (reduced from 0.68) */
    const val MOBILEFACENET_SAFE_SAME = 0.58f
    /** UNCERTAIN zone lower bound for MobileFaceNet */
    const val MOBILEFACENET_UNCERTAIN_LOW = 0.42f
    /** Pass 2 high confidence for MobileFaceNet */
    const val MOBILEFACENET_PASS2_HIGH = 0.52f
    /** Pass 2 multi-anchor threshold for MobileFaceNet */
    const val MOBILEFACENET_PASS2_MULTI = 0.45f

    // --- Hash Fallback Thresholds (Very strict - low quality embeddings) ---
    /** SAFE_SAME for hash fallback: requires very high similarity */
    const val HASH_SAFE_SAME = 0.85f
    /** UNCERTAIN zone lower bound for hash fallback */
    const val HASH_UNCERTAIN_LOW = 0.70f

    // ============================================================================
    // DEFAULT THRESHOLDS (Used when source is unknown - conservative/strict)
    // ============================================================================

    /** SAFE_SAME: Definite match - commit immediately to cluster */
    const val SAFE_SAME_THRESHOLD = 0.62f  // High confidence required

    /** UNCERTAIN zone upper bound */
    const val UNCERTAIN_HIGH = 0.62f

    /** UNCERTAIN zone lower bound */
    const val UNCERTAIN_LOW = 0.35f

    /** SAFE_DIFFERENT: Definitely different person - create new cluster */
    const val SAFE_DIFFERENT_THRESHOLD = 0.35f

    /** Minimum evidence gap to resolve conflicts - INCREASED for accuracy */
    const val MIN_EVIDENCE_GAP = 0.10f  // Raised from 0.08f for stricter decisions

    /** Minimum supporting anchors for uncertain zone assignment */
    const val MIN_SUPPORTING_ANCHORS = 1

    /** Temporal boost for faces in same photo session */
    const val SESSION_TEMPORAL_BOOST = 0.05f

    /** High-confidence threshold for Pass 2 single-anchor assignment */
    const val PASS2_HIGH_CONFIDENCE = 0.62f

    /** Medium threshold for Pass 2 multi-anchor assignment */
    const val PASS2_MULTI_ANCHOR_THRESHOLD = 0.55f

    // ============================================================================
    // SOURCE-AWARE THRESHOLD FUNCTIONS
    // ============================================================================

    /**
     * Get SAFE_SAME threshold for the given embedding source.
     */
    fun getSafeSameThreshold(source: String): Float {
        return when (source) {
            "FACENET_512" -> FACENET_SAFE_SAME
            "MOBILEFACENET_192" -> MOBILEFACENET_SAFE_SAME
            "HASH_FALLBACK" -> HASH_SAFE_SAME
            else -> SAFE_SAME_THRESHOLD  // Default for unknown
        }
    }

    /**
     * Get UNCERTAIN zone lower bound for the given embedding source.
     */
    fun getUncertainLowThreshold(source: String): Float {
        return when (source) {
            "FACENET_512" -> FACENET_UNCERTAIN_LOW
            "MOBILEFACENET_192" -> MOBILEFACENET_UNCERTAIN_LOW
            "HASH_FALLBACK" -> HASH_UNCERTAIN_LOW
            else -> UNCERTAIN_LOW
        }
    }

    /**
     * Get Pass 2 high confidence threshold for the given embedding source.
     */
    fun getPass2HighThreshold(source: String): Float {
        return when (source) {
            "FACENET_512" -> FACENET_PASS2_HIGH
            "MOBILEFACENET_192" -> MOBILEFACENET_PASS2_HIGH
            "HASH_FALLBACK" -> HASH_SAFE_SAME  // Use safe threshold for hash
            else -> PASS2_HIGH_CONFIDENCE
        }
    }

    /**
     * Get Pass 2 multi-anchor threshold for the given embedding source.
     */
    fun getPass2MultiAnchorThreshold(source: String): Float {
        return when (source) {
            "FACENET_512" -> FACENET_PASS2_MULTI
            "MOBILEFACENET_192" -> MOBILEFACENET_PASS2_MULTI
            "HASH_FALLBACK" -> HASH_UNCERTAIN_LOW  // Use uncertain threshold for hash
            else -> PASS2_MULTI_ANCHOR_THRESHOLD
        }
    }
}

/**
 * Decision zones for face clustering.
 */
enum class ClusteringDecisionZone {
    /** >= 0.70: Commit immediately to cluster */
    SAFE_SAME,

    /** 0.50-0.70: Hold for evidence accumulation, never assign immediately */
    UNCERTAIN,

    /** < 0.50: Create new cluster (if anchor-quality) or defer */
    SAFE_DIFFERENT
}

/**
 * Anchor eligibility tiers based on face quality.
 *
 * Only QUALIFIED_ANCHOR faces can create clusters or be matched against.
 */
enum class AnchorEligibility {
    /** Quality >= 65, Sharpness >= 15, Pose <= 20°, Eyes >= 7: Can create clusters, be matched against */
    QUALIFIED_ANCHOR,

    /** Quality >= 50, Sharpness >= 10: Can join clusters but not be an anchor */
    CLUSTERING_ONLY,

    /** Quality >= 35: Visible in UI but excluded from clustering */
    DISPLAY_ONLY,

    /** Quality < 35: Not stored */
    REJECTED
}

/**
 * Anchor face that defines cluster identity.
 *
 * Only high-quality faces (ANCHOR tier) are stored here. These are the "gold standard"
 * faces that new faces are compared against. Low-quality faces can join clusters but
 * NEVER influence cluster identity.
 *
 * Key rules:
 * 1. Only ANCHOR faces can CREATE new clusters
 * 2. Only ANCHOR faces are stored in the matching index
 * 3. Face-to-face comparisons are PROHIBITED - only face→anchor
 * 4. Low-quality faces can NEVER influence cluster identity
 */
@Entity(
    tableName = "cluster_anchors",
    foreignKeys = [
        ForeignKey(
            entity = FaceClusterEntity::class,
            parentColumns = ["clusterId"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["clusterId"]),
        Index(value = ["clusterId", "poseCategory"]),
        Index(value = ["qualityScore"]),
        Index(value = ["isActive"])
    ]
)
data class ClusterAnchorEntity(
    @PrimaryKey
    val anchorId: String,

    /** Cluster this anchor belongs to */
    val clusterId: String,

    /** Reference to the original detected face */
    val faceId: String,

    /** Face embedding (512-dim or 192-dim depending on model) */
    val embedding: ByteArray,

    // Quality metrics (must meet ANCHOR tier requirements)
    /** Overall quality score (must be >= 65 for anchor) */
    val qualityScore: Float,

    /** Sharpness score from Laplacian variance (must be >= 15 for anchor) */
    val sharpnessScore: Float,

    /** Eye visibility score (must be >= 7 for anchor) */
    val eyeVisibilityScore: Float,

    // Pose information for diverse matching
    /** Pose category for pose-diverse anchor selection */
    val poseCategory: String,  // FRONTAL, SLIGHT_LEFT, SLIGHT_RIGHT, PROFILE_LEFT, PROFILE_RIGHT

    /** Yaw angle (-90 to 90, must be within [-20, 20] for anchor) */
    val eulerY: Float,

    /** Roll angle */
    val eulerZ: Float = 0f,

    // Statistics for adaptive thresholding
    /** Average similarity to other faces in the cluster */
    val intraClusterMeanSimilarity: Float = 0f,

    /** Whether this anchor is currently active for matching */
    val isActive: Boolean = true,

    /** When this anchor was created */
    val createdAt: Long,

    /** When this anchor was last used for a successful match */
    val lastMatchedAt: Long = 0L,

    /** How many times this anchor has been matched against */
    val matchCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClusterAnchorEntity
        return anchorId == other.anchorId
    }

    override fun hashCode(): Int = anchorId.hashCode()
}

/**
 * Per-cluster statistics for adaptive thresholding.
 *
 * Each cluster tracks its own internal consistency metrics to determine
 * an adaptive acceptance threshold. A cluster with consistent faces gets
 * a tighter threshold, while a cluster with variation (glasses/no-glasses,
 * different ages) gets a looser threshold.
 *
 * Threshold calculation: mean - 2 * stddev (clamped to [0.45, 0.65])
 */
@Entity(
    tableName = "cluster_statistics",
    foreignKeys = [
        ForeignKey(
            entity = FaceClusterEntity::class,
            parentColumns = ["clusterId"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["clusterId"], unique = true),
        Index(value = ["acceptanceThreshold"])
    ]
)
data class ClusterStatisticsEntity(
    @PrimaryKey
    val clusterId: String,

    // Intra-cluster similarity statistics
    /** Average pairwise similarity between all faces in cluster */
    val meanSimilarity: Float,

    /** Variance in pairwise similarity */
    val similarityVariance: Float,

    /** Standard deviation (sqrt of variance) */
    val similarityStdDev: Float,

    /** Lowest similarity between any two faces in cluster */
    val minSimilarity: Float,

    /** Highest similarity between any two faces in cluster */
    val maxSimilarity: Float,

    // Adaptive threshold
    /** Calculated acceptance threshold: mean - 2*stddev, clamped to [0.45, 0.65] */
    val acceptanceThreshold: Float,

    // Cluster composition
    /** Number of anchor faces in cluster */
    val anchorCount: Int,

    /** Total number of faces in cluster */
    val totalFaceCount: Int,

    /** JSON representation of pose distribution: {"FRONTAL": 5, "SLIGHT_LEFT": 3, ...} */
    val poseDistribution: String = "{}",

    /** When statistics were last recalculated */
    val lastUpdatedAt: Long,

    /** Number of pairwise comparisons used to calculate stats */
    val sampleCount: Int = 0
)

/**
 * Result of anchor matching decision.
 */
data class AnchorMatchDecision(
    val zone: ClusteringDecisionZone,
    val bestAnchorMatch: AnchorMatch?,
    val allAnchorMatches: List<AnchorMatch>,
    val requiresMoreEvidence: Boolean,
    val evidenceGap: Float,
    val temporalHint: TemporalHint? = null,
    val supportingAnchorCount: Int = 0
) {
    val canCommit: Boolean
        get() = zone == ClusteringDecisionZone.SAFE_SAME && !requiresMoreEvidence

    val shouldCreateNewCluster: Boolean
        get() = zone == ClusteringDecisionZone.SAFE_DIFFERENT

    val shouldDefer: Boolean
        get() = zone == ClusteringDecisionZone.UNCERTAIN || requiresMoreEvidence
}

/**
 * Single anchor match result.
 */
data class AnchorMatch(
    val clusterId: String,
    val anchorId: String,
    val similarity: Float,
    val poseCategory: String,
    val anchorQuality: Float
)

/**
 * Temporal context hints for boosting uncertain matches.
 */
enum class TemporalHint {
    /** Multiple faces detected in the same photo */
    SAME_PHOTO,

    /** Sequential burst mode photos */
    BURST_MODE,

    /** Within 1-hour photo session */
    SESSION_MATCH,

    /** Same album or folder */
    SAME_ALBUM
}

/**
 * Photo session for temporal continuity.
 * Photos within 1 hour are considered the same session.
 */
data class PhotoSession(
    val sessionId: String,
    val startTime: Long,
    val endTime: Long,
    val photoUris: MutableList<String> = mutableListOf(),
    val detectedClusterIds: MutableSet<String> = mutableSetOf()
) {
    /** Check if a timestamp falls within this session (1 hour window) */
    fun containsTime(timestamp: Long): Boolean {
        val SESSION_WINDOW_MS = 3600_000L  // 1 hour
        return timestamp >= startTime - SESSION_WINDOW_MS &&
               timestamp <= endTime + SESSION_WINDOW_MS
    }

    /** Add a photo to this session */
    fun addPhoto(photoUri: String, timestamp: Long) {
        photoUris.add(photoUri)
        if (timestamp < startTime) {
            // This shouldn't happen if we're processing in order
        }
        if (timestamp > endTime) {
            // Extend session
        }
    }
}

/**
 * Deferred face waiting for more evidence before assignment.
 */
data class DeferredFace(
    val faceId: String,
    val embedding: ByteArray,
    val qualityScore: Float,
    val poseCategory: String,
    val photoUri: String,
    val candidateClusterId: String?,
    val candidateSimilarity: Float,
    val allMatches: List<AnchorMatch>,
    val timestamp: Long,
    val photoTimestamp: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeferredFace
        return faceId == other.faceId
    }

    override fun hashCode(): Int = faceId.hashCode()
}

/**
 * Result of Pass 1 (high precision) clustering.
 */
data class Pass1Result(
    val assignedFaces: Map<String, String>,  // faceId -> clusterId
    val newClusters: List<String>,           // newly created cluster IDs
    val deferredFaces: List<DeferredFace>,   // faces deferred to Pass 2
    val displayOnlyFaces: List<String>       // faces visible but not clustered
)

/**
 * Result of Pass 2 (controlled recall) clustering.
 */
data class Pass2Result(
    val newAssignments: Map<String, String>,  // faceId -> clusterId
    val stillUnassigned: List<DeferredFace>,  // faces still without assignment
    val suggestedMerges: List<PoseBridge>     // potential cluster merges found via pose bridging
)

/**
 * Cross-pose bridge connecting two clusters via adjacent pose anchors.
 */
data class PoseBridge(
    val clusterIdA: String,
    val clusterIdB: String,
    val anchorIdA: String,
    val anchorIdB: String,
    val similarity: Float,
    val poseA: String,
    val poseB: String,
    val confidence: Float
) {
    /** Whether this bridge represents a likely same-person connection */
    val isLikelySamePerson: Boolean
        get() = similarity >= 0.60f && confidence >= 0.65f
}
