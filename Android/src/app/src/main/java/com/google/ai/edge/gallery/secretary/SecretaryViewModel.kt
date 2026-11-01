/* Copyright 2026 Jenous. Licensed under the Apache License, Version 2.0. */
package com.google.ai.edge.gallery.secretary

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.secretary.data.*
import com.google.ai.edge.gallery.secretary.documents.DocumentImporter
import com.google.ai.edge.gallery.secretary.notifications.SecretaryReminderReceiver
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class SecretaryUiState(
  val data: SecretarySnapshot = SecretarySnapshot(),
  val busy: Boolean = false,
  val error: String? = null,
  val selectedProjectId: Long? = null,
  val assistantProjectId: Long? = null,
)

class SecretaryViewModel(application: Application) : AndroidViewModel(application) {
  private val database = SecretaryDatabase(application)
  private val importer = DocumentImporter(application)
  private val _uiState = MutableStateFlow(SecretaryUiState())
  val uiState = _uiState.asStateFlow()

  init { refresh() }

  fun refresh() = viewModelScope.launch { _uiState.update { it.copy(data = withContext(Dispatchers.IO) { database.snapshot() }, busy = false) } }
  fun selectProject(id: Long?) = _uiState.update { it.copy(selectedProjectId = id) }
  fun setAssistantProject(id: Long?) = _uiState.update { it.copy(assistantProjectId = id) }
  fun clearError() = _uiState.update { it.copy(error = null) }

  fun saveProject(item: Project) = mutate { database.saveProject(item) }
  fun saveWork(item: WorkItem) = mutate { database.saveWork(item) }
  fun saveKpi(item: KpiItem) = mutate { database.saveKpi(item) }
  fun saveChecklist(item: ChecklistItem, onSaved: (() -> Unit)? = null) = viewModelScope.launch {
    withContext(Dispatchers.IO) { database.saveChecklist(item) }
    onSaved?.invoke()
  }
  fun toggleChecklist(id: Long, checked: Boolean) = mutate { database.toggleChecklist(id, checked) }
  fun checklist(workId: Long, callback: (List<ChecklistItem>) -> Unit) = viewModelScope.launch { callback(withContext(Dispatchers.IO) { database.checklist(workId) }) }
  fun delete(table: String, id: Long) = mutate { database.delete(table, id) }

  fun saveSchedule(item: ScheduleItem) = mutate {
    val id = database.saveSchedule(item)
    SecretaryReminderReceiver.schedule(getApplication(), item.copy(id = id))
  }

  fun importDocument(uri: Uri, projectId: Long?, category: String, version: String) = mutate {
    val imported = importer.import(uri)
    database.saveDocument(DocumentItem(projectId = projectId, title = imported.displayName.substringBeforeLast('.'), category = category, version = version, mimeType = imported.mimeType, localPath = imported.file.absolutePath, originalName = imported.displayName, extractedText = imported.text, indexedAt = if (imported.text.isBlank()) null else System.currentTimeMillis()))
  }

  fun ask(question: String, model: Model? = null) {
    if (question.isBlank()) return
    viewModelScope.launch {
      _uiState.update { it.copy(busy = true) }
      withContext(Dispatchers.IO) {
        database.saveChat(ChatMessage(role = "user", content = question))
        val evidence = database.searchEvidence(question, _uiState.value.assistantProjectId)
        val fallback = buildAnswer(question, evidence, database.snapshot())
        val answer = if (model != null && evidence.isNotEmpty()) runLocalLlm(model, question, evidence).getOrNull()?.takeIf { it.isNotBlank() } ?: fallback else fallback
        database.saveChat(ChatMessage(role = "assistant", content = answer, evidence = evidence.joinToString("\n") { "${it.documentTitle}#${it.chunkIndex + 1}" }))
      }
      refresh()
    }
  }

  private suspend fun runLocalLlm(model: Model, question: String, evidence: List<Evidence>): Result<String> = runCatching {
    val context = getApplication<Application>()
    val system = Contents.of("당신은 개인 업무 비서 Sec_Ond입니다. 제공된 근거만 사용하고, 모르면 모른다고 답하세요. 날짜·금액·요구사항 뒤에는 [문서명#문단] 형태의 근거를 표시하세요. 한국어로 간결하게 답하세요.")
    if (model.instance == null) suspendCancellableCoroutine { continuation ->
      LlmChatModelHelper.initialize(context, model, "secretary_assistant", false, false, { error ->
        if (continuation.isActive) { if (error.isBlank()) continuation.resume(Unit) else continuation.cancel(IllegalStateException(error)) }
      }, systemInstruction = system, coroutineScope = viewModelScope)
    }
    val prompt = buildString {
      append("질문: $question\n\n검색 근거:\n")
      evidence.forEach { append("[${it.documentTitle}#${it.chunkIndex + 1}] ${it.text}\n\n") }
    }
    suspendCancellableCoroutine { continuation ->
      val output = StringBuilder()
      LlmChatModelHelper.runInference(model, prompt, { partial, done, _ ->
        output.append(partial)
        if (done && continuation.isActive) continuation.resume(output.toString())
      }, cleanUpListener = {}, onError = { if (continuation.isActive) continuation.cancel(IllegalStateException(it)) }, coroutineScope = viewModelScope)
      continuation.invokeOnCancellation { LlmChatModelHelper.stopResponse(model) }
    }
  }

  fun backup(uri: Uri) = viewModelScope.launch {
    runCatching { withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri)?.use { database.writeBackup(it) } ?: error("백업 파일을 열 수 없습니다.") } }
      .onFailure { failure -> _uiState.update { it.copy(error = failure.message) } }
  }

  fun restore(uri: Uri) = viewModelScope.launch {
    _uiState.update { it.copy(busy = true, error = null) }
    runCatching { withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)?.use { database.restoreBackup(it) } ?: error("백업 파일을 열 수 없습니다.") } }
      .onSuccess { refresh() }.onFailure { failure -> _uiState.update { it.copy(busy = false, error = failure.message) } }
  }

  private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
    _uiState.update { it.copy(busy = true, error = null) }
    runCatching { withContext(Dispatchers.IO) { block() } }
      .onSuccess { refresh() }.onFailure { failure -> _uiState.update { it.copy(busy = false, error = failure.message ?: "처리 중 오류가 발생했습니다.") } }
  }

  private fun buildAnswer(question: String, evidence: List<Evidence>, snapshot: SecretarySnapshot): String {
    val q = question.lowercase()
    if (q.contains("마감") || q.contains("일정") || q.contains("이번 주") || q.contains("오늘")) {
      val now = System.currentTimeMillis(); val end = now + if (q.contains("오늘")) 86_400_000L else 7 * 86_400_000L
      val upcoming = snapshot.schedules.filter { it.eventAt in now..end }.take(8)
      if (upcoming.isNotEmpty()) return "가까운 일정입니다.\n" + upcoming.joinToString("\n") { "• ${it.title} (${formatDate(it.eventAt)})" }
    }
    if (q.contains("미완료") || q.contains("할 일") || q.contains("업무")) {
      val works = snapshot.works.filter { it.status != WorkStatus.DONE }.take(10)
      if (works.isNotEmpty()) return "미완료 업무입니다.\n" + works.joinToString("\n") { "• ${it.title}${it.dueAt?.let { due -> " · ${formatDate(due)}" } ?: ""} [${it.status.label}]" }
    }
    if (evidence.isEmpty()) return "등록된 자료에서 질문과 직접 관련된 근거를 찾지 못했습니다. 문서를 등록하거나 사업 범위를 변경한 뒤 다시 질문해 주세요."
    return buildString {
      append("등록된 문서에서 찾은 관련 내용입니다.\n\n")
      evidence.take(3).forEachIndexed { index, item -> append("${index + 1}. ${item.text.take(500)}\n\n") }
      append("근거\n"); evidence.forEach { append("• ${it.projectName} / ${it.documentTitle} / 문단 ${it.chunkIndex + 1}\n") }
      append("\n중요한 일정·금액·요구사항은 원문을 확인한 후 저장하세요.")
    }
  }

  private fun formatDate(epoch: Long) = java.time.Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate().toString()
  companion object { fun dateToEpoch(text: String): Long? = runCatching { LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() }
}
