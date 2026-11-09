package com.satori.data.di

import android.content.Context
import androidx.room.Room
import com.satori.data.database.AppDatabase
import com.satori.data.database.dao.CategoryDao
import com.satori.data.database.dao.FinishingJobDao
import com.satori.data.database.dao.LlmInsightDao
import com.satori.data.database.dao.MindMapDao
import com.satori.data.database.dao.SearchDao
import com.satori.data.database.dao.SegmentReadingDao
import com.satori.data.database.dao.TodoDao
import com.satori.data.database.dao.TranscriptionSegmentDao
import com.satori.data.database.dao.TranscriptionSessionDao
import com.satori.data.repository.CategoryRepositoryImpl
import com.satori.data.repository.FinishingJobRepositoryImpl
import com.satori.data.repository.MindMapRepositoryImpl
import com.satori.data.repository.TodoRepositoryImpl
import com.satori.data.repository.TranscriptionRepositoryImpl
import com.satori.domain.finish.FinishingJobRepository
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing database and repository dependencies.
 *
 * All database-related dependencies are scoped as Singleton to ensure
 * a single database instance throughout the app lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Provide the Room database instance.
     *
     * Includes proper database migrations to preserve user data across schema updates. The file is
     * [AppDatabase.DATABASE_NAME] from the first install of this app on: it starts fresh, and no
     * database is carried over from another install (the owner's decision of 2026-10-15).
     */
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
    }

    /**
     * Provide TranscriptionSessionDao from the database.
     */
    @Provides
    @Singleton
    fun provideTranscriptionSessionDao(database: AppDatabase): TranscriptionSessionDao {
        return database.transcriptionSessionDao()
    }

    /**
     * Provide TranscriptionSegmentDao from the database.
     */
    @Provides
    @Singleton
    fun provideTranscriptionSegmentDao(database: AppDatabase): TranscriptionSegmentDao {
        return database.transcriptionSegmentDao()
    }

    /**
     * Provide LlmInsightDao from the database.
     */
    @Provides
    @Singleton
    fun provideLlmInsightDao(database: AppDatabase): LlmInsightDao {
        return database.llmInsightDao()
    }

    /**
     * Provide TranscriptionRepository implementation.
     */
    @Provides
    @Singleton
    fun provideSearchDao(database: AppDatabase): SearchDao {
        return database.searchDao()
    }

    /** The readings of transcript lines (0.8.0). */
    @Provides
    @Singleton
    fun provideSegmentReadingDao(database: AppDatabase): SegmentReadingDao {
        return database.segmentReadingDao()
    }

    @Provides
    @Singleton
    fun provideTranscriptionRepository(
        sessionDao: TranscriptionSessionDao,
        segmentDao: TranscriptionSegmentDao,
        insightDao: LlmInsightDao,
        searchDao: SearchDao,
        mindMapDao: MindMapDao,
        audio: RecordingAudioRepository,
        readingDao: SegmentReadingDao
    ): TranscriptionRepository {
        return TranscriptionRepositoryImpl(
            sessionDao = sessionDao,
            segmentDao = segmentDao,
            insightDao = insightDao,
            searchDao = searchDao,
            mindMapDao = mindMapDao,
            audio = audio,
            readingDao = readingDao
        )
    }

    @Provides
    @Singleton
    fun provideTodoDao(database: AppDatabase): TodoDao {
        return database.todoDao()
    }

    @Provides
    @Singleton
    fun provideCategoryDao(database: AppDatabase): CategoryDao {
        return database.categoryDao()
    }

    @Provides
    @Singleton
    fun provideTodoRepository(todoDao: TodoDao): TodoRepository {
        return TodoRepositoryImpl(todoDao)
    }

    @Provides
    @Singleton
    fun provideCategoryRepository(categoryDao: CategoryDao): CategoryRepository {
        return CategoryRepositoryImpl(categoryDao)
    }

    @Provides
    @Singleton
    fun provideMindMapDao(database: AppDatabase): MindMapDao {
        return database.mindMapDao()
    }

    /**
     * The mind map's store. The mind-map use cases, which the presentation layer provides, read
     * and write the map through it; it stores a recording's map in one transaction of [database].
     */
    @Provides
    @Singleton
    fun provideMindMapRepository(mindMapDao: MindMapDao, database: AppDatabase): MindMapRepository {
        return MindMapRepositoryImpl(mindMapDao, database)
    }

    @Provides
    @Singleton
    fun provideFinishingJobDao(database: AppDatabase): FinishingJobDao {
        return database.finishingJobDao()
    }

    /** The queue of recordings to be finished, as stored; the finisher works on it. */
    @Provides
    @Singleton
    fun provideFinishingJobRepository(finishingJobDao: FinishingJobDao): FinishingJobRepository {
        return FinishingJobRepositoryImpl(finishingJobDao)
    }
}
