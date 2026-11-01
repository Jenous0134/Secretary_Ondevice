/*
 * Copyright 2026 Jenous
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.google.ai.edge.gallery.secretary.data

enum class ProjectStatus(val label: String) {
  PLANNING("기획 중"),
  WRITING("작성 중"),
  SUBMITTED("제출"),
  EVALUATING("평가 중"),
  SELECTED("선정"),
  NOT_SELECTED("미선정"),
  IN_PROGRESS("수행 중"),
  COMPLETED("완료"),
}

enum class WorkStatus(val label: String) {
  TODO("예정"), IN_PROGRESS("진행 중"), DONE("완료"), HOLD("보류")
}

enum class Priority(val label: String) {
  LOW("낮음"), NORMAL("보통"), HIGH("높음"), URGENT("긴급")
}

data class Project(
  val id: Long = 0,
  val name: String,
  val agency: String = "",
  val startAt: Long? = null,
  val endAt: Long? = null,
  val status: ProjectStatus = ProjectStatus.PLANNING,
  val manager: String = "",
  val participants: String = "",
  val memo: String = "",
  val progress: Int = 0,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

data class WorkItem(
  val id: Long = 0,
  val projectId: Long?,
  val title: String,
  val description: String = "",
  val assignee: String = "",
  val dueAt: Long? = null,
  val priority: Priority = Priority.NORMAL,
  val status: WorkStatus = WorkStatus.TODO,
  val progress: Int = 0,
  val deliverable: String = "",
  val createdAt: Long = System.currentTimeMillis(),
)

data class ScheduleItem(
  val id: Long = 0,
  val projectId: Long?,
  val title: String,
  val description: String = "",
  val eventAt: Long,
  val reminderDays: String = "7,3,1",
  val createdAt: Long = System.currentTimeMillis(),
)

data class DocumentItem(
  val id: Long = 0,
  val projectId: Long?,
  val title: String,
  val category: String,
  val version: String,
  val mimeType: String,
  val localPath: String,
  val originalName: String,
  val extractedText: String = "",
  val indexedAt: Long? = null,
  val createdAt: Long = System.currentTimeMillis(),
)

data class KpiItem(
  val id: Long = 0,
  val projectId: Long,
  val name: String,
  val target: String,
  val current: String = "",
  val unit: String = "",
)

data class ChecklistItem(
  val id: Long = 0,
  val workId: Long,
  val title: String,
  val checked: Boolean = false,
)

data class Evidence(
  val documentId: Long,
  val documentTitle: String,
  val projectName: String,
  val chunkIndex: Int,
  val text: String,
  val score: Int,
)

data class ChatMessage(
  val id: Long = 0,
  val role: String,
  val content: String,
  val evidence: String = "",
  val createdAt: Long = System.currentTimeMillis(),
)

data class SecretarySnapshot(
  val projects: List<Project> = emptyList(),
  val works: List<WorkItem> = emptyList(),
  val schedules: List<ScheduleItem> = emptyList(),
  val documents: List<DocumentItem> = emptyList(),
  val kpis: List<KpiItem> = emptyList(),
  val messages: List<ChatMessage> = emptyList(),
)
