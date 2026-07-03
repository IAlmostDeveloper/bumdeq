package ru.ialmostdeveloper.bumdeq.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ru.ialmostdeveloper.bumdeq.criticalforce.CriticalForceResult
import ru.ialmostdeveloper.bumdeq.data.MeasurementsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * История замеров Critical Force для вкладки «Результаты». Тонкая обёртка над
 * [MeasurementsRepository]: отдаёт сохранённые замеры потоком и проксирует сохранение/удаление.
 */
class MeasurementsViewModel(
    private val repository: MeasurementsRepository,
) : ViewModel() {

    /** Сохранённые замеры (старые раньше); UI обычно показывает в обратном порядке. */
    val results: StateFlow<List<CriticalForceResult>> = repository.results
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun save(result: CriticalForceResult) {
        viewModelScope.launch { repository.save(result) }
    }

    fun delete(id: UUID) {
        viewModelScope.launch { repository.delete(id) }
    }

    /** JSON-массив текущих замеров для экспорта в файл. */
    fun exportJson(): String = repository.toJsonArray(results.value)

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MeasurementsViewModel(MeasurementsRepository(context.applicationContext)) as T
            }
    }
}
