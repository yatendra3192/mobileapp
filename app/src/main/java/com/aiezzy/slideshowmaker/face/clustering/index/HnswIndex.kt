package com.aiezzy.slideshowmaker.face.clustering.index

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random

/**
 * Hierarchical Navigable Small World (HNSW) index for efficient approximate nearest neighbor search.
 *
 * Key features:
 * - O(log n) search complexity vs O(n) for linear search
 * - Supports incremental insertion
 * - Persistence to disk for fast startup
 * - Thread-safe operations
 *
 * Algorithm parameters:
 * - M: Maximum number of connections per node per layer (default 16)
 * - efConstruction: Size of dynamic candidate list during construction (default 200)
 * - efSearch: Size of dynamic candidate list during search (default 50)
 * - mL: Level generation factor (default 1/ln(M))
 *
 * Reference: https://arxiv.org/abs/1603.09320
 */
class HnswIndex(
    private val dimensions: Int,
    private val m: Int = 16,
    private val efConstruction: Int = 200,
    private val mL: Double = 1.0 / ln(16.0),
    private var efSearch: Int = 50
) {
    companion object {
        private const val TAG = "HnswIndex"
        private const val SERIALIZATION_VERSION = 1
    }

    // Node representation
    private data class Node(
        val id: String,
        val embedding: FloatArray,
        val connections: MutableList<MutableList<String>> = mutableListOf() // connections per layer
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Node) return false
            return id == other.id
        }

        override fun hashCode(): Int = id.hashCode()
    }

    // Search result
    data class SearchResult(
        val id: String,
        val distance: Float
    ) : Comparable<SearchResult> {
        override fun compareTo(other: SearchResult): Int = distance.compareTo(other.distance)
    }

    private val nodes = mutableMapOf<String, Node>()
    private var entryPointId: String? = null
    private var maxLevel = 0
    private val mutex = Mutex()
    private val random = Random(System.currentTimeMillis())

    /**
     * Number of items in the index.
     */
    val size: Int get() = nodes.size

    /**
     * Check if the index is empty.
     */
    val isEmpty: Boolean get() = nodes.isEmpty()

    /**
     * Insert a new item into the index.
     *
     * @param id Unique identifier for the item
     * @param embedding The embedding vector (must match dimensions)
     */
    suspend fun insert(id: String, embedding: FloatArray) = mutex.withLock {
        require(embedding.size == dimensions) {
            "Embedding size ${embedding.size} doesn't match index dimensions $dimensions"
        }

        if (nodes.containsKey(id)) {
            Log.v(TAG, "Item $id already exists, updating embedding")
            nodes[id]?.let { node ->
                // Update embedding in place
                System.arraycopy(embedding, 0, node.embedding, 0, dimensions)
            }
            return@withLock
        }

        // Assign random level for new node
        val level = randomLevel()
        val newNode = Node(
            id = id,
            embedding = embedding.copyOf(),
            connections = MutableList(level + 1) { mutableListOf() }
        )

        if (nodes.isEmpty()) {
            // First node becomes entry point
            nodes[id] = newNode
            entryPointId = id
            maxLevel = level
            return@withLock
        }

        nodes[id] = newNode

        var currentId = entryPointId!!
        var currentLevel = maxLevel

        // Find entry point at the target level
        while (currentLevel > level) {
            val changed = greedySearchLevel(embedding, currentId, currentLevel)
            if (changed != currentId) {
                currentId = changed
            } else {
                currentLevel--
            }
        }

        // Insert at each level from level down to 0
        for (lc in minOf(level, maxLevel) downTo 0) {
            val neighbors = searchLevel(embedding, currentId, efConstruction, lc)
            val selectedNeighbors = selectNeighbors(embedding, neighbors, m)

            // Connect new node to selected neighbors
            newNode.connections[lc].addAll(selectedNeighbors.map { it.id })

            // Connect neighbors back to new node
            for (neighbor in selectedNeighbors) {
                val neighborNode = nodes[neighbor.id] ?: continue
                if (neighborNode.connections.size <= lc) {
                    // Extend connections list if needed
                    while (neighborNode.connections.size <= lc) {
                        neighborNode.connections.add(mutableListOf())
                    }
                }
                neighborNode.connections[lc].add(id)

                // Prune if exceeding M connections
                if (neighborNode.connections[lc].size > m) {
                    val neighborEmbedding = neighborNode.embedding
                    val candidates = neighborNode.connections[lc].mapNotNull { connId ->
                        nodes[connId]?.let { SearchResult(connId, distance(neighborEmbedding, it.embedding)) }
                    }
                    val pruned = selectNeighbors(neighborEmbedding, candidates, m)
                    neighborNode.connections[lc].clear()
                    neighborNode.connections[lc].addAll(pruned.map { it.id })
                }
            }

            if (neighbors.isNotEmpty()) {
                currentId = neighbors.first().id
            }
        }

        // Update entry point if new node has higher level
        if (level > maxLevel) {
            maxLevel = level
            entryPointId = id
        }
    }

    /**
     * Remove an item from the index.
     * Note: This is a soft delete - connections are not fully cleaned up.
     * For production use, periodic rebuilding is recommended.
     */
    suspend fun remove(id: String) = mutex.withLock {
        nodes.remove(id)
        if (entryPointId == id) {
            // Find new entry point
            entryPointId = nodes.keys.firstOrNull()
            maxLevel = nodes.values.maxOfOrNull { it.connections.size - 1 } ?: 0
        }
    }

    /**
     * Search for k nearest neighbors.
     *
     * @param query The query embedding vector
     * @param k Number of neighbors to return
     * @return List of SearchResult sorted by distance (ascending)
     */
    suspend fun search(query: FloatArray, k: Int): List<SearchResult> = mutex.withLock {
        require(query.size == dimensions) {
            "Query size ${query.size} doesn't match index dimensions $dimensions"
        }

        if (nodes.isEmpty()) return@withLock emptyList()

        var currentId = entryPointId!!
        var currentLevel = maxLevel

        // Traverse from top layer to layer 1
        while (currentLevel > 0) {
            val changed = greedySearchLevel(query, currentId, currentLevel)
            if (changed != currentId) {
                currentId = changed
            } else {
                currentLevel--
            }
        }

        // Search at layer 0
        val candidates = searchLevel(query, currentId, max(efSearch, k), 0)
        return@withLock candidates.take(k)
    }

    /**
     * Search for neighbors within a distance threshold.
     *
     * @param query The query embedding vector
     * @param threshold Maximum distance (cosine distance, so 0 = identical)
     * @param maxResults Maximum number of results to return
     * @return List of SearchResult within threshold, sorted by distance
     */
    suspend fun searchWithThreshold(
        query: FloatArray,
        threshold: Float,
        maxResults: Int = 100
    ): List<SearchResult> {
        val results = search(query, maxResults)
        return results.filter { it.distance <= threshold }
    }

    /**
     * Set the efSearch parameter for tuning search accuracy/speed tradeoff.
     * Higher values = more accurate but slower.
     */
    fun setEfSearch(ef: Int) {
        efSearch = ef
    }

    /**
     * Get embedding for an item.
     */
    fun getEmbedding(id: String): FloatArray? = nodes[id]?.embedding?.copyOf()

    /**
     * Check if an item exists in the index.
     */
    fun contains(id: String): Boolean = nodes.containsKey(id)

    /**
     * Get all item IDs in the index.
     */
    fun getAllIds(): Set<String> = nodes.keys.toSet()

    /**
     * Clear all items from the index.
     */
    suspend fun clear() = mutex.withLock {
        nodes.clear()
        entryPointId = null
        maxLevel = 0
    }

    // Greedy search at a specific level - returns closest node
    private fun greedySearchLevel(query: FloatArray, startId: String, level: Int): String {
        var currentId = startId
        var currentDist = distance(query, nodes[currentId]?.embedding ?: return currentId)

        while (true) {
            val node = nodes[currentId] ?: break
            if (node.connections.size <= level) break

            var changed = false
            for (neighborId in node.connections[level]) {
                val neighborNode = nodes[neighborId] ?: continue
                val dist = distance(query, neighborNode.embedding)
                if (dist < currentDist) {
                    currentDist = dist
                    currentId = neighborId
                    changed = true
                }
            }

            if (!changed) break
        }

        return currentId
    }

    // Search at a specific level with ef candidates
    private fun searchLevel(
        query: FloatArray,
        startId: String,
        ef: Int,
        level: Int
    ): List<SearchResult> {
        val visited = mutableSetOf<String>()
        val candidates = sortedSetOf(compareBy<SearchResult> { it.distance })
        val results = sortedSetOf(compareBy<SearchResult> { -it.distance }) // Max heap for results

        val startNode = nodes[startId] ?: return emptyList()
        val startDist = distance(query, startNode.embedding)
        candidates.add(SearchResult(startId, startDist))
        results.add(SearchResult(startId, startDist))
        visited.add(startId)

        while (candidates.isNotEmpty()) {
            val current = candidates.first()
            candidates.remove(current)

            val furthestResult = results.first()
            if (current.distance > furthestResult.distance) break

            val node = nodes[current.id] ?: continue
            if (node.connections.size <= level) continue

            for (neighborId in node.connections[level]) {
                if (neighborId in visited) continue
                visited.add(neighborId)

                val neighborNode = nodes[neighborId] ?: continue
                val dist = distance(query, neighborNode.embedding)
                val furthest = results.first()

                if (dist < furthest.distance || results.size < ef) {
                    candidates.add(SearchResult(neighborId, dist))
                    results.add(SearchResult(neighborId, dist))

                    if (results.size > ef) {
                        results.remove(results.first())
                    }
                }
            }
        }

        return results.sortedBy { it.distance }
    }

    // Select M best neighbors using simple heuristic
    private fun selectNeighbors(
        query: FloatArray,
        candidates: List<SearchResult>,
        maxNeighbors: Int
    ): List<SearchResult> {
        return candidates.sortedBy { it.distance }.take(maxNeighbors)
    }

    // Generate random level with exponential distribution
    private fun randomLevel(): Int {
        var level = 0
        while (random.nextDouble() < mL && level < 16) {
            level++
        }
        return level
    }

    // Cosine distance (1 - cosine similarity) for normalized vectors
    // For L2-normalized vectors: cosine_distance = 1 - dot_product
    private fun distance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return 1f - dot // Cosine distance
    }

    /**
     * Save the index to a file.
     */
    suspend fun save(file: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ObjectOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
                out.writeInt(SERIALIZATION_VERSION)
                out.writeInt(dimensions)
                out.writeInt(m)
                out.writeInt(efConstruction)
                out.writeDouble(mL)
                out.writeInt(efSearch)
                out.writeInt(maxLevel)
                out.writeObject(entryPointId)
                out.writeInt(nodes.size)

                for ((id, node) in nodes) {
                    out.writeObject(id)
                    out.writeObject(node.embedding)
                    out.writeInt(node.connections.size)
                    for (layer in node.connections) {
                        out.writeInt(layer.size)
                        for (connId in layer) {
                            out.writeObject(connId)
                        }
                    }
                }
            }
            Log.i(TAG, "Saved HNSW index with ${nodes.size} items to ${file.absolutePath}")
        }
    }

    /**
     * Load the index from a file.
     */
    suspend fun load(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false

        mutex.withLock {
            try {
                ObjectInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                    val version = input.readInt()
                    if (version != SERIALIZATION_VERSION) {
                        Log.w(TAG, "Index version mismatch: expected $SERIALIZATION_VERSION, got $version")
                        return@withContext false
                    }

                    val dims = input.readInt()
                    if (dims != dimensions) {
                        Log.w(TAG, "Dimensions mismatch: expected $dimensions, got $dims")
                        return@withContext false
                    }

                    // Skip parameters (they should match constructor)
                    input.readInt() // m
                    input.readInt() // efConstruction
                    input.readDouble() // mL
                    efSearch = input.readInt()
                    maxLevel = input.readInt()
                    entryPointId = input.readObject() as? String

                    val nodeCount = input.readInt()
                    nodes.clear()

                    repeat(nodeCount) {
                        val id = input.readObject() as String
                        val embedding = input.readObject() as FloatArray
                        val layerCount = input.readInt()
                        val connections = MutableList(layerCount) { mutableListOf<String>() }

                        repeat(layerCount) { layer ->
                            val connCount = input.readInt()
                            repeat(connCount) {
                                connections[layer].add(input.readObject() as String)
                            }
                        }

                        nodes[id] = Node(id, embedding, connections)
                    }
                }
                Log.i(TAG, "Loaded HNSW index with ${nodes.size} items from ${file.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load HNSW index", e)
                false
            }
        }
    }

    /**
     * Get index statistics for debugging.
     */
    fun getStats(): IndexStats {
        val layerDistribution = mutableMapOf<Int, Int>()
        var totalConnections = 0

        for (node in nodes.values) {
            val level = node.connections.size - 1
            layerDistribution[level] = (layerDistribution[level] ?: 0) + 1
            totalConnections += node.connections.sumOf { it.size }
        }

        return IndexStats(
            size = nodes.size,
            dimensions = dimensions,
            maxLevel = maxLevel,
            m = m,
            efSearch = efSearch,
            layerDistribution = layerDistribution,
            avgConnections = if (nodes.isNotEmpty()) totalConnections.toFloat() / nodes.size else 0f
        )
    }

    data class IndexStats(
        val size: Int,
        val dimensions: Int,
        val maxLevel: Int,
        val m: Int,
        val efSearch: Int,
        val layerDistribution: Map<Int, Int>,
        val avgConnections: Float
    )
}
