package com.coparently.app.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.data.local.dao.CustodyScheduleDao
import com.coparently.app.data.local.entity.CustodyScheduleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * ViewModel for calendar screen.
 * Handles custody schedule data and view mode.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao
) : ViewModel() {

    private val _custodySchedules = MutableStateFlow<List<CustodyScheduleEntity>>(emptyList())
    val custodySchedules: StateFlow<List<CustodyScheduleEntity>> = _custodySchedules.asStateFlow()

    private val _viewMode = MutableStateFlow<CalendarViewMode>(CalendarViewMode.MONTH)
    val viewMode: StateFlow<CalendarViewMode> = _viewMode.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate>(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    init {
        loadCustodySchedules()
    }

    /**
     * Loads all active custody schedules.
     */
    fun loadCustodySchedules() {
        viewModelScope.launch {
            custodyScheduleDao.getAllActiveSchedules().collect { schedules ->
                _custodySchedules.value = schedules
            }
        }
    }

    /**
     * Sets the calendar view mode.
     */
    fun setViewMode(mode: CalendarViewMode) {
        _viewMode.value = mode
    }

    /**
     * Sets the selected date.
     */
    fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = date
    }
}

