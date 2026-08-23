package com.coparently.app.presentation.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.contacts.ContactDirectory
import com.coparently.app.domain.contacts.ContactGroup
import com.coparently.app.domain.repository.ChildInfoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Keeps the flow warm across a configuration change. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * The numbers worth finding in a hurry: grandparents, doctors, a friend's parents.
 *
 * Reads the children's own `emergencyContacts`, which the co-parent already shares and both
 * parents may edit — one list, widened in meaning, rather than a second model with its own
 * editor and its own answer to "where did I put grandma".
 *
 * This screen never writes. Editing goes to the child's record, where the list already lives, so
 * there is one list and one editor.
 */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    childInfoRepository: ChildInfoRepository
) : ViewModel() {

    /**
     * One group per child that has contacts, in the order the children are stored.
     *
     * `getAllChildInfo()` is the right subscription here, unlike in the child editor: this screen
     * owns showing *every* child's contacts, so the whole list is exactly what it is about.
     */
    val groups: StateFlow<List<ContactGroup>> = childInfoRepository.getAllChildInfo()
        .map(ContactDirectory::of)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
}
