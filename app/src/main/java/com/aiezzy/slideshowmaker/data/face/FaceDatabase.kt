package com.aiezzy.slideshowmaker.data.face

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.dao.*
import com.aiezzy.slideshowmaker.data.face.entities.*

@Database(
    entities = [
        ScannedPhotoEntity::class,
        DetectedFaceEntity::class,
        PersonEntity::class,
        FaceClusterEntity::class,
        ScanProgressEntity::class,
        ClusterRepresentativeEntity::class,
        ClusterHistoryEntity::class,
        ClusteringConstraintEntity::class,
        StagedFaceEntity::class,  // Staging buffer for multi-stage clustering
        ClusterAnchorEntity::class,  // NEW: High-quality anchor faces for cluster identity
        ClusterStatisticsEntity::class  // NEW: Per-cluster adaptive threshold statistics
    ],
    version = 8,  // Bumped from 7 to 8 - anchor-based clustering system
    exportSchema = true
)
@TypeConverters(FaceConverters::class)
abstract class FaceDatabase : RoomDatabase() {

    abstract fun scannedPhotoDao(): ScannedPhotoDao
    abstract fun detectedFaceDao(): DetectedFaceDao
    abstract fun personDao(): PersonDao
    abstract fun faceClusterDao(): FaceClusterDao
    abstract fun scanProgressDao(): ScanProgressDao
    abstract fun clusterRepresentativeDao(): ClusterRepresentativeDao
    abstract fun clusterHistoryDao(): ClusterHistoryDao
    abstract fun clusteringConstraintDao(): ClusteringConstraintDao
    abstract fun stagedFaceDao(): StagedFaceDao
    abstract fun clusterAnchorDao(): ClusterAnchorDao  // NEW: Anchor-based clustering
    abstract fun clusterStatisticsDao(): ClusterStatisticsDao  // NEW: Adaptive thresholds

    companion object {
        private const val TAG = "FaceDatabase"
        private const val DATABASE_NAME = "face_database"

        @Volatile
        private var INSTANCE: FaceDatabase? = null

        /**
         * Migration from version 1 to 2.
         * Adds clustering enhancement tables:
         * - cluster_representatives: Multiple representatives per cluster
         * - cluster_history: Undo support for cluster operations
         * - clustering_constraints: Must-link/Cannot-link user constraints
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 1 to 2")

                // Create cluster_representatives table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cluster_representatives (
                        id TEXT NOT NULL PRIMARY KEY,
                        clusterId TEXT NOT NULL,
                        faceId TEXT NOT NULL,
                        embedding BLOB NOT NULL,
                        qualityScore REAL NOT NULL,
                        rank INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY (clusterId) REFERENCES face_clusters(clusterId) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_representatives_clusterId ON cluster_representatives(clusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_representatives_clusterId_rank ON cluster_representatives(clusterId, rank)")

                // Create cluster_history table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cluster_history (
                        historyId TEXT NOT NULL PRIMARY KEY,
                        operationType TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        clusterId TEXT NOT NULL,
                        sourceClusterId TEXT,
                        faceId TEXT,
                        previousName TEXT,
                        undoData TEXT,
                        canUndo INTEGER NOT NULL DEFAULT 1,
                        expiresAt INTEGER
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_history_timestamp ON cluster_history(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_history_operationType ON cluster_history(operationType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_history_clusterId ON cluster_history(clusterId)")

                // Create clustering_constraints table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS clustering_constraints (
                        constraintId TEXT NOT NULL PRIMARY KEY,
                        constraintType TEXT NOT NULL,
                        faceId1 TEXT NOT NULL,
                        faceId2 TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        createdBy TEXT NOT NULL DEFAULT 'user'
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clustering_constraints_faceId1 ON clustering_constraints(faceId1)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clustering_constraints_faceId2 ON clustering_constraints(faceId2)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clustering_constraints_constraintType ON clustering_constraints(constraintType)")

                Log.i(TAG, "Migration 1->2 complete")
            }
        }

        /**
         * Migration from version 2 to 3.
         * Adds soft delete support:
         * - deletedAt column to detected_faces, persons, face_clusters
         * - Indexes for soft delete queries
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 2 to 3")

                // Add deletedAt column to detected_faces
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_faces_deletedAt ON detected_faces(deletedAt)")

                // Add deletedAt column to persons
                db.execSQL("ALTER TABLE persons ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_persons_deletedAt ON persons(deletedAt)")

                // Add deletedAt column to face_clusters
                db.execSQL("ALTER TABLE face_clusters ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_face_clusters_deletedAt ON face_clusters(deletedAt)")

                Log.i(TAG, "Migration 2->3 complete")
            }
        }

        /**
         * Combined migration from version 1 to 3.
         * For fresh installs that need to skip intermediate versions.
         */
        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 1 to 3")
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                Log.i(TAG, "Migration 1->3 complete")
            }
        }

        /**
         * Migration from version 3 to 4.
         * Adds pose diversity support to cluster_representatives:
         * - eulerY: Head yaw angle for pose classification
         * - eulerZ: Head roll angle
         * - poseCategory: Classified pose (FRONTAL, SLIGHT_LEFT, etc.)
         * - Index for pose-based queries
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 3 to 4")

                // Add pose columns to cluster_representatives
                db.execSQL("ALTER TABLE cluster_representatives ADD COLUMN eulerY REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cluster_representatives ADD COLUMN eulerZ REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cluster_representatives ADD COLUMN poseCategory TEXT NOT NULL DEFAULT 'FRONTAL'")

                // Add index for pose-based queries
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_representatives_clusterId_poseCategory ON cluster_representatives(clusterId, poseCategory)")

                // Add performance indexes for face clustering
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_faces_clusterId_confidence ON detected_faces(clusterId, confidence)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_face_clusters_personId_faceCount ON face_clusters(personId, faceCount)")

                Log.i(TAG, "Migration 3->4 complete")
            }
        }

        /**
         * Combined migration from version 1 to 4.
         */
        val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 1 to 4")
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                Log.i(TAG, "Migration 1->4 complete")
            }
        }

        /**
         * Combined migration from version 2 to 4.
         */
        val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 2 to 4")
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                Log.i(TAG, "Migration 2->4 complete")
            }
        }

        /**
         * Migration from version 4 to 5.
         * Adds matchScore column to detected_faces for better thumbnail selection:
         * - matchScore: How well a face matches its cluster (0.0-1.0)
         * - Higher matchScore = better representative of the cluster
         * - Index for matchScore-based queries
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 4 to 5")

                // Add matchScore column to detected_faces
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN matchScore REAL DEFAULT NULL")

                // Add index for matchScore-based thumbnail queries
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_faces_clusterId_matchScore ON detected_faces(clusterId, matchScore)")

                Log.i(TAG, "Migration 4->5 complete")
            }
        }

        /**
         * Combined migration from version 1 to 5.
         */
        val MIGRATION_1_5 = object : Migration(1, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 1 to 5")
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                Log.i(TAG, "Migration 1->5 complete")
            }
        }

        /**
         * Combined migration from version 2 to 5.
         */
        val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 2 to 5")
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                Log.i(TAG, "Migration 2->5 complete")
            }
        }

        /**
         * Combined migration from version 3 to 5.
         */
        val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 3 to 5")
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                Log.i(TAG, "Migration 3->5 complete")
            }
        }

        /**
         * Migration from version 5 to 6.
         * Adds birthday and displayNumber columns to persons table:
         * - birthday: Person's birthday in "MM-DD" format for annual notifications
         * - displayNumber: Auto-generated number for unnamed persons (Person 1, Person 2, etc.)
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 5 to 6")

                // Add birthday column to persons
                db.execSQL("ALTER TABLE persons ADD COLUMN birthday TEXT DEFAULT NULL")

                // Add displayNumber column to persons
                db.execSQL("ALTER TABLE persons ADD COLUMN displayNumber INTEGER DEFAULT NULL")

                // Add index for birthday queries
                db.execSQL("CREATE INDEX IF NOT EXISTS index_persons_birthday ON persons(birthday)")

                // Auto-populate displayNumber for existing persons
                db.execSQL("""
                    UPDATE persons SET displayNumber = (
                        SELECT COUNT(*) FROM persons p2
                        WHERE p2.createdAt <= persons.createdAt
                        AND p2.deletedAt IS NULL
                    ) WHERE deletedAt IS NULL
                """)

                Log.i(TAG, "Migration 5->6 complete")
            }
        }

        /**
         * Combined migration from version 1 to 6.
         */
        val MIGRATION_1_6 = object : Migration(1, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 1 to 6")
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                Log.i(TAG, "Migration 1->6 complete")
            }
        }

        /**
         * Combined migration from version 2 to 6.
         */
        val MIGRATION_2_6 = object : Migration(2, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 2 to 6")
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                Log.i(TAG, "Migration 2->6 complete")
            }
        }

        /**
         * Combined migration from version 3 to 6.
         */
        val MIGRATION_3_6 = object : Migration(3, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 3 to 6")
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                Log.i(TAG, "Migration 3->6 complete")
            }
        }

        /**
         * Combined migration from version 4 to 6.
         */
        val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 4 to 6")
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                Log.i(TAG, "Migration 4->6 complete")
            }
        }

        /**
         * Migration from version 6 to 7.
         *
         * MAJOR CHANGES - Enterprise-grade face grouping improvements:
         * 1. Added quality breakdown fields to detected_faces (sizeScore, poseScore, etc.)
         * 2. Added clustering metadata (assignmentConfidence, verifiedAt, embeddingQuality)
         * 3. Added staged_faces table for multi-stage clustering with staging buffer
         * 4. Added new indices for quality-based and confidence-based queries
         *
         * NOTE: This is a DESTRUCTIVE migration that clears all face data.
         * Users will need to re-scan their gallery after this update.
         * This approach was chosen because:
         * - The clustering algorithm has fundamentally changed
         * - Old embeddings/clusters would be incompatible with new thresholds
         * - A fresh scan with improved quality filtering produces better results
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 6 to 7 (DESTRUCTIVE - clearing face data)")

                // Clear all face-related data (preserving structure)
                db.execSQL("DELETE FROM detected_faces")
                db.execSQL("DELETE FROM face_clusters")
                db.execSQL("DELETE FROM persons")
                db.execSQL("DELETE FROM scanned_photos")
                db.execSQL("DELETE FROM scan_progress")
                db.execSQL("DELETE FROM cluster_representatives")
                db.execSQL("DELETE FROM cluster_history")
                db.execSQL("DELETE FROM clustering_constraints")

                // Add new columns to detected_faces
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN qualityScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN sizeScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN poseScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN sharpnessScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN brightnessScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN eyeVisibilityScore REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN assignmentConfidence TEXT NOT NULL DEFAULT 'NEW'")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN verifiedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN embeddingQuality REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN eulerY REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE detected_faces ADD COLUMN eulerZ REAL DEFAULT NULL")

                // Add new indices
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_faces_assignmentConfidence ON detected_faces(assignmentConfidence)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_faces_clusterId_qualityScore ON detected_faces(clusterId, qualityScore)")

                // Create staged_faces table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS staged_faces (
                        faceId TEXT NOT NULL PRIMARY KEY,
                        embedding BLOB NOT NULL,
                        qualityScore REAL NOT NULL,
                        photoUri TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        candidateClusterId TEXT,
                        candidateSimilarity REAL NOT NULL,
                        conflictingClusterId TEXT,
                        conflictingSimilarity REAL NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        boundingBoxLeft REAL NOT NULL,
                        boundingBoxTop REAL NOT NULL,
                        boundingBoxRight REAL NOT NULL,
                        boundingBoxBottom REAL NOT NULL,
                        imageWidth INTEGER NOT NULL,
                        imageHeight INTEGER NOT NULL,
                        sizeScore REAL NOT NULL DEFAULT 0,
                        poseScore REAL NOT NULL DEFAULT 0,
                        sharpnessScore REAL NOT NULL DEFAULT 0,
                        brightnessScore REAL NOT NULL DEFAULT 0,
                        eyeVisibilityScore REAL NOT NULL DEFAULT 0,
                        eulerY REAL,
                        eulerZ REAL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_staged_faces_createdAt ON staged_faces(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_staged_faces_candidateClusterId ON staged_faces(candidateClusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_staged_faces_status ON staged_faces(status)")

                Log.i(TAG, "Migration 6->7 complete - face data cleared, ready for re-scan")
            }
        }

        /**
         * Migration from version 7 to 8.
         *
         * Anchor-based clustering system for production-grade face grouping:
         * 1. cluster_anchors table - High-quality faces that define cluster identity
         * 2. cluster_statistics table - Per-cluster adaptive thresholds
         *
         * This migration adds new tables without destroying existing data.
         * The new anchor-based system will be populated during the next scan.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 7 to 8 - anchor-based clustering")

                // Create cluster_anchors table
                // High-quality faces that define cluster identity
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cluster_anchors (
                        anchorId TEXT NOT NULL PRIMARY KEY,
                        clusterId TEXT NOT NULL,
                        faceId TEXT NOT NULL,
                        embedding BLOB NOT NULL,
                        qualityScore REAL NOT NULL,
                        sharpnessScore REAL NOT NULL,
                        eyeVisibilityScore REAL NOT NULL,
                        poseCategory TEXT NOT NULL,
                        eulerY REAL NOT NULL,
                        eulerZ REAL NOT NULL DEFAULT 0,
                        intraClusterMeanSimilarity REAL NOT NULL DEFAULT 0,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        lastMatchedAt INTEGER NOT NULL DEFAULT 0,
                        matchCount INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (clusterId) REFERENCES face_clusters(clusterId) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_anchors_clusterId ON cluster_anchors(clusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_anchors_clusterId_poseCategory ON cluster_anchors(clusterId, poseCategory)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_anchors_qualityScore ON cluster_anchors(qualityScore)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_anchors_isActive ON cluster_anchors(isActive)")

                // Create cluster_statistics table
                // Per-cluster adaptive thresholds based on internal consistency
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cluster_statistics (
                        clusterId TEXT NOT NULL PRIMARY KEY,
                        meanSimilarity REAL NOT NULL,
                        similarityVariance REAL NOT NULL,
                        similarityStdDev REAL NOT NULL,
                        minSimilarity REAL NOT NULL,
                        maxSimilarity REAL NOT NULL,
                        acceptanceThreshold REAL NOT NULL,
                        anchorCount INTEGER NOT NULL,
                        totalFaceCount INTEGER NOT NULL,
                        poseDistribution TEXT NOT NULL DEFAULT '{}',
                        lastUpdatedAt INTEGER NOT NULL,
                        sampleCount INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (clusterId) REFERENCES face_clusters(clusterId) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cluster_statistics_clusterId ON cluster_statistics(clusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cluster_statistics_acceptanceThreshold ON cluster_statistics(acceptanceThreshold)")

                Log.i(TAG, "Migration 7->8 complete - anchor-based clustering tables created")
            }
        }

        /**
         * Combined migrations to version 8.
         */
        val MIGRATION_1_8 = object : Migration(1, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 1 to 8")
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
                Log.i(TAG, "Migration 1->8 complete")
            }
        }

        val MIGRATION_2_8 = object : Migration(2, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 2 to 8")
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
                Log.i(TAG, "Migration 2->8 complete")
            }
        }

        val MIGRATION_3_8 = object : Migration(3, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 3 to 8")
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
                Log.i(TAG, "Migration 3->8 complete")
            }
        }

        val MIGRATION_4_8 = object : Migration(4, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 4 to 8")
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
                Log.i(TAG, "Migration 4->8 complete")
            }
        }

        val MIGRATION_5_8 = object : Migration(5, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 5 to 8")
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
                Log.i(TAG, "Migration 5->8 complete")
            }
        }

        val MIGRATION_6_8 = object : Migration(6, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 6 to 8")
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
                Log.i(TAG, "Migration 6->8 complete")
            }
        }

        fun getInstance(context: Context): FaceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): FaceDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                FaceDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(
                    // Sequential migrations
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    // Combined migrations to v3
                    MIGRATION_1_3,
                    // Combined migrations to v4
                    MIGRATION_1_4, MIGRATION_2_4,
                    // Combined migrations to v5
                    MIGRATION_1_5, MIGRATION_2_5, MIGRATION_3_5,
                    // Combined migrations to v6
                    MIGRATION_1_6, MIGRATION_2_6, MIGRATION_3_6, MIGRATION_4_6,
                    // Combined migrations to v8 (anchor-based clustering)
                    MIGRATION_1_8, MIGRATION_2_8, MIGRATION_3_8, MIGRATION_4_8, MIGRATION_5_8, MIGRATION_6_8
                )
                // Fallback to destructive migration for development and major version changes
                // Version 8 adds anchor-based clustering without destroying data
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Build database for production without destructive migration fallback.
         * Use this in release builds to ensure user data is never lost.
         */
        fun buildProductionDatabase(context: Context): FaceDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                FaceDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(
                    // Sequential migrations
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    // Combined migrations to v3
                    MIGRATION_1_3,
                    // Combined migrations to v4
                    MIGRATION_1_4, MIGRATION_2_4,
                    // Combined migrations to v5
                    MIGRATION_1_5, MIGRATION_2_5, MIGRATION_3_5,
                    // Combined migrations to v6
                    MIGRATION_1_6, MIGRATION_2_6, MIGRATION_3_6, MIGRATION_4_6,
                    // Combined migrations to v8 (anchor-based clustering)
                    MIGRATION_1_8, MIGRATION_2_8, MIGRATION_3_8, MIGRATION_4_8, MIGRATION_5_8, MIGRATION_6_8
                )
                .build()
        }

        /**
         * Clear the singleton instance (useful for testing)
         */
        fun clearInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
