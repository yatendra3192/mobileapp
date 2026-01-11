package com.aiezzy.slideshowmaker.data.face.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a photo that has been scanned for faces
 */
@Entity(
    tableName = "scanned_photos",
    indices = [
        Index(value = ["scanStatus"]),  // For querying pending photos
        Index(value = ["dateAdded"])    // For sorting by date
    ]
)
data class ScannedPhotoEntity(
    @PrimaryKey
    val photoUri: String,
    val dateAdded: Long,
    val dateTaken: Long?,
    val scanStatus: String,  // PENDING, SCANNED, FAILED, NO_FACES
    val lastScanned: Long?,
    val displayName: String? = null,
    val bucketName: String? = null  // Album/folder name
)

/**
 * Represents a detected face in a photo
 */
@Entity(
    tableName = "detected_faces",
    foreignKeys = [
        ForeignKey(
            entity = ScannedPhotoEntity::class,
            parentColumns = ["photoUri"],
            childColumns = ["photoUri"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["photoUri"]),
        Index(value = ["clusterId"]),
        // Composite index for representative face selection (highest confidence per cluster)
        Index(value = ["clusterId", "confidence"]),
        // Composite index for best matching face selection (thumbnail)
        Index(value = ["clusterId", "matchScore"]),
        // Composite index for person-photo queries (efficient joins)
        Index(value = ["photoUri", "clusterId"]),
        // Index for soft delete queries
        Index(value = ["deletedAt"]),
        // Index for assignment confidence queries (staging buffer)
        Index(value = ["assignmentConfidence"]),
        // Composite index for quality-based queries
        Index(value = ["clusterId", "qualityScore"])
    ]
)
data class DetectedFaceEntity(
    @PrimaryKey
    val faceId: String,
    val photoUri: String,
    val boundingBoxLeft: Float,
    val boundingBoxTop: Float,
    val boundingBoxRight: Float,
    val boundingBoxBottom: Float,
    val embedding: ByteArray?,  // 512-dim float array serialized (FaceNet)
    val confidence: Float,      // Face detection confidence (from ML Kit) - DEPRECATED, use qualityScore
    val clusterId: String?,
    val imageWidth: Int,
    val imageHeight: Int,
    // Match score - how well this face matches its cluster (0.0-1.0, higher = better match)
    // This is the similarity score when the face was assigned to the cluster
    val matchScore: Float? = null,
    // Soft delete timestamp - null means not deleted
    val deletedAt: Long? = null,

    // ============ NEW: Quality breakdown for better filtering ============
    // Overall quality score (0-100) - composite of all quality metrics
    val qualityScore: Float = 0f,
    // Individual quality components (each 0-25 typically)
    val sizeScore: Float = 0f,       // Based on face pixel size
    val poseScore: Float = 0f,       // Based on head rotation angles
    val sharpnessScore: Float = 0f,  // Laplacian variance blur detection
    val brightnessScore: Float = 0f, // Lighting quality (under/overexposed)
    val eyeVisibilityScore: Float = 0f, // Both eyes detected and visible

    // ============ NEW: Clustering metadata for staging buffer ============
    // Assignment confidence: HIGH, MEDIUM, STAGED, or NEW
    val assignmentConfidence: String = AssignmentConfidence.NEW,
    // When the face was verified and committed from staging (null if not yet committed)
    val verifiedAt: Long? = null,
    // Quality of the face alignment during embedding generation (0-1)
    val embeddingQuality: Float = 0f,
    // Euler angles for pose tracking
    val eulerY: Float? = null,  // Yaw (left/right turn)
    val eulerZ: Float? = null,  // Roll (head tilt)

    // ============ NEW: Quality tier for clustering decisions ============
    // Quality tier: ANCHOR, CLUSTERING, DISPLAY_ONLY, or REJECTED
    // Determines what this face can do in the clustering pipeline
    val qualityTier: String = QualityTier.CLUSTERING,
    // Whether this face can form a new cluster (only ANCHOR tier)
    val canFormCluster: Boolean = false,
    // Whether this face can update cluster centroid (ANCHOR or CLUSTERING tier)
    val canUpdateCentroid: Boolean = true,

    // ============ NEW: Embedding source tracking ============
    // Source of the embedding: FACENET_512, MOBILEFACENET_192, HASH_FALLBACK
    val embeddingSource: String = EmbeddingSource.UNKNOWN,
    // Alignment quality when embedding was generated (0-1, higher = better alignment)
    val alignmentQuality: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DetectedFaceEntity

        if (faceId != other.faceId) return false
        if (photoUri != other.photoUri) return false
        if (boundingBoxLeft != other.boundingBoxLeft) return false
        if (boundingBoxTop != other.boundingBoxTop) return false
        if (boundingBoxRight != other.boundingBoxRight) return false
        if (boundingBoxBottom != other.boundingBoxBottom) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        if (confidence != other.confidence) return false
        if (clusterId != other.clusterId) return false
        if (matchScore != other.matchScore) return false

        return true
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + photoUri.hashCode()
        result = 31 * result + boundingBoxLeft.hashCode()
        result = 31 * result + boundingBoxTop.hashCode()
        result = 31 * result + boundingBoxRight.hashCode()
        result = 31 * result + boundingBoxBottom.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (clusterId?.hashCode() ?: 0)
        result = 31 * result + (matchScore?.hashCode() ?: 0)
        return result
    }
}

/**
 * Represents a person (named face group)
 */
@Entity(
    tableName = "persons",
    indices = [
        Index(value = ["name"]),  // For search by name
        Index(value = ["photoCount"]),  // For sorting by photo count
        Index(value = ["isHidden", "photoCount"]),  // For listing visible persons sorted
        Index(value = ["createdAt"]),  // For sorting by creation date
        Index(value = ["deletedAt"]),  // For soft delete queries
        Index(value = ["birthday"])  // For birthday queries
    ]
)
data class PersonEntity(
    @PrimaryKey
    val personId: String,
    val name: String?,
    val representativeFaceId: String,
    val photoCount: Int,
    val isHidden: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    // Soft delete timestamp - null means not deleted
    val deletedAt: Long? = null,
    // Birthday in format "MM-DD" for annual notifications (year-agnostic)
    // Example: "01-15" for January 15th
    val birthday: String? = null,
    // Auto-generated display number for unnamed persons (Person 1, Person 2, etc.)
    val displayNumber: Int? = null
) {
    /**
     * Get display name for this person.
     * Returns the user-set name if available, otherwise "Person X".
     */
    fun getDisplayName(): String {
        return name ?: "Person ${displayNumber ?: "?"}"
    }
}

/**
 * Represents a cluster of similar faces (may or may not be assigned to a person)
 */
@Entity(
    tableName = "face_clusters",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["personId"]),
        Index(value = ["faceCount"]),  // For sorting clusters by size
        Index(value = ["createdAt"]),  // For sorting by creation date
        Index(value = ["deletedAt"])   // For soft delete queries
    ]
)
data class FaceClusterEntity(
    @PrimaryKey
    val clusterId: String,
    val personId: String?,
    val centroidEmbedding: ByteArray?,
    val faceCount: Int,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    // Soft delete timestamp - null means not deleted
    val deletedAt: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FaceClusterEntity

        if (clusterId != other.clusterId) return false
        if (personId != other.personId) return false
        if (centroidEmbedding != null) {
            if (other.centroidEmbedding == null) return false
            if (!centroidEmbedding.contentEquals(other.centroidEmbedding)) return false
        } else if (other.centroidEmbedding != null) return false
        if (faceCount != other.faceCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = clusterId.hashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        result = 31 * result + (centroidEmbedding?.contentHashCode() ?: 0)
        result = 31 * result + faceCount
        return result
    }
}

/**
 * Tracks the progress of gallery scanning
 */
@Entity(tableName = "scan_progress")
data class ScanProgressEntity(
    @PrimaryKey
    val id: Int = 1,  // Single row table
    val totalPhotos: Int,
    val scannedPhotos: Int,
    val facesDetected: Int = 0,
    val clustersCreated: Int = 0,
    val lastPhotoUri: String?,
    val isComplete: Boolean,
    val startedAt: Long,
    val completedAt: Long? = null,
    val errorMessage: String? = null
)

// Data class for joining person with their representative face
data class PersonWithFace(
    val personId: String,
    val name: String?,
    val photoCount: Int,
    val isHidden: Boolean,
    val faceId: String,
    val photoUri: String,
    val boundingBoxLeft: Float,
    val boundingBoxTop: Float,
    val boundingBoxRight: Float,
    val boundingBoxBottom: Float,
    val birthday: String? = null,
    val displayNumber: Int? = null
) {
    /**
     * Get display name for this person.
     * Returns the user-set name if available, otherwise "Person X".
     */
    fun getDisplayName(): String {
        return name ?: "Person ${displayNumber ?: "?"}"
    }
}

// Scan status enum
object ScanStatus {
    const val PENDING = "PENDING"
    const val SCANNED = "SCANNED"
    const val FAILED = "FAILED"
    const val NO_FACES = "NO_FACES"
}

/**
 * Assignment confidence levels for clustering decisions.
 * Used to implement multi-stage clustering with staging buffer.
 */
object AssignmentConfidence {
    const val HIGH = "HIGH"           // >= 0.70 similarity - auto-assign, high confidence
    const val MEDIUM = "MEDIUM"       // >= 0.60 similarity - auto-assign, medium confidence
    const val STAGED = "STAGED"       // 0.45-0.60 similarity - held for verification
    const val NEW = "NEW"             // < 0.45 similarity or new cluster
    const val COMMITTED = "COMMITTED" // Verified and committed from staging
}

/**
 * Quality tier for face clustering decisions.
 *
 * Determines what a face can do in the clustering pipeline:
 * - ANCHOR: Can form new clusters and be a representative
 * - CLUSTERING: Can join clusters and affect centroid
 * - DISPLAY_ONLY: Visible in UI but excluded from clustering decisions
 * - REJECTED: Not stored (too low quality)
 */
object QualityTier {
    const val ANCHOR = "ANCHOR"             // High quality: can form clusters, be representative
    const val CLUSTERING = "CLUSTERING"     // Medium quality: can join clusters, affect centroid
    const val DISPLAY_ONLY = "DISPLAY_ONLY" // Low quality: visible in UI, no clustering effect
    const val REJECTED = "REJECTED"         // Very low quality: not stored

    /** Check if this tier allows forming new clusters */
    fun canFormCluster(tier: String): Boolean = tier == ANCHOR

    /** Check if this tier allows joining existing clusters */
    fun canJoinCluster(tier: String): Boolean = tier == ANCHOR || tier == CLUSTERING

    /** Check if this tier allows updating cluster centroid */
    fun canUpdateCentroid(tier: String): Boolean = tier == ANCHOR || tier == CLUSTERING

    /** Check if this face should be stored */
    fun shouldStore(tier: String): Boolean = tier != REJECTED

    /** Check if this face is visible in UI */
    fun isUIVisible(tier: String): Boolean = tier != REJECTED
}

/**
 * Embedding source types for tracking which model generated the embedding.
 *
 * Different sources have different quality characteristics:
 * - FACENET_512: Best quality, 512-dimensional embeddings
 * - MOBILEFACENET_192: Good quality, 192-dimensional embeddings
 * - HASH_FALLBACK: Low quality, should use stricter thresholds
 * - UNKNOWN: Legacy faces without source tracking
 */
object EmbeddingSource {
    const val FACENET_512 = "FACENET_512"           // High quality: 512-dim FaceNet
    const val MOBILEFACENET_192 = "MOBILEFACENET_192" // Medium quality: 192-dim MobileFaceNet
    const val HASH_FALLBACK = "HASH_FALLBACK"       // Low quality: perceptual hash fallback
    const val UNKNOWN = "UNKNOWN"                   // Legacy: source not tracked

    /** Check if this source is from a neural network model */
    fun isModelBased(source: String): Boolean =
        source == FACENET_512 || source == MOBILEFACENET_192

    /** Get recommended similarity threshold adjustment for this source */
    fun getThresholdAdjustment(source: String): Float = when (source) {
        FACENET_512 -> 0f            // Baseline
        MOBILEFACENET_192 -> -0.05f  // Slightly stricter
        HASH_FALLBACK -> 0.20f       // Much stricter (higher threshold needed)
        else -> 0f                   // Unknown, use baseline
    }
}

/**
 * Staging buffer entity for faces awaiting verification.
 * Faces with uncertain similarity scores are held here until batch verification.
 *
 * This enables the multi-stage clustering approach:
 * 1. Faces with high confidence are committed immediately
 * 2. Uncertain faces are staged here
 * 3. After a batch, staged faces are verified against all cluster representatives
 * 4. If clear winner found, commit; otherwise create new cluster
 */
@Entity(
    tableName = "staged_faces",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["candidateClusterId"]),
        Index(value = ["status"])
    ]
)
data class StagedFaceEntity(
    @PrimaryKey
    val faceId: String,
    val embedding: ByteArray,
    val qualityScore: Float,
    val photoUri: String,
    val createdAt: Long,
    // Best candidate cluster match so far
    val candidateClusterId: String?,
    val candidateSimilarity: Float,
    // Second-best match if close (for conflict detection)
    val conflictingClusterId: String? = null,
    val conflictingSimilarity: Float = 0f,
    // Status: PENDING, VERIFIED, COMMITTED, REJECTED
    val status: String = StagingStatus.PENDING,
    // Bounding box (needed to create DetectedFaceEntity after commit)
    val boundingBoxLeft: Float,
    val boundingBoxTop: Float,
    val boundingBoxRight: Float,
    val boundingBoxBottom: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    // Quality breakdown
    val sizeScore: Float = 0f,
    val poseScore: Float = 0f,
    val sharpnessScore: Float = 0f,
    val brightnessScore: Float = 0f,
    val eyeVisibilityScore: Float = 0f,
    // Pose info
    val eulerY: Float? = null,
    val eulerZ: Float? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StagedFaceEntity
        return faceId == other.faceId
    }

    override fun hashCode(): Int = faceId.hashCode()
}

/**
 * Staging status for faces in the staging buffer.
 */
object StagingStatus {
    const val PENDING = "PENDING"     // Awaiting batch verification
    const val VERIFIED = "VERIFIED"   // Passed verification, ready to commit
    const val COMMITTED = "COMMITTED" // Successfully committed to main table
    const val REJECTED = "REJECTED"   // Failed quality check or conflict detection
}
