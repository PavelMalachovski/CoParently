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
import javax.inject.Inject

/**
 * ViewModel for calendar screen.
 * Handles custody schedule data.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao
) : ViewModel() {

    private val _custodySchedules = MutableStateFlow<List<CustodyScheduleEntity>>(emptyList())
    val custodySchedules: StateFlow<List<CustodyScheduleEntity>> = _custodySchedules.asStateFlow()

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
}

