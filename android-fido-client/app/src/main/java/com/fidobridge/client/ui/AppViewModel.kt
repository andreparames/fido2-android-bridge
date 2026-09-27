package com.fidobridge.client.ui

import androidx.lifecycle.ViewModel
import com.fidobridge.client.pairing.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    pairingRepository: PairingRepository
) : ViewModel() {

    val isPaired: Boolean = pairingRepository.isPaired
}
