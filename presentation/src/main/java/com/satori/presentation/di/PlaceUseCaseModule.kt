package com.satori.presentation.di

import com.satori.domain.place.Gazetteer
import com.satori.domain.usecase.place.DescribeRecordingPlaceUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

/** Use cases of the place of a recording on its details page. */
@Module
@InstallIn(ViewModelComponent::class)
object PlaceUseCaseModule {

    @Provides
    @ViewModelScoped
    fun provideDescribeRecordingPlaceUseCase(gazetteer: Gazetteer): DescribeRecordingPlaceUseCase {
        return DescribeRecordingPlaceUseCase(gazetteer)
    }
}
