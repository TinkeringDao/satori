package com.satori.presentation.di

import com.satori.presentation.sessions.AndroidHomePhone
import com.satori.presentation.sessions.HomePhone
import com.satori.presentation.sessions.NudgeLedgerStore
import com.satori.presentation.sessions.PrefsNudgeLedgerStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Home's status strip: the one nudge ledger of the app, and the phone its offers read. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HomeStripModule {

    @Binds
    abstract fun nudgeLedger(store: PrefsNudgeLedgerStore): NudgeLedgerStore

    @Binds
    abstract fun homePhone(phone: AndroidHomePhone): HomePhone
}
