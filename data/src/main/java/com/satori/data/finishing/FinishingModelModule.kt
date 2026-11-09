package com.satori.data.finishing

import com.satori.domain.repository.FinishingModelRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt binding for the choice of the model for the work after Stop. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FinishingModelModule {

    @Binds
    abstract fun bindFinishingModelRepository(
        impl: DataStoreFinishingModelRepository
    ): FinishingModelRepository
}
