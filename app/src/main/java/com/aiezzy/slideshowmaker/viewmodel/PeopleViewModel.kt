package com.aiezzy.slideshowmaker.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiezzy.slideshowmaker.data.face.entities.PersonWithFace
import com.aiezzy.slideshowmaker.data.face.entities.ScanProgressEntity
import com.aiezzy.slideshowmaker.face.FaceRepository
import com.aiezzy.slideshowmaker.face.scanner.GalleryScanWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for managing face grouping / people features
 */
class PeopleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FaceRepository.getInstance(application)

    // All visible persons with their representative faces
    val persons: StateFlow<List<PersonWithFace>> = repository.getPersonsWithFaceFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Scan progress
    val scanProgress: StateFlow<ScanProgressEntity?> = repository.getScanProgressFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Selected person IDs for filtering
    private val _selectedPersonIds = MutableStateFlow<Set<String>>(emptySet())
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

    // Is scan currently running
    val isScanRunning: StateFlow<Boolean> = scanProgress.map { progress ->
        progress != null && !progress.isComplete
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    // Scan completion percentage
    val scanPercentage: StateFlow<Float> = scanProgress.map { progress ->
        if (progress == null || progress.totalPhotos == 0) 0f
        else progress.scannedPhotos.toFloat() / progress.totalPhotos
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    /**
     * Start background gallery scan
     */
    fun startGalleryScan(forceRescan: Boolean = false) {
        GalleryScanWorker.schedule(getApplication(), forceRescan)
    }

    /**
     * Cancel running scan
     */
    fun cancelScan() {
        GalleryScanWorker.cancel(getApplication())
    }

    /**
     * Select a person for filtering
     */
    fun selectPerson(personId: String) {
        _selectedPersonIds.update { current ->
            current + personId
        }
        loadFilteredPhotos()
    }

    /**
     * Deselect a person
     */
    fun deselectPerson(personId: String) {
        _selectedPersonIds.update { current ->
            current - personId
        }
        loadFilteredPhotos()
    }

    /**
     * Toggle person selection
     */
    fun togglePersonSelection(personId: String) {
        _selectedPersonIds.update { current ->
            if (personId in current) {
                current - personId
            } else {
                current + personId
            }
        }
        loadFilteredPhotos()
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
        loadFilteredPhotos()
    }

    /**
     * Load current person for detail screen
     */
    fun loadPerson(personId: String) {
        viewModelScope.launch {
            _currentPerson.value = repository.getPersonWithFace(personId)
            // Auto-select this person
            selectOnlyPerson(personId)
        }
    }

    /**
     * Load photos matching selected persons
     */
    private fun loadFilteredPhotos() {
        val personIds = _selectedPersonIds.value.toList()
        if (personIds.isEmpty()) {
            _filteredPhotos.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isLoadingPhotos.value = true
            try {
                val photoUris = repository.getPhotosContainingAllPersons(personIds)
                _filteredPhotos.value = photoUris.map { Uri.parse(it) }
            } finally {
                _isLoadingPhotos.value = false
            }
        }
    }

    /**
     * Update person name
     */
    fun updatePersonName(personId: String, name: String) {
        viewModelScope.launch {
            repository.updatePersonName(personId, name.ifBlank { null })
        }
    }

    /**
     * Hide a person from the list
     */
    fun hidePerson(personId: String) {
        viewModelScope.launch {
            repository.hidePersons(personId)
            // Remove from selection if selected
            deselectPerson(personId)
        }
    }

    /**
     * Merge two persons (same person detected as different)
     */
    fun mergePersons(sourcePersonId: String, targetPersonId: String) {
        viewModelScope.launch {
            repository.mergePersons(sourcePersonId, targetPersonId)
        }
    }

    /**
     * Get top N persons for preview (e.g., home screen card)
     */
    fun getTopPersons(count: Int): Flow<List<PersonWithFace>> {
        return persons.map { it.take(count) }
    }

    /**
     * Check if any persons are available
     */
    val hasPersons: StateFlow<Boolean> = persons.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    /**
     * Clear all face data and rescan
     */
    fun resetAndRescan() {
        viewModelScope.launch {
            repository.clearAllData()
            startGalleryScan(forceRescan = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Don't close repository here as it's a singleton
    }
}
