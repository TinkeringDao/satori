package com.satori.presentation.di

import com.satori.data.firstrun.FirstRunModelDownloads
import com.satori.data.firstrun.FirstRunModelFiles
import com.satori.data.firstrun.NetworkMonitor
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunDownloads
import com.satori.domain.firstrun.FirstRunFiles
import com.satori.domain.firstrun.NetworkWatch
import com.satori.domain.reading.ReaderAvailability
import com.satori.presentation.firstrun.FirstRunCoordinatorImpl
import com.satori.presentation.firstrun.FirstRunReaderAvailability
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The first run's coordinator, one for the whole app: the setup screens, the recorder, home's
 * status strip and the finisher read the same one. It works through the model files, the
 * downloads and the network as the data module has them.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FirstRunModule {

    @Binds
    abstract fun firstRunCoordinator(coordinator: FirstRunCoordinatorImpl): FirstRunCoordinator

    @Binds
    abstract fun firstRunFiles(files: FirstRunModelFiles): FirstRunFiles

    @Binds
    abstract fun firstRunDownloads(downloads: FirstRunModelDownloads): FirstRunDownloads

    @Binds
    abstract fun networkWatch(monitor: NetworkMonitor): NetworkWatch

    /** The reader's availability for the finisher's wait (design D7), from the first run's READER part (R7). */
    @Binds
    abstract fun readerAvailability(availability: FirstRunReaderAvailability): ReaderAvailability
}
