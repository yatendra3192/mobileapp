package com.aiezzy.slideshowmaker.face

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aiezzy.slideshowmaker.data.face.FaceDatabase
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.entities.*
import com.aiezzy.slideshowmaker.face.clustering.FaceClusteringService
import com.aiezzy.slideshowmaker.face.detection.FaceDetectionService
import com.aiezzy.slideshowmaker.face.embedding.FaceEmbeddingGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Repository for face-related operations.
 * Coordinates detection, embedding, clustering, and database operations.
 */
class FaceRepository(context: Context) {

    companion object {
        private const val TAG = "FaceRepository"

        @Volatile
        private var INSTANCE: FaceRepository? = null

        fun getInstance(context: Context): FaceRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FaceRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val database = FaceDatabase.getInstance(context)
    private val scannedPhotoDao = database.scannedPhotoDao()
    private val detectedFaceDao = database.detectedFaceDao()
    private val personDao = database.personDao()
    private val faceClusterDao = database.faceClusterDao()
    private val scanProgressDao = database.scanProgressDao()

    private val detectionService = FaceDetectionService(context)
    private val embeddingGenerator = FaceEmbeddingGenerator(context)
    private val clusteringService = FaceClusteringService(embeddingGenerator)

    // ============ Scan Progress Operations ============

    /**
     * Get scan progress as flow
     */
    fun getScanProgressFlow(): Flow<ScanProgressEntity?> = scanProgressDao.getProgressFlow()

    /**
     * Get current scan progress
     */
    suspend fun getScanProgress(): ScanProgressEntity? = scanProgressDao.getProgress()

    /**
     * Start a new scan
     */
    suspend fun startNewScan(totalPhotos: Int) {
        scanProgressDao.startNewScan(totalPhotos)
    }

    /**
     * Mark scan as complete
     */
    suspend fun markScanComplete() {
        scanProgressDao.markComplete()
    }

    // ============ Photo Scanning Operations ============

    /**
     * Add photos to be scanned
     */
    suspend fun addPhotosToScan(photos: List<ScannedPhotoEntity>) {
        scannedPhotoDao.insertAll(photos)
    }

    /**
     * Check if a photo has been scanned
     */
    suspend fun isPhotoScanned(uri: String): Boolean {
        return scannedPhotoDao.exists(uri)
    }

    /**
     * Get pending photos to scan
     */
    suspend fun getPendingPhotos(limit: Int = 100): List<ScannedPhotoEntity> {
        return scannedPhotoDao.getPendingPhotos(limit)
    }

    /**
     * Process a single photo: detect faces, generate embeddings, cluster
     */
    suspend fun processPhoto(photoUri: String): ProcessingResult = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(photoUri)

            // Detect faces
            val detectionResult = detectionService.detectFaces(uri)
            if (detectionResult.isFailure) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
                return@withContext ProcessingResult.Error("Detection failed: ${detectionResult.exceptionOrNull()?.message}")
            }

            val detectedFaces = detectionResult.getOrNull() ?: emptyList()
            if (detectedFaces.isEmpty()) {
                scannedPhotoDao.updateStatus(photoUri, ScanStatus.NO_FACES, System.currentTimeMillis())
                return@withContext ProcessingResult.NoFaces
            }

            // Get existing clusters for clustering
            val existingClusters = faceClusterDao.getAll()

            var newClustersCreated = 0
            val processedFaces = mutableListOf<DetectedFaceEntity>()

            for (detectedFace in detectedFaces) {
                // Generate embedding
                val faceBitmap = detectedFace.faceBitmap
                val embedding = if (faceBitmap != null) {
                    embeddingGenerator.generateEmbedding(faceBitmap)
                } else {
                    null
                }

                // Cluster the face
                val clusterResult = if (embedding != null) {
                    clusteringService.clusterFace(embedding, existingClusters)
                } else {
                    // Create new cluster for faces without embeddings
                    FaceClusteringService.ClusterResult(
                        clusterId = UUID.randomUUID().toString(),
                        isNewCluster = true,
                        similarity = 0f,
                        updatedCentroid = null
                    )
                }

                // Create face entity
                val faceEntity = DetectedFaceEntity(
                    faceId = detectedFace.faceId,
                    photoUri = photoUri,
                    boundingBoxLeft = detectedFace.boundingBox.left,
                    boundingBoxTop = detectedFace.boundingBox.top,
                    boundingBoxRight = detectedFace.boundingBox.right,
                    boundingBoxBottom = detectedFace.boundingBox.bottom,
                    embedding = embedding?.let { FaceConverters.floatArrayToByteArray(it) },
                    confidence = detectedFace.confidence,
                    clusterId = clusterResult.clusterId,
                    imageWidth = detectedFace.imageWidth,
                    imageHeight = detectedFace.imageHeight
                )

                // Save face to database
                detectedFaceDao.insert(faceEntity)
                processedFaces.add(faceEntity)

                // Update or create cluster
                if (clusterResult.isNewCluster) {
                    val newCluster = FaceClusterEntity(
                        clusterId = clusterResult.clusterId,
                        personId = null,
                        centroidEmbedding = clusterResult.updatedCentroid?.let { FaceConverters.floatArrayToByteArray(it) },
                        faceCount = 1,
                        createdAt = System.currentTimeMillis()
                    )
                    faceClusterDao.insert(newCluster)
                    newClustersCreated++

                    // Auto-create person for new cluster
                    createPersonForCluster(newCluster, faceEntity)
                } else {
                    // Update existing cluster
                    val existingCluster = faceClusterDao.getById(clusterResult.clusterId)
                    if (existingCluster != null) {
                        val updatedCluster = existingCluster.copy(
                            centroidEmbedding = clusterResult.updatedCentroid?.let { FaceConverters.floatArrayToByteArray(it) },
                            faceCount = existingCluster.faceCount + 1,
                            updatedAt = System.currentTimeMillis()
                        )
                        faceClusterDao.update(updatedCluster)

                        // Update person photo count
                        existingCluster.personId?.let { personId ->
                            updatePersonPhotoCount(personId)
                        }
                    }
                }

                // Recycle face bitmap
                faceBitmap?.recycle()
            }

            // Update photo status
            scannedPhotoDao.updateStatus(photoUri, ScanStatus.SCANNED, System.currentTimeMillis())

            // Update scan progress
            scanProgressDao.incrementProgress(photoUri, detectedFaces.size)

            ProcessingResult.Success(detectedFaces.size, newClustersCreated)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing photo: $photoUri", e)
            scannedPhotoDao.updateStatus(photoUri, ScanStatus.FAILED, System.currentTimeMillis())
            ProcessingResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Create a person for a new cluster
     */
    private suspend fun createPersonForCluster(cluster: FaceClusterEntity, representativeFace: DetectedFaceEntity) {
        val person = PersonEntity(
            personId = UUID.randomUUID().toString(),
            name = null,
            representativeFaceId = representativeFace.faceId,
            photoCount = 1,
            isHidden = false,
            createdAt = System.currentTimeMillis()
        )
        personDao.insert(person)

        // Link cluster to person
        faceClusterDao.assignToPerson(cluster.clusterId, person.personId)
    }

    /**
     * Update person's photo count
     */
    private suspend fun updatePersonPhotoCount(personId: String) {
        val photoUris = detectedFaceDao.getPhotoUrisForPerson(personId)
        personDao.updatePhotoCount(personId, photoUris.size)
    }

    // ============ Person Operations ============

    /**
     * Get all visible persons with their representative faces
     */
    fun getPersonsWithFaceFlow(): Flow<List<PersonWithFace>> = personDao.getPersonsWithFaceFlow()

    /**
     * Get all visible persons
     */
    suspend fun getPersonsWithFace(): List<PersonWithFace> = personDao.getPersonsWithFace()

    /**
     * Get a specific person with face info
     */
    suspend fun getPersonWithFace(personId: String): PersonWithFace? = personDao.getPersonWithFace(personId)

    /**
     * Update person's name
     */
    suspend fun updatePersonName(personId: String, name: String?) {
        personDao.updateName(personId, name)
    }

    /**
     * Hide a person
     */
    suspend fun hidePersons(personId: String) {
        personDao.updateHidden(personId, true)
    }

    /**
     * Unhide a person
     */
    suspend fun showPerson(personId: String) {
        personDao.updateHidden(personId, false)
    }

    // ============ Photo Filtering Operations ============

    /**
     * Get photo URIs for a specific person
     */
    suspend fun getPhotosForPerson(personId: String): List<String> {
        return detectedFaceDao.getPhotoUrisForPerson(personId)
    }

    /**
     * Get photo URIs containing ALL specified persons
     */
    suspend fun getPhotosContainingAllPersons(personIds: List<String>): List<String> {
        if (personIds.isEmpty()) return emptyList()
        if (personIds.size == 1) return getPhotosForPerson(personIds.first())
        return detectedFaceDao.getPhotoUrisContainingAllPersons(personIds, personIds.size)
    }

    // ============ Cluster Operations ============

    /**
     * Merge two persons (and their clusters)
     */
    suspend fun mergePersons(sourcePersonId: String, targetPersonId: String) {
        // Get clusters for source person
        val sourceClusters = faceClusterDao.getByPersonId(sourcePersonId)

        // Reassign clusters to target person
        for (cluster in sourceClusters) {
            faceClusterDao.assignToPerson(cluster.clusterId, targetPersonId)
        }

        // Delete source person
        personDao.getById(sourcePersonId)?.let { personDao.delete(it) }

        // Update target person photo count
        updatePersonPhotoCount(targetPersonId)
    }

    /**
     * Find clusters that might be the same person
     */
    suspend fun findMergeCandidates(): List<Pair<String, String>> {
        val clusters = faceClusterDao.getAll()
        return clusteringService.findMergeCandidates(clusters)
    }

    // ============ Statistics ============

    /**
     * Get total count of detected faces
     */
    suspend fun getTotalFacesCount(): Int = detectedFaceDao.getTotalCount()

    /**
     * Get count of visible persons
     */
    suspend fun getVisiblePersonsCount(): Int = personDao.getVisibleCount()

    /**
     * Get count of clusters
     */
    suspend fun getClustersCount(): Int = faceClusterDao.getTotalCount()

    // ============ Cleanup ============

    /**
     * Clear all face data
     */
    suspend fun clearAllData() {
        detectedFaceDao.deleteAll()
        faceClusterDao.deleteAll()
        personDao.deleteAll()
        scannedPhotoDao.deleteAll()
        scanProgressDao.clear()
    }

    /**
     * Close resources
     */
    fun close() {
        detectionService.close()
        embeddingGenerator.close()
    }

    /**
     * Result of processing a photo
     */
    sealed class ProcessingResult {
        data class Success(val facesDetected: Int, val newClusters: Int) : ProcessingResult()
        object NoFaces : ProcessingResult()
        data class Error(val message: String) : ProcessingResult()
    }
}
