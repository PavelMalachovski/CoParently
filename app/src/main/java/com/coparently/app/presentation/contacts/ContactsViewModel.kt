package com.coparently.app.presentation.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coparently.app.domain.contacts.ContactDirectory
import com.coparently.app.domain.contacts.ContactGroup
import com.coparently.app.domain.repository.ChildInfoRepository
import com.coparently.app.domain.repository.PetRepository
import com.coparently.app.presentation.common.Loadable
import com.coparently.app.presentation.common.stateInLoadable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    childInfoRepository: ChildInfoRepository,
    petRepository: PetRepository
) : ViewModel() {

    /**
     * One group per child that has contacts, in the order the children are stored.
     *
     * `getAllChildInfo()` is the right subscription here, unlike in the child editor: this screen
     * owns showing *every* child's contacts, so the whole list is exactly what it is about.
     */
    val groups: StateFlow<Loadable<List<ContactGroup>>> = combine(
        childInfoRepository.getAllChildInfo(),
        petRepository.getAllPets()
    ) { children, pets -> ContactDirectory.of(children, pets) }
        // This is the emergency surface. Telling a parent in a hurry that there are no contacts,
        // a frame before showing them, is the worst instance of the defect Loadable exists for.
        .stateInLoadable(viewModelScope, STOP_TIMEOUT_MS)
}
