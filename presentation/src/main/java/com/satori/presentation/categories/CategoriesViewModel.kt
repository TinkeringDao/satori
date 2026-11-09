package com.satori.presentation.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.domain.category.CategoryRules
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the Categories screen: the list, and adding, renaming and deleting categories.
 * The rules for a name live in [CategoryRules]; the screen shows their verdict while the name
 * is typed, and every change is checked again here against the stored list.
 */
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    /** The categories in their order, or null while they load. */
    val categories: StateFlow<List<RecordingCategory>?> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Adds a category at the end of the list. A name [CategoryRules.checkName] refuses is ignored. */
    fun add(name: String, mode: RecordingMode) {
        viewModelScope.launch {
            val existing = categoryRepository.observeAll().first()
            val check = CategoryRules.checkName(name, existing.map { it.name })
            if (!check.isValid) return@launch
            categoryRepository.upsert(
                CategoryRules.create(
                    name = check.name,
                    mode = mode,
                    existing = existing,
                    id = UUID.randomUUID().toString(),
                    now = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Renames the category [id] and sets its type; its place in the list and its recordings stay.
     * A name [CategoryRules.checkName] refuses, or an [id] that is gone, is ignored.
     */
    fun update(id: String, name: String, mode: RecordingMode) {
        viewModelScope.launch {
            val existing = categoryRepository.observeAll().first()
            val category = existing.firstOrNull { it.id == id } ?: return@launch
            val check = CategoryRules.checkName(name, existing.filter { it.id != id }.map { it.name })
            if (!check.isValid) return@launch
            categoryRepository.upsert(category.copy(name = check.name, mode = mode))
        }
    }

    /**
     * Deletes the category [id]. Its recordings keep their notes and to-dos and lose only the
     * label. The repository keeps the last category, so the list is never empty; the screen does
     * not offer to delete it.
     */
    fun delete(id: String) {
        viewModelScope.launch { categoryRepository.delete(id) }
    }

    private companion object {
        /** How long the list keeps loading after the screen is gone, to survive a fold. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
