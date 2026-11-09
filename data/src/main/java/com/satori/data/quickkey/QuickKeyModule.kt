package com.satori.data.quickkey

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt binding for the quick key settings. */
@Module
@InstallIn(SingletonComponent::class)
abstract class QuickKeyModule {

    @Binds
    abstract fun bindQuickKeySettingsRepository(
        impl: DataStoreQuickKeySettingsRepository
    ): QuickKeySettingsRepository
}
