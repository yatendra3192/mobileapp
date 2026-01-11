package com.aiezzy.slideshowmaker.face.clustering

import android.util.Log
import com.aiezzy.slideshowmaker.data.face.converters.FaceConverters
import com.aiezzy.slideshowmaker.data.face.dao.*
import com.aiezzy.slideshowmaker.data.face.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Batch Clustering Service for processing ALL faces at once after detection is complete.
 */
@Singleton
class BatchClusteringService @Inject constructor(
    private val detectedFaceDao: DetectedFaceDao,
    private val faceClusterDao: FaceClusterDao,
    private val personDao: PersonDao,
    private val clusteringService: FaceClusteringService
) {
    companion object {
        private const val TAG = "BatchClustering"
        private const val CLUSTERING_THRESHOLD = 0.58f
        private const val MIN_CLUSTER_SIZE = 1
        private const val MERGE_THRESHOLD = 0.72f
    }

    private data class ClusterData(
        val faceIds: MutableList<String>,
        val embeddings: MutableList<FloatArray>
    ) {
        fun getCentroid(): FloatArray {
            if (embeddings.isEmpty()) return FloatArray(512)
            val dim = embeddings[0].size
            val centroid = FloatArray(dim)
            for (emb in embeddings) {
                for (i in 0 until dim) {
                    centroid[i] = centroid[i] + emb[i]
                }
            }
            for (i in 0 until dim) {
                centroid[i] = centroid[i] / embeddings.size
            }
            return centroid
        }
    }

    data class BatchClusteringResult(
        val totalFaces: Int,
        val clusteredFaces: Int,
        val clustersCreated: Int,
        val personsCreated: Int,
        val durationMs: Long
    )

    suspend fun runBatchClustering(): BatchClusteringResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Starting batch clustering...")

        val unclusteredFaces = detectedFaceDao.getUnclusteredFacesWithEmbeddings()
        if (unclusteredFaces.isEmpty()) {
            Log.i(TAG, "No unclustered faces found")
            return@withContext BatchClusteringResult(0, 0, 0, 0, 0)
        }

        Log.i(TAG, "Loaded ${unclusteredFaces.size} unclustered faces")

        val faceEmbeddings = mutableListOf<Pair<String, FloatArray>>()
        for (face in unclusteredFaces) {
            val embedding = face.embedding?.let { FaceConverters.byteArrayToFloatArray(it) }
            if (embedding != null) {
                faceEmbeddings.add(face.faceId to embedding)
            }
        }

        if (faceEmbeddings.isEmpty()) {
            return@withContext BatchClusteringResult(unclusteredFaces.size, 0, 0, 0, 0)
        }

        val clusters = runClusteringAlgorithm(faceEmbeddings, unclusteredFaces)
        Log.i(TAG, "Created ${clusters.size} clusters")

        var clustersCreated = 0
        var personsCreated = 0
        var clusteredFaces = 0

        for ((clusterFaces, centroid) in clusters) {
            if (clusterFaces.size < MIN_CLUSTER_SIZE) continue

            val clusterId = UUID.randomUUID().toString()
            val personId = UUID.randomUUID().toString()
            val medoidFaceId = findMedoid(clusterFaces, faceEmbeddings, unclusteredFaces)

            val displayNumber = personDao.getNextDisplayNumber()
            val person = PersonEntity(
                personId = personId,
                name = null,
                representativeFaceId = medoidFaceId,
                photoCount = clusterFaces.size,
                isHidden = false,
                createdAt = System.currentTimeMillis(),
                displayNumber = displayNumber
            )
            personDao.insert(person)
            personsCreated++

            val cluster = FaceClusterEntity(
                clusterId = clusterId,
                personId = personId,
                centroidEmbedding = FaceConverters.floatArrayToByteArray(centroid),
                faceCount = clusterFaces.size,
                createdAt = System.currentTimeMillis()
            )
            faceClusterDao.insert(cluster)
            clustersCreated++

            for (faceId in clusterFaces) {
                val faceEmb = faceEmbeddings.find { it.first == faceId }?.second
                val matchScore = if (faceEmb != null) {
                    clusteringService.calculateCosineSimilarity(faceEmb, centroid)
                } else 0f
                detectedFaceDao.assignToCluster(faceId, clusterId, matchScore)
                clusteredFaces++
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "Batch clustering complete: $clusteredFaces faces in $clustersCreated clusters in ${duration}ms")

        BatchClusteringResult(unclusteredFaces.size, clusteredFaces, clustersCreated, personsCreated, duration)
    }

    private fun runClusteringAlgorithm(
        faceEmbeddings: List<Pair<String, FloatArray>>,
        allFaces: List<DetectedFaceEntity>
    ): List<Pair<List<String>, FloatArray>> {
        if (faceEmbeddings.isEmpty()) return emptyList()

        val faceToPhoto = allFaces.associate { it.faceId to it.photoUri }
        val clusters = mutableListOf<ClusterData>()

        val sortedFaces = faceEmbeddings.sortedByDescending { (faceId, _) ->
            allFaces.find { it.faceId == faceId }?.qualityScore ?: 0f
        }

        for ((faceId, embedding) in sortedFaces) {
            val facePhotoUri = faceToPhoto[faceId]
            var bestClusterIdx = -1
            var bestSimilarity = 0f

            for ((idx, cluster) in clusters.withIndex()) {
                val hasConflict = cluster.faceIds.any { faceToPhoto[it] == facePhotoUri }
                if (hasConflict) continue

                val similarity = clusteringService.calculateCosineSimilarity(embedding, cluster.getCentroid())
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestClusterIdx = idx
                }
            }

            if (bestSimilarity >= CLUSTERING_THRESHOLD && bestClusterIdx >= 0) {
                clusters[bestClusterIdx].faceIds.add(faceId)
                clusters[bestClusterIdx].embeddings.add(embedding)
            } else {
                clusters.add(ClusterData(mutableListOf(faceId), mutableListOf(embedding)))
            }
        }

        mergeSimilarClusters(clusters)

        return clusters.map { it.faceIds.toList() to it.getCentroid() }
    }

    private fun mergeSimilarClusters(clusters: MutableList<ClusterData>) {
        if (clusters.size <= 1) return
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val similarity = clusteringService.calculateCosineSimilarity(
                        clusters[i].getCentroid(), clusters[j].getCentroid()
                    )
                    if (similarity >= MERGE_THRESHOLD) {
                        clusters[i].faceIds.addAll(clusters[j].faceIds)
                        clusters[i].embeddings.addAll(clusters[j].embeddings)
                        clusters.removeAt(j)
                        merged = true
                        break@outer
                    }
                }
            }
        }
    }

    private fun findMedoid(
        faceIds: List<String>,
        faceEmbeddings: List<Pair<String, FloatArray>>,
        allFaces: List<DetectedFaceEntity>
    ): String {
        if (faceIds.isEmpty()) return faceIds.firstOrNull() ?: ""

        // For single face clusters, still prefer faces with visible eyes
        if (faceIds.size == 1) {
            val face = allFaces.find { it.faceId == faceIds[0] }
            // If no eyes visible, log warning but return it anyway (it's the only one)
            if (face != null && face.eyeVisibilityScore <= 0) {
                Log.d(TAG, "Single-face cluster has no visible eyes: ${faceIds[0]}")
            }
            return faceIds[0]
        }

        val embeddings = faceIds.mapNotNull { id ->
            faceEmbeddings.find { it.first == id }?.let { id to it.second }
        }
        if (embeddings.isEmpty()) return faceIds[0]

        var bestFaceId = faceIds[0]
        var bestScore = -1f

        for ((candidateId, candidateEmb) in embeddings) {
            val face = allFaces.find { it.faceId == candidateId }

            // Skip faces without visible eyes for representative selection
            if (face != null && face.eyeVisibilityScore <= 0) {
                continue
            }

            // Calculate average similarity to other faces in cluster
            var similarityTotal = 0f
            var count = 0
            for ((otherId, otherEmb) in embeddings) {
                if (otherId != candidateId) {
                    similarityTotal += clusteringService.calculateCosineSimilarity(candidateEmb, otherEmb)
                    count++
                }
            }
            val avgSimilarity = if (count > 0) similarityTotal / count else 0f

            // Combined score: similarity (0-1) + quality bonus (0-0.5)
            // Quality normalized: qualityScore/100 * 0.5 gives 0-0.5 range
            val qualityBonus = (face?.qualityScore ?: 0f) / 100f * 0.5f
            val combinedScore = avgSimilarity + qualityBonus

            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestFaceId = candidateId
            }
        }

        // If all faces were skipped (no eyes visible), fall back to highest quality
        if (bestScore < 0) {
            val highestQuality = faceIds.maxByOrNull { id ->
                allFaces.find { it.faceId == id }?.qualityScore ?: 0f
            }
            return highestQuality ?: faceIds[0]
        }

        return bestFaceId
    }

    suspend fun runIncrementalClustering(): BatchClusteringResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val unclusteredFaces = detectedFaceDao.getUnclusteredFacesWithEmbeddings()
        if (unclusteredFaces.isEmpty()) return@withContext BatchClusteringResult(0, 0, 0, 0, 0)

        val existingClusters = faceClusterDao.getAll()
        if (existingClusters.isEmpty()) return@withContext runBatchClustering()

        var clusteredFaces = 0
        var newClustersCreated = 0
        var personsCreated = 0

        for (face in unclusteredFaces) {
            val embedding = face.embedding?.let { FaceConverters.byteArrayToFloatArray(it) } ?: continue

            var bestCluster: FaceClusterEntity? = null
            var bestSimilarity = 0f

            for (cluster in existingClusters) {
                val centroid = cluster.centroidEmbedding?.let { FaceConverters.byteArrayToFloatArray(it) } ?: continue
                val existing = detectedFaceDao.getFacesByClusterId(cluster.clusterId)
                if (existing.any { it.photoUri == face.photoUri }) continue

                val similarity = clusteringService.calculateCosineSimilarity(embedding, centroid)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestCluster = cluster
                }
            }

            if (bestSimilarity >= CLUSTERING_THRESHOLD && bestCluster != null) {
                detectedFaceDao.assignToCluster(face.faceId, bestCluster.clusterId, bestSimilarity)
                faceClusterDao.incrementFaceCount(bestCluster.clusterId)
                updateClusterCentroid(bestCluster.clusterId)
                bestCluster.personId?.let { personDao.incrementPhotoCount(it) }
                clusteredFaces++
            } else {
                val clusterId = UUID.randomUUID().toString()
                val personId = UUID.randomUUID().toString()
                val displayNumber = personDao.getNextDisplayNumber()
                personDao.insert(PersonEntity(personId, null, face.faceId, 1, false, System.currentTimeMillis(), displayNumber = displayNumber))
                faceClusterDao.insert(FaceClusterEntity(clusterId, personId, face.embedding, 1, System.currentTimeMillis()))
                detectedFaceDao.assignToCluster(face.faceId, clusterId, 1.0f)
                newClustersCreated++
                personsCreated++
                clusteredFaces++
            }
        }

        val mergeCount = runClusterMerging()
        val duration = System.currentTimeMillis() - startTime
        BatchClusteringResult(unclusteredFaces.size, clusteredFaces, newClustersCreated, personsCreated, duration)
    }

    private suspend fun updateClusterCentroid(clusterId: String) {
        val faces = detectedFaceDao.getFacesWithEmbeddingsByClusterId(clusterId)
        if (faces.isEmpty()) return
        val embeddings = faces.mapNotNull { it.embedding?.let { e -> FaceConverters.byteArrayToFloatArray(e) } }
        if (embeddings.isEmpty()) return

        val dim = embeddings[0].size
        val centroid = FloatArray(dim)
        for (emb in embeddings) {
            for (i in 0 until dim) centroid[i] = centroid[i] + emb[i]
        }
        for (i in 0 until dim) centroid[i] = centroid[i] / embeddings.size
        faceClusterDao.updateCentroid(clusterId, FaceConverters.floatArrayToByteArray(centroid))
    }

    private suspend fun runClusterMerging(): Int {
        var mergeCount = 0
        val clusters = faceClusterDao.getAll().toMutableList()
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in clusters.indices) {
                val c1 = clusters[i].centroidEmbedding?.let { FaceConverters.byteArrayToFloatArray(it) } ?: continue
                for (j in i + 1 until clusters.size) {
                    val c2 = clusters[j].centroidEmbedding?.let { FaceConverters.byteArrayToFloatArray(it) } ?: continue
                    if (clusteringService.calculateCosineSimilarity(c1, c2) >= MERGE_THRESHOLD) {
                        detectedFaceDao.moveAllFacesToCluster(clusters[j].clusterId, clusters[i].clusterId)
                        val newCount = clusters[i].faceCount + clusters[j].faceCount
                        faceClusterDao.updateFaceCount(clusters[i].clusterId, newCount)
                        updateClusterCentroid(clusters[i].clusterId)
                        clusters[i].personId?.let { personDao.updatePhotoCount(it, newCount) }
                        clusters[j].personId?.let { personDao.delete(it) }
                        faceClusterDao.delete(clusters[j].clusterId)
                        clusters.removeAt(j)
                        merged = true
                        mergeCount++
                        break@outer
                    }
                }
            }
        }
        return mergeCount
    }

    suspend fun updateAllMedoids() = withContext(Dispatchers.Default) {
        for (cluster in faceClusterDao.getAll()) {
            val faces = detectedFaceDao.getFacesWithEmbeddingsByClusterId(cluster.clusterId)
            if (faces.isEmpty()) continue
            val faceEmbs = faces.mapNotNull { f -> f.embedding?.let { f.faceId to FaceConverters.byteArrayToFloatArray(it) } }
            val medoidId = findMedoid(faces.map { it.faceId }, faceEmbs, faces)
            cluster.personId?.let { personDao.updateRepresentativeFace(it, medoidId) }
        }
    }
}


