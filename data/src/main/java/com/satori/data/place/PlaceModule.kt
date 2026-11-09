package com.satori.data.place

import com.satori.domain.place.Gazetteer
import com.satori.domain.place.LocationSource
import com.satori.domain.place.PhoneCountry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bindings for the place of a recording: the phone's position, its country, and the list of places. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaceModule {

    @Binds
    abstract fun bindLocationSource(impl: AndroidLocationSource): LocationSource

    @Binds
    abstract fun bindPhoneCountry(impl: AndroidPhoneCountry): PhoneCountry

    @Binds
    abstract fun bindGazetteer(impl: AssetGazetteer): Gazetteer
}
