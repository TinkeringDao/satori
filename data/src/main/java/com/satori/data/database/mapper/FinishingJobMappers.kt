package com.satori.data.database.mapper

import com.satori.data.database.entity.FinishingJobEntity
import com.satori.domain.finish.FinishingJob
import com.satori.domain.finish.FinishingJobState
import com.satori.domain.usecase.finish.FinishStep

/**
 * A stored finishing job in the domain's terms. A state this version doesn't know (one a later
 * version wrote before it was taken back) reads as [FinishingJobState.CAPTURING], which nothing
 * starts or shows, and a step it doesn't know as none.
 */
fun FinishingJobEntity.toDomain(): FinishingJob = FinishingJob(
    sessionId = sessionId,
    state = FinishingJobState.entries.firstOrNull { it.name == state } ?: FinishingJobState.CAPTURING,
    nextStep = nextStep?.let { name -> FinishStep.entries.firstOrNull { it.name == name } },
    attempts = attempts,
    owner = owner,
    recognizerFailed = recognizerFailed,
    recovered = recovered,
    waitsForRecording = waitsForRecording,
    queuedAt = queuedAt,
    createdAt = createdAt,
    redo = redo
)
