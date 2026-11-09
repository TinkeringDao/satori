package com.satori.domain.firstrun

import kotlinx.coroutines.flow.StateFlow

/** The default network as Android reports it, as it changes ([NetworkFacts]). */
interface NetworkWatch {

    /** The facts of the default network now; [NetworkFacts.NONE] without one. */
    val facts: StateFlow<NetworkFacts>
}
