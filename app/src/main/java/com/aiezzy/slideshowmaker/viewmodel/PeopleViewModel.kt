package com.aiezzy.slideshowmaker.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiezzy.slideshowmaker.data.face.entities.PersonWithFace
import com.aiezzy.slideshowmaker.data.face.entities.ScanProgressEntity
import com.aiezzy.slideshowmaker.face.FaceRepository
import com.aiezzy.slideshowmaker.face.scanner.GalleryScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MVI-style ViewModel for managing face grouping / people features.
 *
 * Key features:
 * - Sealed UI state for type-safe state management
 * - SavedStateHandle for rotation survival
 * - Debounced search and filter operations
 * - Error handling with retry capability
 * - Undo support for destructive actions
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val application: Application,
    private val repository: FaceRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "PeopleViewModel"
        private const val KEY_SELECTED_PERSON_IDS = "selected_person_ids"
        private const val KEY_CURRENT_PERSON_ID = "current_person_id"
        private const val KEY_SEARCH_QUERY = "search_query"
        private const val KEY_MERGE_MODE = "merge_mode"
        private const val KEY_MERGE_SELECTION = "merge_selection"
        private const val KEY_ONLY_THEM_MODE = "only_them_mode"
        private const val DEBOUNCE_MS = 300L
        private const val UNDO_TIMEOUT_MS = 5000L

        // UI update throttling - prevents flickering while ensuring immediate first update
        // CHANGED FROM DEBOUNCE: Throttle shows first result immediately, then batches subsequent
        // Debounce waits for silence, causing faces to appear then disappear
        private const val UI_UPDATE_THROTTLE_MS = 300L   // Maximum update rate during scan
        private const val UI_UPDATE_IDLE_THROTTLE_MS = 100L  // Faster when idle
    }

    // ============ UI State ============

    /**
     * Sealed class representing all possible UI states.
     */
    sealed class PeopleUiState {
        object Loading : PeopleUiState()
        data class Success(
            val persons: List<PersonWithFace>,
            val isRefreshing: Boolean = false
        ) : PeopleUiState()
        data class Empty(val message: String = "No people found") : PeopleUiState()
        data class Error(
            val message: String,
            val canRetry: Boolean = true
        ) : PeopleUiState()
    }

    /**
     * Sealed class for scan state.
     */
    sealed class ScanState {
        object Idle : ScanState()
        data class Scanning(
            val progress: Float,
            val scannedCount: Int,
            val totalCount: Int,
            val facesFound: Int
        ) : ScanState()
        object Paused : ScanState()
        data class Complete(
            val totalScanned: Int,
            val facesFound: Int
        ) : ScanState()
        data class Error(val message: String) : ScanState()
    }

    /**
     * Represents an action that can be undone.
     */
    data class UndoAction(
        val message: String,
        val action: suspend () -> Unit,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ============ State Flows ============

    // Main UI state
    private val _uiState = MutableStateFlow<PeopleUiState>(PeopleUiState.Loading)
    val uiState: StateFlow<PeopleUiState> = _uiState.asStateFlow()

    // Scan state
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // Search query with debouncing
    private val _searchQuery = MutableStateFlow(
        savedStateHandle.get<String>(KEY_SEARCH_QUERY) ?: ""
    )
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Selected person IDs for filtering (saved across rotation)
    private val _selectedPersonIds = MutableStateFlow<Set<String>>(
        savedStateHandle.get<ArrayList<String>>(KEY_SELECTED_PERSON_IDS)?.toSet() ?: emptySet()
    )
    val selectedPersonIds: StateFlow<Set<String>> = _selectedPersonIds.asStateFlow()

    // Filtered photo URIs based on selected persons
    private val _filteredPhotos = MutableStateFlow<List<Uri>>(emptyList())
    val filteredPhotos: StateFlow<List<Uri>> = _filteredPhotos.asStateFlow()

    // Loading state for photo filtering
    private val _isLoadingPhotos = MutableStateFlow(false)
    val isLoadingPhotos: StateFlow<Boolean> = _isLoadingPhotos.asStateFlow()

    // Currently viewed person (for PersonDetailScreen)
    private val _currentPerson = MutableStateFlow<PersonWithFace?>(null)
    val currentPerson: StateFlow<PersonWithFace?> = _currentPerson.asStateFlow()

    // "Only them" mode - when enabled, shows only photos where the person appears alone
    // Default is false - shows all photos containing the person (including group photos)
    private val _onlyThemMode = MutableStateFlow(
        savedStateHandle.get<Boolean>(KEY_ONLY_THEM_MODE) ?: false
    )
    val onlyThemMode: StateFlow<Boolean> = _onlyThemMode.asStateFlow()

    // Merge mode state (saved across rotation)
    private val _mergeSelectionMode = MutableStateFlow(
        savedStateHandle.get<Boolean>(KEY_MERGE_MODE) ?: false
    )
    val mergeSelectionMode: StateFlow<Boolean> = _mergeSelectionMode.asStateFlow()

    private val _selectedForMerge = MutableStateFlow<Set<String>>(
        savedStateHandle.get<ArrayList<String>>(KEY_MERGE_SELECTION)?.toSet() ?: emptySet()
    )
    val selectedForMerge: StateFlow<Set<String>> = _selectedForMerge.asStateFlow()

    // Undo action (for snackbar)
    private val _undoAction = MutableStateFlow<UndoAction?>(null)
    val undoAction: StateFlow<UndoAction?> = _undoAction.asStateFlow()

    // Refreshing state for pull-to-refresh
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Error message for snackbar
    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    // Success message for snackbar
    private val _successMessage = MutableSharedFlow<String>()
    val successMessage: SharedFlow<String> = _successMessage.asSharedFlow()

    // Jobs for cancellation
    private var filterJob: Job? = null
    private var undoTimeoutJob: Job? = null
    private var personsFlowJob: Job? = null

    // Stable UI tracking - cache for maintaining consistent ordering
    private val stablePersonsCache = mutableMapOf<String, PersonWithFace>()

    // ============ Derived State ============

    val hasPersons: StateFlow<Boolean> = uiState.map { state ->
        state is PeopleUiState.Success && state.persons.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val isScanRunning: StateFlow<Boolean> = scanState.map { state ->
        state is ScanState.Scanning
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val scanPercentage: StateFlow<Float> = scanState.map { state ->
        when (state) {
            is ScanState.Scanning -> state.progress
            is ScanState.Complete -> 1f
            else -> 0f
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    // ============ Initialization ============

    init {
        // Load persons immediately - don't wait for orphan fix
        // CHANGED: Removed fixOrphanedClustersAndLoad() to prevent race condition
        // Orphan fix now happens only when scan completes (see observeScanProgress)
        loadPersons()
        observeScanProgress()
        setupSearchDebounce()
        setupStateSaving()
    }

    /**
     * Fix orphaned clusters (clusters without person assigned).
     * CHANGED: Now called only when scan completes, not at startup.
     * This prevents race conditions where orphan fix runs while scan is inserting new data.
     */
    private fun fixOrphanedClustersIfNeeded() {
        viewModelScope.launch {
            try {
                val fixedCount = repository.fixOrphanedClusters()
                if (fixedCount > 0) {
                    Log.i(TAG, "Fixed $fixedCount orphaned clusters after scan completion")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fixing orphaned clusters", e)
            }
        }
    }

    /**
     * Load persons with stable UI updates using THROTTLE (not debounce).
     *
     * KEY CHANGE: Replaced debounce with throttle-like behavior.
     *
     * Why this matters:
     * - DEBOUNCE: Waits for silence, cancels intermediate emissions
     *   → Faces appear briefly, then disappear when debounce fires
     * - THROTTLE: Emits first result immediately, then batches subsequent updates
     *   → Faces appear and stay, never randomly disappear
     *
     * The flow:
     * 1. First emission goes through immediately (user sees faces fast)
     * 2. Subsequent emissions are throttled to prevent UI thrashing
     * 3. distinctUntilChanged ensures only meaningful changes update UI
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun loadPersons() {
        // Cancel any existing collection
        personsFlowJob?.cancel()

        personsFlowJob = viewModelScope.launch {
            _uiState.value = PeopleUiState.Loading
            try {
                var isFirstEmission = true
                var lastEmissionTime = 0L

                repository.getPersonsWithFaceFlow()
                    .combine(_scanState) { persons, scanState ->
                        Triple(persons, scanState is ScanState.Scanning, scanState)
                    }
                    // THROTTLE-LIKE BEHAVIOR: First emission immediate, then rate-limited
                    .filter { (persons, isScanning, _) ->
                        val now = System.currentTimeMillis()
                        val throttleMs = if (isScanning) UI_UPDATE_THROTTLE_MS else UI_UPDATE_IDLE_THROTTLE_MS

                        // Always allow first emission or if throttle window passed
                        val shouldEmit = isFirstEmission ||
                                (now - lastEmissionTime) >= throttleMs ||
                                (_uiState.value is PeopleUiState.Loading)

                        if (shouldEmit) {
                            isFirstEmission = false
                            lastEmissionTime = now
                        }
                        shouldEmit
                    }
                    .map { (persons, _, _) -> persons }
                    // Apply stable ordering to minimize visual reordering
                    .map { persons -> stabilizePersonsOrder(persons) }
                    // Only update UI if there's meaningful change
                    .distinctUntilChanged { old, new ->
                        old.size == new.size &&
                        old.zip(new).all { (oldP, newP) ->
                            oldP.personId == newP.personId &&
                            oldP.photoCount == newP.photoCount &&
                            oldP.faceId == newP.faceId &&
                            oldP.name == newP.name  // Also track name changes
                        }
                    }
                    .catch { e ->
                        Log.e(TAG, "Error loading persons", e)
                        _uiState.value = PeopleUiState.Error(
                            message = e.message ?: "Failed to load people",
                            canRetry = true
                        )
                    }
                    .collect { persons ->
                        // Update cache for stable ordering
                        persons.forEach { person ->
                            stablePersonsCache[person.personId] = person
                        }
                        // Remove deleted persons from cache
                        val currentIds = persons.map { it.personId }.toSet()
                        stablePersonsCache.keys.removeAll { it !in currentIds }

                        _uiState.value = if (persons.isEmpty()) {
                            PeopleUiState.Empty()
                        } else {
                            PeopleUiState.Success(
                                persons = filterBySearchQuery(persons),
                                isRefreshing = _isRefreshing.value
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up persons flow", e)
                _uiState.value = PeopleUiState.Error(
                    message = e.message ?: "Failed to load people",
                    canRetry = true
                )
            }
        }
    }

    /**
     * Stabilize the order of persons to minimize UI reordering.
     * New persons are added at the end, existing persons maintain their relative order.
     * Persons are then sorted by photoCount (descending) for meaningful display.
     */
    private fun stabilizePersonsOrder(persons: List<PersonWithFace>): List<PersonWithFace> {
        // During initial load, just sort by photo count
        if (stablePersonsCache.isEmpty()) {
            return persons.sortedByDescending { it.photoCount }
        }

        // Separate existing and new persons
        val existingPersonIds = stablePersonsCache.keys
        val (existingPersons, newPersons) = persons.partition { it.personId in existingPersonIds }

        // Sort existing by their current photo count (maintains relative quality order)
        // Sort new persons by photo count and add at the end
        return existingPersons
            .sortedByDescending { it.photoCount }
            .plus(newPersons.sortedByDescending { it.photoCount })
    }

    private fun observeScanProgress() {
        viewModelScope.launch {
            var wasScanning = false

            repository.getScanProgressFlow()
                .collect { progress ->
                    val newState = when {
                        progress == null -> ScanState.Idle
                        progress.isComplete -> ScanState.Complete(
                            totalScanned = progress.scannedPhotos,
                            facesFound = progress.facesDetected
                        )
                        GalleryScanWorker.isPaused(application) -> ScanState.Paused
                        progress.errorMessage != null -> ScanState.Error(progress.errorMessage)
                        else -> ScanState.Scanning(
                            progress = if (progress.totalPhotos > 0) {
                                progress.scannedPhotos.toFloat() / progress.totalPhotos
                            } else 0f,
                            scannedCount = progress.scannedPhotos,
                            totalCount = progress.totalPhotos,
                            facesFound = progress.facesDetected
                        )
                    }

                    // CHANGED: Fix orphaned clusters ONLY when scan transitions to complete
                    // This prevents race conditions during active scanning
                    val isNowComplete = newState is ScanState.Complete
                    if (wasScanning && isNowComplete) {
                        Log.i(TAG, "Scan completed, fixing orphaned clusters")
                        fixOrphanedClustersIfNeeded()
                    }

                    wasScanning = newState is ScanState.Scanning
                    _scanState.value = newState
                }
        }
    }

    @OptIn(FlowPreview::class)
    private fun setupSearchDebounce() {
        viewModelScope.launch {
            _searchQuery
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { query ->
                    savedStateHandle[KEY_SEARCH_QUERY] = query
                    // Re-filter the current persons list
                    val currentState = _uiState.value
                    if (currentState is PeopleUiState.Success) {
                        // Trigger a refresh of the filtered list
                        loadPersons()
                    }
                }
        }
    }

    private fun setupStateSaving() {
        // Save selected person IDs
        viewModelScope.launch {
            _selectedPersonIds.collect { ids ->
                savedStateHandle[KEY_SELECTED_PERSON_IDS] = ArrayList(ids)
            }
        }
        // Save merge mode
        viewModelScope.launch {
            _mergeSelectionMode.collect { mode ->
                savedStateHandle[KEY_MERGE_MODE] = mode
            }
        }
        // Save merge selection
        viewModelScope.launch {
            _selectedForMerge.collect { ids ->
                savedStateHandle[KEY_MERGE_SELECTION] = ArrayList(ids)
            }
        }
    }

    private fun filterBySearchQuery(persons: List<PersonWithFace>): List<PersonWithFace> {
        val query = _searchQuery.value.trim().lowercase()
        if (query.isEmpty()) return persons
        return persons.filter { person ->
            person.name?.lowercase()?.contains(query) == true
        }
    }

    // ============ Actions ============

    /**
     * Retry loading after an error
     */
    fun retry() {
        loadPersons()
    }

    /**
     * Refresh the persons list (for pull-to-refresh)
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Force refresh by reloading
                loadPersons()
            } finally {
                delay(500) // Minimum refresh time for UX
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Update search query
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Start background gallery scan
     */
    fun startGalleryScan(forceRescan: Boolean = false) {
        GalleryScanWorker.schedule(application, forceRescan)
        _scanState.value = ScanState.Scanning(0f, 0, 0, 0)
    }

    /**
     * Cancel running scan
     */
    fun cancelScan() {
        GalleryScanWorker.cancel(application)
        _scanState.value = ScanState.Idle
    }

    /**
     * Pause running scan
     */
    fun pauseScan() {
        GalleryScanWorker.pause(application)
        _scanState.value = ScanState.Paused
    }

    /**
     * Resume paused scan
     */
    fun resumeScan() {
        GalleryScanWorker.resume(application)
    }

    /**
     * Select a person for filtering
     */
    fun selectPerson(personId: String) {
        _selectedPersonIds.update { it + personId }
        loadFilteredPhotosDebounced()
    }

    /**
     * Deselect a person
     */
    fun deselectPerson(personId: String) {
        _selectedPersonIds.update { it - personId }
        loadFilteredPhotosDebounced()
    }

    /**
     * Toggle person selection
     */
    fun togglePersonSelection(personId: String) {
        _selectedPersonIds.update { current ->
            if (personId in current) current - personId else current + personId
        }
        loadFilteredPhotosDebounced()
    }

    /**
     * Clear all selections
     */
    fun clearSelection() {
        _selectedPersonIds.value = emptySet()
        _filteredPhotos.value = emptyList()
    }

    /**
     * Set single person selection (for "Only them" mode)
     */
    fun selectOnlyPerson(personId: String) {
        _selectedPersonIds.value = setOf(personId)
        loadFilteredPhotosDebounced()
    }

    /**
     * Load current person for detail screen.
     * Shows all photos of this person by default (onlyThemMode = false).
     */
    fun loadPerson(personId: String) {
        savedStateHandle[KEY_CURRENT_PERSON_ID] = personId
        viewModelScope.launch {
            try {
                _currentPerson.value = repository.getPersonWithFace(personId)
                // Reset onlyThemMode when loading a new person
                _onlyThemMode.value = false
                // Load all photos for this person (not filtered to "only them")
                loadPhotosForPerson(personId)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading person $personId", e)
                _errorMessage.emit("Failed to load person details")
            }
        }
    }

    /**
     * Toggle "Only them" mode for the current person.
     * When enabled, shows only photos where this person appears alone.
     * When disabled, shows all photos containing this person (including group photos).
     */
    fun toggleOnlyThemMode() {
        val currentPersonId = _currentPerson.value?.personId ?: return
        _onlyThemMode.value = !_onlyThemMode.value
        savedStateHandle[KEY_ONLY_THEM_MODE] = _onlyThemMode.value
        loadPhotosForPerson(currentPersonId)
    }

    /**
     * Load photos for a specific person.
     * Respects onlyThemMode setting.
     */
    private fun loadPhotosForPerson(personId: String) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            _isLoadingPhotos.value = true
            try {
                val photoUris = if (_onlyThemMode.value) {
                    // Only photos where this person appears alone
                    repository.getSoloPhotosForPerson(personId)
                } else {
                    // All photos containing this person
                    repository.getPhotosForPerson(personId)
                }
                _filteredPhotos.value = photoUris.map { Uri.parse(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos for person $personId", e)
                _errorMessage.emit("Failed to load photos")
            } finally {
                _isLoadingPhotos.value = false
            }
        }
    }

    /**
     * Load filtered photos with debounce
     */
    private fun loadFilteredPhotosDebounced() {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            loadFilteredPhotos()
        }
    }

    private suspend fun loadFilteredPhotos() {
        val personIds = _selectedPersonIds.value.toList()
        if (personIds.isEmpty()) {
            _filteredPhotos.value = emptyList()
            return
        }

        _isLoadingPhotos.value = true
        try {
            val photoUris = repository.getPhotosContainingAllPersons(personIds)
            _filteredPhotos.value = photoUris.map { Uri.parse(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading filtered photos", e)
            _errorMessage.emit("Failed to load photos")
        } finally {
            _isLoadingPhotos.value = false
        }
    }

    /**
     * Update person name
     */
    fun updatePersonName(personId: String, name: String) {
        viewModelScope.launch {
            try {
                val oldName = _currentPerson.value?.name
                repository.updatePersonName(personId, name.ifBlank { null })
                _successMessage.emit("Name updated")

                // Set up undo action
                if (oldName != name) {
                    setUndoAction("Name changed") {
                        repository.updatePersonName(personId, oldName)
                        loadPerson(personId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating person name", e)
                _errorMessage.emit("Failed to update name")
            }
        }
    }

    /**
     * Update person profile (name and birthday)
     * @param personId The person's ID
     * @param name The new name (can be blank to clear)
     * @param birthday The birthday in "MM-DD" format (e.g., "01-15" for January 15th), or null to clear
     */
    fun updatePersonProfile(personId: String, name: String, birthday: String?) {
        viewModelScope.launch {
            try {
                val oldPerson = repository.getPerson(personId)
                val oldName = oldPerson?.name
                val oldBirthday = oldPerson?.birthday

                repository.updatePersonProfile(
                    personId = personId,
                    name = name.ifBlank { null },
                    birthday = birthday
                )

                // Refresh current person
                loadPerson(personId)

                _successMessage.emit("Profile updated")

                // Set up undo action
                if (oldName != name || oldBirthday != birthday) {
                    setUndoAction("Profile changed") {
                        repository.updatePersonProfile(personId, oldName, oldBirthday)
                        loadPerson(personId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating person profile", e)
                _errorMessage.emit("Failed to update profile")
            }
        }
    }

    /**
     * Hide a person from the list with undo support
     */
    fun hidePerson(personId: String) {
        viewModelScope.launch {
            try {
                repository.hidePersons(personId)
                deselectPerson(personId)

                setUndoAction("Person hidden") {
                    repository.showPerson(personId)
                }

                _successMessage.emit("Person hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding person", e)
                _errorMessage.emit("Failed to hide person")
            }
        }
    }

    /**
     * Remove a photo from a person's group with undo support
     */
    fun removePhotoFromPerson(photoUri: Uri, personId: String) {
        viewModelScope.launch {
            try {
                val success = repository.removePhotoFromPerson(photoUri.toString(), personId)
                if (success) {
                    loadFilteredPhotosDebounced()
                    setUndoAction("Photo removed") {
                        // Note: Full undo would require storing the face ID and cluster ID
                        // For now, we just show the message
                        _successMessage.emit("Undo not available for this action")
                    }
                    _successMessage.emit("Photo removed from person")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing photo from person", e)
                _errorMessage.emit("Failed to remove photo")
            }
        }
    }

    // ============ Merge Operations ============

    /**
     * Enter merge selection mode
     */
    fun enterMergeMode() {
        _mergeSelectionMode.value = true
        _selectedForMerge.value = emptySet()
    }

    /**
     * Exit merge selection mode
     */
    fun exitMergeMode() {
        _mergeSelectionMode.value = false
        _selectedForMerge.value = emptySet()
    }

    /**
     * Toggle person selection for merge
     */
    fun toggleMergeSelection(personId: String) {
        _selectedForMerge.update { current ->
            if (personId in current) current - personId else current + personId
        }
    }

    /**
     * Merge all selected persons
     */
    fun mergeSelectedPersons() {
        val selectedIds = _selectedForMerge.value.toList()
        if (selectedIds.size < 2) {
            viewModelScope.launch {
                _errorMessage.emit("Select at least 2 people to merge")
            }
            return
        }

        viewModelScope.launch {
            try {
                val success = repository.mergeMultiplePersons(selectedIds)
                if (success) {
                    exitMergeMode()
                    _successMessage.emit("${selectedIds.size} people merged")
                } else {
                    _errorMessage.emit("Failed to merge people")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error merging persons", e)
                _errorMessage.emit("Failed to merge people")
            }
        }
    }

    /**
     * Merge two persons (same person detected as different)
     */
    fun mergePersons(sourcePersonId: String, targetPersonId: String) {
        viewModelScope.launch {
            try {
                repository.mergePersons(sourcePersonId, targetPersonId)
                _successMessage.emit("People merged successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error merging persons", e)
                _errorMessage.emit("Failed to merge people")
            }
        }
    }

    // ============ Undo Support ============

    private fun setUndoAction(message: String, action: suspend () -> Unit) {
        undoTimeoutJob?.cancel()
        _undoAction.value = UndoAction(message, action)

        // Auto-clear undo after timeout
        undoTimeoutJob = viewModelScope.launch {
            delay(UNDO_TIMEOUT_MS)
            _undoAction.value = null
        }
    }

    /**
     * Execute the undo action
     */
    fun executeUndo() {
        val action = _undoAction.value ?: return
        undoTimeoutJob?.cancel()
        _undoAction.value = null

        viewModelScope.launch {
            try {
                action.action()
                _successMessage.emit("Action undone")
            } catch (e: Exception) {
                Log.e(TAG, "Error executing undo", e)
                _errorMessage.emit("Failed to undo action")
            }
        }
    }

    /**
     * Dismiss the undo snackbar
     */
    fun dismissUndo() {
        undoTimeoutJob?.cancel()
        _undoAction.value = null
    }

    // ============ Utility ============

    /**
     * Get top N persons for preview (e.g., home screen card)
     */
    fun getTopPersons(count: Int): Flow<List<PersonWithFace>> {
        return uiState.map { state ->
            when (state) {
                is PeopleUiState.Success -> state.persons.take(count)
                else -> emptyList()
            }
        }
    }

    /**
     * Clear all face data and rescan
     */
    fun resetAndRescan() {
        viewModelScope.launch {
            try {
                repository.clearAllData()
                _uiState.value = PeopleUiState.Loading
                startGalleryScan(forceRescan = true)
                _successMessage.emit("Rescan started")
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting data", e)
                _errorMessage.emit("Failed to reset data")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        filterJob?.cancel()
        undoTimeoutJob?.cancel()
        personsFlowJob?.cancel()
        stablePersonsCache.clear()
    }
}
