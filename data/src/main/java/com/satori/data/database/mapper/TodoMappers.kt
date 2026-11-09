package com.satori.data.database.mapper

import com.satori.data.database.entity.CategoryEntity
import com.satori.data.database.entity.TodoEntity
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoCertainty
import com.satori.domain.model.TodoKind
import com.satori.domain.model.TodoStatus

/**
 * Mappers between the todo and category entities and their domain models.
 *
 * Enums are stored by name. A name this version doesn't know falls back to the domain default,
 * like the session mappers do.
 */

// ========== Todo Mappers ==========

fun TodoEntity.toDomain(): Todo {
    return Todo(
        id = id,
        sessionId = sessionId,
        text = text,
        kind = kind.toEnumOr(TodoKind.TASK),
        status = status.toEnumOr(TodoStatus.OPEN),
        certainty = certainty.toEnumOr(TodoCertainty.CONFIRMED),
        capture = capture.toEnumOr(TodoCapture.EXPLICIT),
        dueAt = dueAt,
        dueHasTime = dueHasTime,
        timePhrase = timePhrase,
        remindAt = remindAt,
        reminderShownAt = reminderShownAt,
        sourceSegmentIds = parseStringList(sourceSegmentIds),
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt
    )
}

fun Todo.toEntity(): TodoEntity {
    return TodoEntity(
        id = id,
        sessionId = sessionId,
        text = text,
        kind = kind.name,
        status = status.name,
        certainty = certainty.name,
        capture = capture.name,
        dueAt = dueAt,
        dueHasTime = dueHasTime,
        timePhrase = timePhrase,
        remindAt = remindAt,
        reminderShownAt = reminderShownAt,
        sourceSegmentIds = serializeStringList(sourceSegmentIds),
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt
    )
}

// ========== Category Mappers ==========

fun CategoryEntity.toDomain(): RecordingCategory {
    return RecordingCategory(
        id = id,
        name = name,
        mode = mode.toEnumOr(RecordingMode.SIMPLE_LISTENING),
        sortOrder = sortOrder,
        createdAt = createdAt
    )
}

fun RecordingCategory.toEntity(): CategoryEntity {
    return CategoryEntity(
        id = id,
        name = name,
        mode = mode.name,
        sortOrder = sortOrder,
        createdAt = createdAt
    )
}

private inline fun <reified E : Enum<E>> String.toEnumOr(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default
