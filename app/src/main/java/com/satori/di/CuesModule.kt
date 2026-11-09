package com.satori.di

import com.satori.presentation.main.NotesReadyCue
import com.satori.service.FirstRunCues
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The finisher of recordings tells the owner of ready notes through the first run's cues ([FirstRunCues]). */
@Module
@InstallIn(SingletonComponent::class)
abstract class CuesModule {

    @Binds
    abstract fun bindNotesReadyCue(cues: FirstRunCues): NotesReadyCue
}
