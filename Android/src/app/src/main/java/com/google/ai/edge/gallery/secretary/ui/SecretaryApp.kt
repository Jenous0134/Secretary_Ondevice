/* Copyright 2026 Jenous. Licensed under the Apache License, Version 2.0. */
package com.google.ai.edge.gallery.secretary.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.secretary.SecretaryViewModel
import com.google.ai.edge.gallery.secretary.data.*
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private enum class MainTab(val label: String) { HOME("홈"), PROJECTS("사업"), SCHEDULE("일정"), WORK("업무"), ASSISTANT("AI 비서") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretaryApp(modelManagerViewModel: ModelManagerViewModel, vm: SecretaryViewModel = viewModel()) {
  val state by vm.uiState.collectAsStateWithLifecycle()
  var tab by remember { mutableStateOf(MainTab.HOME) }
  var settings by remember { mutableStateOf(false) }
  var modelManager by remember { mutableStateOf(false) }
  var assistantModel by remember { mutableStateOf<com.google.ai.edge.gallery.data.Model?>(null) }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }

  if (modelManager) {
    GlobalModelManager(viewModel = modelManagerViewModel, navigateUp = { modelManager = false }, onModelSelected = { _, model -> assistantModel = model; modelManagerViewModel.selectModel(model); modelManager = false }, onBenchmarkClicked = {})
    return
  }
  if (settings) {
    SettingsScreen(onBack = { settings = false }, onModels = { modelManager = true }, onBackup = vm::backup, onRestore = vm::restore)
    return
  }

  Scaffold(
    topBar = { TopAppBar(title = { Column { Text("Secretary Ondevice", fontWeight = FontWeight.Bold); Text("Sec_Ond · 오프라인 업무 비서", style = MaterialTheme.typography.labelSmall) } }, actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, "설정") } }) },
    bottomBar = { NavigationBar { MainTab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(tabIcon(item), item.label) }, label = { Text(item.label) }) } } },
    floatingActionButton = { when (tab) { MainTab.PROJECTS -> AddProjectButton(vm); MainTab.SCHEDULE -> AddScheduleButton(vm, state.data.projects); MainTab.WORK -> AddWorkButton(vm, state.data.projects); else -> Unit } },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (tab) {
        MainTab.HOME -> HomeScreen(state.data, onTab = { tab = it })
        MainTab.PROJECTS -> ProjectsScreen(state.data, state.selectedProjectId, vm)
        MainTab.SCHEDULE -> ScheduleScreen(state.data, vm)
        MainTab.WORK -> WorkScreen(state.data, vm)
        MainTab.ASSISTANT -> AssistantScreen(state.data, state.assistantProjectId, state.busy, assistantModel, vm)
      }
      if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
      state.error?.let { error -> AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("확인") } }, title = { Text("처리할 수 없습니다") }, text = { Text(error) }) }
    }
  }
}

@Composable
private fun HomeScreen(data: SecretarySnapshot, onTab: (MainTab) -> Unit) {
  val now = System.currentTimeMillis(); val week = now + 7 * 86_400_000L
  val upcoming = data.schedules.filter { it.eventAt in now..week }.take(5)
  val overdue = data.works.filter { it.status != WorkStatus.DONE && it.dueAt != null && it.dueAt < now }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { Text("오늘의 업무", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("사업 자료와 마감을 한곳에서 관리하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("진행 사업", data.projects.count { it.status !in listOf(ProjectStatus.COMPLETED, ProjectStatus.NOT_SELECTED) }, Modifier.weight(1f)); StatCard("미완료", data.works.count { it.status != WorkStatus.DONE }, Modifier.weight(1f)); StatCard("기한 초과", overdue.size, Modifier.weight(1f)) } }
    item { SectionHeader("7일 안의 일정", "전체 보기") { onTab(MainTab.SCHEDULE) } }
    if (upcoming.isEmpty()) item { EmptyCard("예정된 일정이 없습니다.") } else items(upcoming) { ScheduleRow(it, data.projectName(it.projectId)) }
    item { SectionHeader("진행 중인 업무", "전체 보기") { onTab(MainTab.WORK) } }
    val active = data.works.filter { it.status != WorkStatus.DONE }.take(5)
    if (active.isEmpty()) item { EmptyCard("진행 중인 업무가 없습니다.") } else items(active) { WorkCard(it, data.projectName(it.projectId), onStatus = {}) }
    item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().clickable { onTab(MainTab.ASSISTANT) }) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Rounded.Chat, null); Spacer(Modifier.width(12.dp)); Column { Text("AI 비서에게 질문하기", fontWeight = FontWeight.Bold); Text("등록된 문서와 업무에서 근거를 찾아 답합니다.") } } } }
  }
}

@Composable private fun StatCard(label: String, count: Int, modifier: Modifier) { Card(modifier) { Column(Modifier.padding(14.dp)) { Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelMedium) } } }
@Composable private fun EmptyCard(text: String) { OutlinedCard(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun SectionHeader(title: String, action: String, onClick: () -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = onClick) { Text(action) } } }

@Composable
private fun ProjectsScreen(data: SecretarySnapshot, selectedId: Long?, vm: SecretaryViewModel) {
  if (selectedId != null) {
    val project = data.projects.firstOrNull { it.id == selectedId }
    if (project != null) ProjectDetail(project, data, vm) else vm.selectProject(null)
    return
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item { Text("사업", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("기획부터 완료까지 전체 과정을 추적합니다.") }
    if (data.projects.isEmpty()) item { EmptyCard("오른쪽 아래 + 버튼으로 첫 사업을 등록하세요.") }
    items(data.projects, key = { it.id }) { p -> Card(Modifier.fillMaxWidth().clickable { vm.selectProject(p.id) }) { Column(Modifier.padding(18.dp)) { Row { Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); AssistChip(onClick = {}, label = { Text(p.status.label) }) }; Text(p.agency.ifBlank { "지원기관 미등록" }, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)); LinearProgressIndicator({ p.progress / 100f }, Modifier.fillMaxWidth()); Text("진행률 ${p.progress}% · ${date(p.startAt)} ~ ${date(p.endAt)}", style = MaterialTheme.typography.labelMedium) } } }
  }
}

@Composable
private fun ProjectDetail(project: Project, data: SecretarySnapshot, vm: SecretaryViewModel) {
  var edit by remember { mutableStateOf(false) }; var addKpi by remember { mutableStateOf(false) }
  var importMeta by remember { mutableStateOf(false) }; var category by remember { mutableStateOf("사업계획서") }; var version by remember { mutableStateOf("초안") }
  var selectedWork by remember { mutableStateOf<WorkItem?>(null) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { vm.importDocument(uri, project.id, category, version); importMeta = false } }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { vm.selectProject(null) }) { Icon(Icons.Rounded.ArrowBack, "뒤로") }; Column(Modifier.weight(1f)) { Text(project.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${project.agency} · ${project.status.label}") }; IconButton(onClick = { edit = true }) { Icon(Icons.Rounded.Edit, "수정") } } }
    item { InfoCard("사업 정보", listOf("기간" to "${date(project.startAt)} ~ ${date(project.endAt)}", "담당자" to project.manager.ifBlank { "미등록" }, "참여자" to project.participants.ifBlank { "미등록" }, "메모" to project.memo.ifBlank { "없음" })) }
    item { SectionHeader("관련 문서", "파일 추가") { importMeta = true } }
    val docs = data.documents.filter { it.projectId == project.id }
    if (docs.isEmpty()) item { EmptyCard("PDF, DOCX, HWPX, HWP 파일을 등록할 수 있습니다.") } else items(docs) { d -> ListItem(headlineContent = { Text(d.title, fontWeight = FontWeight.SemiBold) }, supportingContent = { Text("${d.category} · ${d.version} · ${if (d.indexedAt != null) "AI 검색 가능" else "원본 보관"}") }, leadingContent = { Icon(Icons.Rounded.Description, null) }) }
    item { SectionHeader("KPI 및 목표", "추가") { addKpi = true } }
    items(data.kpis.filter { it.projectId == project.id }) { k -> ListItem(headlineContent = { Text(k.name) }, supportingContent = { Text("현재 ${k.current.ifBlank { "-" }} / 목표 ${k.target} ${k.unit}") }, leadingContent = { Icon(Icons.Rounded.TrackChanges, null) }) }
    item { Text("업무", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    items(data.works.filter { it.projectId == project.id }) { work -> WorkCard(work, project.name, onOpen = { selectedWork = work }) { status -> vm.saveWork(work.copy(status = status, progress = if (status == WorkStatus.DONE) 100 else work.progress)) } }
  }
  if (edit) ProjectDialog(project, onDismiss = { edit = false }) { vm.saveProject(it); edit = false }
  if (addKpi) KpiDialog(project.id, { addKpi = false }) { vm.saveKpi(it); addKpi = false }
  if (importMeta) AlertDialog(onDismissRequest = { importMeta = false }, title = { Text("문서 정보") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SimpleField("분류", category) { category = it }; SimpleField("버전", version) { version = it }; Text("PDF·DOCX·HWPX·TXT는 로컬에서 텍스트를 추출합니다. HWP는 원본 보관을 지원합니다.", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button(onClick = { picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/*", "text/*")) }) { Text("파일 선택") } }, dismissButton = { TextButton(onClick = { importMeta = false }) { Text("취소") } })
  selectedWork?.let { WorkDetailDialog(it, vm) { selectedWork = null } }
}

@Composable private fun InfoCard(title: String, rows: List<Pair<String, String>>) { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.Bold); rows.forEach { (key, value) -> Row { Text(key, Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, Modifier.weight(1f)) } } } } }

@Composable
private fun ScheduleScreen(data: SecretarySnapshot, vm: SecretaryViewModel) {
  var range by remember { mutableIntStateOf(30) }; val now = System.currentTimeMillis(); val end = now + range * 86_400_000L
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item { Text("일정", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1 to "오늘", 7 to "이번 주", 30 to "이번 달", 3650 to "전체").forEach { (days, label) -> FilterChip(selected = range == days, onClick = { range = days }, label = { Text(label) }) } } }
    val filtered = data.schedules.filter { it.eventAt in (now - 86_400_000L)..end }
    if (filtered.isEmpty()) item { EmptyCard("선택한 기간에 일정이 없습니다.") } else items(filtered) { ScheduleRow(it, data.projectName(it.projectId)) }
  }
}

@Composable private fun ScheduleRow(item: ScheduleItem, project: String) { OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(dDay(item.eventAt), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(date(item.eventAt), style = MaterialTheme.typography.labelSmall) }; Spacer(Modifier.width(16.dp)); Column { Text(item.title, fontWeight = FontWeight.Bold); Text(project); if (item.description.isNotBlank()) Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }

@Composable
private fun WorkScreen(data: SecretarySnapshot, vm: SecretaryViewModel) {
  var filter by remember { mutableStateOf<WorkStatus?>(null) }
  var selectedWork by remember { mutableStateOf<WorkItem?>(null) }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item { Text("업무", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(filter == null, { filter = null }, { Text("전체") }); WorkStatus.entries.forEach { s -> FilterChip(filter == s, { filter = s }, { Text(s.label) }) } } }
    val works = data.works.filter { filter == null || it.status == filter }
    if (works.isEmpty()) item { EmptyCard("등록된 업무가 없습니다.") } else items(works) { item -> WorkCard(item, data.projectName(item.projectId), onOpen = { selectedWork = item }) { status -> vm.saveWork(item.copy(status = status, progress = if (status == WorkStatus.DONE) 100 else item.progress)) } }
  }
  selectedWork?.let { WorkDetailDialog(it, vm) { selectedWork = null } }
}

@Composable
private fun WorkCard(item: WorkItem, project: String, onOpen: () -> Unit = {}, onStatus: (WorkStatus) -> Unit) { OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(item.status == WorkStatus.DONE, { onStatus(if (it) WorkStatus.DONE else WorkStatus.IN_PROGRESS) }); Column(Modifier.weight(1f)) { Text(item.title, fontWeight = FontWeight.Bold); Text("$project · ${item.assignee.ifBlank { "담당자 미지정" }}", style = MaterialTheme.typography.bodySmall) }; SuggestionChip(onClick = {}, label = { Text(item.priority.label) }) }; if (item.dueAt != null) Text("마감 ${date(item.dueAt)} · ${dDay(item.dueAt)}", color = if (item.dueAt < System.currentTimeMillis() && item.status != WorkStatus.DONE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant); LinearProgressIndicator({ item.progress / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp)) } } }

@Composable
private fun WorkDetailDialog(work: WorkItem, vm: SecretaryViewModel, onDismiss: () -> Unit) {
  var items by remember(work.id) { mutableStateOf<List<ChecklistItem>>(emptyList()) }
  var newItem by remember { mutableStateOf("") }
  fun reload() { vm.checklist(work.id) { items = it } }
  LaunchedEffect(work.id) { reload() }
  AlertDialog(onDismissRequest = onDismiss, title = { Text(work.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (work.description.isNotBlank()) Text(work.description)
    if (work.deliverable.isNotBlank()) Text("산출물: ${work.deliverable}", color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(); Text("체크리스트", fontWeight = FontWeight.Bold)
    items.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(item.checked, { checked -> items = items.map { if (it.id == item.id) it.copy(checked = checked) else it }; vm.toggleChecklist(item.id, checked) }); Text(item.title) } }
    Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(newItem, { newItem = it }, Modifier.weight(1f), placeholder = { Text("항목 추가") }, singleLine = true); IconButton(onClick = { if (newItem.isNotBlank()) { val title = newItem; newItem = ""; vm.saveChecklist(ChecklistItem(workId = work.id, title = title), ::reload) } }) { Icon(Icons.Rounded.Add, "추가") } }
  } }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

@Composable
private fun AssistantScreen(data: SecretarySnapshot, projectId: Long?, busy: Boolean, model: com.google.ai.edge.gallery.data.Model?, vm: SecretaryViewModel) {
  var input by remember { mutableStateOf("") }; var menu by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("AI 개인 비서", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(if (model == null) "근거 검색 모드 · 설정에서 모델을 선택하세요." else "${model.displayName.ifBlank { model.name }} · 온디바이스 RAG", style = MaterialTheme.typography.bodySmall) }; Box { AssistChip(onClick = { menu = true }, label = { Text(data.projects.firstOrNull { it.id == projectId }?.name ?: "모든 사업") }); DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("모든 사업") }, { vm.setAssistantProject(null); menu = false }); data.projects.forEach { p -> DropdownMenuItem({ Text(p.name) }, { vm.setAssistantProject(p.id); menu = false }) } } } }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (data.messages.isEmpty()) item { EmptyCard("예: 이번 주 마감 일정 알려줘\n이 사업의 개발 범위는 무엇이야?\n미완료 업무를 정리해줘") }
      items(data.messages, key = { it.id }) { msg -> Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.role == "user") Arrangement.End else Arrangement.Start) { Surface(color = if (msg.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 520.dp)) { Column(Modifier.padding(14.dp)) { Text(msg.content); if (msg.evidence.isNotBlank()) { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("근거: ${msg.evidence.replace("\n", ", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } } } } }
    }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("업무 자료에 대해 질문하세요") }, maxLines = 4); IconButton(onClick = { vm.ask(input, model); input = "" }, enabled = input.isNotBlank() && !busy) { Icon(Icons.Rounded.Send, "전송") } }
  }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onModels: () -> Unit, onBackup: (android.net.Uri) -> Unit, onRestore: (android.net.Uri) -> Unit) {
  val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { if (it != null) onBackup(it) }
  var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
  val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) pendingRestore = it }
  Scaffold(topBar = { TopAppBar(title = { Text("설정") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "뒤로") } }) }) { padding -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) {
    item { ListItem(headlineContent = { Text("AI 모델") }, supportingContent = { Text("온디바이스 모델 다운로드·가져오기·관리") }, leadingContent = { Icon(Icons.Rounded.Memory, null) }, modifier = Modifier.clickable(onClick = onModels)) }
    item { ListItem(headlineContent = { Text("데이터 백업") }, supportingContent = { Text("DB와 등록 문서를 ZIP으로 내보냅니다.") }, leadingContent = { Icon(Icons.Rounded.Backup, null) }, modifier = Modifier.clickable { backup.launch("Sec_Ond_${System.currentTimeMillis()}.zip") }) }
    item { ListItem(headlineContent = { Text("데이터 복원") }, supportingContent = { Text("Sec_Ond 백업에서 사업·업무·문서를 복원합니다.") }, leadingContent = { Icon(Icons.Rounded.Restore, null) }, modifier = Modifier.clickable { restore.launch(arrayOf("application/zip", "application/octet-stream")) }) }
    item { ListItem(headlineContent = { Text("오프라인 우선") }, supportingContent = { Text("사업 정보, 문서, 대화 기록은 기기 내부에 저장됩니다.") }, leadingContent = { Icon(Icons.Rounded.Security, null) }) }
    item { Text("Secretary Ondevice · Sec_Ond\nGoogle AI Edge Gallery 기반 · Apache 2.0", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
  } }
  pendingRestore?.let { uri -> AlertDialog(onDismissRequest = { pendingRestore = null }, title = { Text("백업을 복원할까요?") }, text = { Text("현재 데이터베이스가 백업 내용으로 교체됩니다. 이 작업은 되돌릴 수 없습니다.") }, confirmButton = { Button(onClick = { onRestore(uri); pendingRestore = null }) { Text("복원") } }, dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("취소") } }) }
}

@Composable private fun AddProjectButton(vm: SecretaryViewModel) { var open by remember { mutableStateOf(false) }; FloatingActionButton({ open = true }) { Icon(Icons.Rounded.Add, "사업 추가") }; if (open) ProjectDialog(null, { open = false }) { vm.saveProject(it); open = false } }
@Composable private fun AddWorkButton(vm: SecretaryViewModel, projects: List<Project>) { var open by remember { mutableStateOf(false) }; FloatingActionButton({ open = true }) { Icon(Icons.Rounded.AddTask, "업무 추가") }; if (open) WorkDialog(projects, { open = false }) { vm.saveWork(it); open = false } }
@Composable private fun AddScheduleButton(vm: SecretaryViewModel, projects: List<Project>) { var open by remember { mutableStateOf(false) }; FloatingActionButton({ open = true }) { Icon(Icons.Rounded.Event, "일정 추가") }; if (open) ScheduleDialog(projects, { open = false }) { vm.saveSchedule(it); open = false } }

@Composable
private fun ProjectDialog(existing: Project?, onDismiss: () -> Unit, onSave: (Project) -> Unit) {
  var name by remember { mutableStateOf(existing?.name ?: "") }; var agency by remember { mutableStateOf(existing?.agency ?: "") }; var manager by remember { mutableStateOf(existing?.manager ?: "") }; var participants by remember { mutableStateOf(existing?.participants ?: "") }; var memo by remember { mutableStateOf(existing?.memo ?: "") }; var start by remember { mutableStateOf(existing?.startAt?.let(::date) ?: "") }; var end by remember { mutableStateOf(existing?.endAt?.let(::date) ?: "") }; var status by remember { mutableStateOf(existing?.status ?: ProjectStatus.PLANNING) }; var progress by remember { mutableStateOf((existing?.progress ?: 0).toString()) }
  AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "사업 등록" else "사업 수정") }, text = { Column(Modifier.verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(7.dp)) { SimpleField("사업명 *", name) { name = it }; SimpleField("지원기관", agency) { agency = it }; SimpleField("시작일 (YYYY-MM-DD)", start) { start = it }; SimpleField("종료일 (YYYY-MM-DD)", end) { end = it }; EnumSelector("상태", status, ProjectStatus.entries, { it.label }) { status = it }; SimpleField("담당자", manager) { manager = it }; SimpleField("참여자", participants) { participants = it }; SimpleField("진행률 (0~100)", progress) { progress = it }; SimpleField("메모", memo, 3) { memo = it } } }, confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave((existing ?: Project(name = name)).copy(name = name, agency = agency, startAt = SecretaryViewModel.dateToEpoch(start), endAt = SecretaryViewModel.dateToEpoch(end), status = status, manager = manager, participants = participants, memo = memo, progress = progress.toIntOrNull()?.coerceIn(0,100) ?: 0)) }, enabled = name.isNotBlank()) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable private fun WorkDialog(projects: List<Project>, onDismiss: () -> Unit, onSave: (WorkItem) -> Unit) { var title by remember { mutableStateOf("") }; var project by remember { mutableStateOf(projects.firstOrNull()?.id) }; var desc by remember { mutableStateOf("") }; var assignee by remember { mutableStateOf("") }; var due by remember { mutableStateOf("") }; var deliverable by remember { mutableStateOf("") }; var priority by remember { mutableStateOf(Priority.NORMAL) }; AlertDialog(onDismissRequest = onDismiss, title = { Text("업무 추가") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SimpleField("업무명 *", title) { title = it }; ProjectSelector(project, projects) { project = it }; SimpleField("설명", desc, 2) { desc = it }; SimpleField("담당자", assignee) { assignee = it }; SimpleField("마감일 (YYYY-MM-DD)", due) { due = it }; SimpleField("산출물", deliverable) { deliverable = it }; EnumSelector("우선순위", priority, Priority.entries, { it.label }) { priority = it } } }, confirmButton = { Button({ onSave(WorkItem(projectId = project, title = title, description = desc, assignee = assignee, dueAt = SecretaryViewModel.dateToEpoch(due), priority = priority, deliverable = deliverable)) }, enabled = title.isNotBlank()) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }) }
@Composable private fun ScheduleDialog(projects: List<Project>, onDismiss: () -> Unit, onSave: (ScheduleItem) -> Unit) { var title by remember { mutableStateOf("") }; var project by remember { mutableStateOf(projects.firstOrNull()?.id) }; var desc by remember { mutableStateOf("") }; var date by remember { mutableStateOf("") }; var reminders by remember { mutableStateOf("7,3,1") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("일정 추가") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SimpleField("일정명 *", title) { title = it }; ProjectSelector(project, projects) { project = it }; SimpleField("날짜 (YYYY-MM-DD) *", date) { date = it }; SimpleField("설명", desc, 2) { desc = it }; SimpleField("알림 일수 (쉼표 구분)", reminders) { reminders = it } } }, confirmButton = { Button({ SecretaryViewModel.dateToEpoch(date)?.let { onSave(ScheduleItem(projectId = project, title = title, description = desc, eventAt = it, reminderDays = reminders)) } }, enabled = title.isNotBlank() && SecretaryViewModel.dateToEpoch(date) != null) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }) }
@Composable private fun KpiDialog(projectId: Long, onDismiss: () -> Unit, onSave: (KpiItem) -> Unit) { var name by remember { mutableStateOf("") }; var target by remember { mutableStateOf("") }; var current by remember { mutableStateOf("") }; var unit by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("KPI 추가") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SimpleField("지표명 *", name) { name = it }; SimpleField("목표값 *", target) { target = it }; SimpleField("현재값", current) { current = it }; SimpleField("단위", unit) { unit = it } } }, confirmButton = { Button({ onSave(KpiItem(projectId = projectId, name = name, target = target, current = current, unit = unit)) }, enabled = name.isNotBlank() && target.isNotBlank()) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }) }

@Composable private fun SimpleField(label: String, value: String, lines: Int = 1, onChange: (String) -> Unit) { OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines, maxLines = maxOf(lines, 4), singleLine = lines == 1) }
@Composable private fun ProjectSelector(value: Long?, projects: List<Project>, onChange: (Long?) -> Unit) { var open by remember { mutableStateOf(false) }; Box { OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(projects.firstOrNull { it.id == value }?.name ?: "공통 업무/일정") }; DropdownMenu(open, { open = false }) { DropdownMenuItem({ Text("공통") }, { onChange(null); open = false }); projects.forEach { p -> DropdownMenuItem({ Text(p.name) }, { onChange(p.id); open = false }) } } } }
@Composable private fun <T> EnumSelector(label: String, value: T, entries: List<T>, text: (T) -> String, onChange: (T) -> Unit) { var open by remember { mutableStateOf(false) }; Box { OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("$label: ${text(value)}") }; DropdownMenu(open, { open = false }) { entries.forEach { item -> DropdownMenuItem({ Text(text(item)) }, { onChange(item); open = false }) } } } }

private fun tabIcon(tab: MainTab) = when (tab) { MainTab.HOME -> Icons.Rounded.Home; MainTab.PROJECTS -> Icons.Rounded.BusinessCenter; MainTab.SCHEDULE -> Icons.Rounded.CalendarMonth; MainTab.WORK -> Icons.Rounded.Checklist; MainTab.ASSISTANT -> Icons.AutoMirrored.Rounded.Chat }
private fun SecretarySnapshot.projectName(id: Long?) = projects.firstOrNull { it.id == id }?.name ?: "공통"
private fun date(value: Long?): String = value?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() } ?: "미정"
private fun dDay(value: Long): String { val days = ChronoUnit.DAYS.between(java.time.LocalDate.now(), Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate()); return when { days == 0L -> "D-Day"; days > 0 -> "D-$days"; else -> "D+${-days}" } }
@Composable private fun Modifier.verticalScrollCompat(): Modifier = this.heightIn(max = 560.dp)
