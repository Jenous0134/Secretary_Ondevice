/* Copyright 2026 Jenous. Licensed under the Apache License, Version 2.0. */
package com.google.ai.edge.gallery.secretary.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class SecretaryDatabase(private val appContext: Context) :
  SQLiteOpenHelper(appContext, DATABASE_NAME, null, DATABASE_VERSION) {

  override fun onConfigure(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(true)
    db.enableWriteAheadLogging()
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,agency TEXT NOT NULL DEFAULT '',start_at INTEGER,end_at INTEGER,status TEXT NOT NULL,manager TEXT NOT NULL DEFAULT '',participants TEXT NOT NULL DEFAULT '',memo TEXT NOT NULL DEFAULT '',progress INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)""")
    db.execSQL("""CREATE TABLE works(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',assignee TEXT NOT NULL DEFAULT '',due_at INTEGER,priority TEXT NOT NULL,status TEXT NOT NULL,progress INTEGER NOT NULL DEFAULT 0,deliverable TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)""")
    db.execSQL("""CREATE TABLE schedules(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',event_at INTEGER NOT NULL,reminder_days TEXT NOT NULL DEFAULT '7,3,1',created_at INTEGER NOT NULL,FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)""")
    db.execSQL("""CREATE TABLE documents(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER,title TEXT NOT NULL,category TEXT NOT NULL,version TEXT NOT NULL,mime_type TEXT NOT NULL,local_path TEXT NOT NULL,original_name TEXT NOT NULL,extracted_text TEXT NOT NULL DEFAULT '',indexed_at INTEGER,created_at INTEGER NOT NULL,FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)""")
    db.execSQL("""CREATE TABLE document_chunks(id INTEGER PRIMARY KEY AUTOINCREMENT,document_id INTEGER NOT NULL,chunk_index INTEGER NOT NULL,text TEXT NOT NULL,normalized TEXT NOT NULL,FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)""")
    db.execSQL("CREATE INDEX idx_chunks_document ON document_chunks(document_id)")
    db.execSQL("""CREATE TABLE kpis(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,name TEXT NOT NULL,target TEXT NOT NULL,current_value TEXT NOT NULL DEFAULT '',unit TEXT NOT NULL DEFAULT '',FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)""")
    db.execSQL("""CREATE TABLE checklist(id INTEGER PRIMARY KEY AUTOINCREMENT,work_id INTEGER NOT NULL,title TEXT NOT NULL,checked INTEGER NOT NULL DEFAULT 0,FOREIGN KEY(work_id) REFERENCES works(id) ON DELETE CASCADE)""")
    db.execSQL("""CREATE TABLE decisions(id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER,title TEXT NOT NULL,content TEXT NOT NULL,verified INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)""")
    db.execSQL("""CREATE TABLE chat_messages(id INTEGER PRIMARY KEY AUTOINCREMENT,role TEXT NOT NULL,content TEXT NOT NULL,evidence TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)""")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  @Synchronized
  fun snapshot(): SecretarySnapshot {
    val db = readableDatabase
    return SecretarySnapshot(
      projects = db.query("projects").mapRows { project() },
      works = db.query("works", order = "CASE status WHEN 'DONE' THEN 1 ELSE 0 END, due_at IS NULL, due_at").mapRows { work() },
      schedules = db.query("schedules", order = "event_at").mapRows { schedule() },
      documents = db.query("documents", order = "created_at DESC").mapRows { document() },
      kpis = db.query("kpis").mapRows { kpi() },
      messages = db.query("chat_messages", order = "created_at").mapRows { chat() },
    )
  }

  @Synchronized
  fun saveProject(item: Project): Long {
    val values = ContentValues().apply {
      put("name", item.name); put("agency", item.agency); putNullable("start_at", item.startAt)
      putNullable("end_at", item.endAt); put("status", item.status.name); put("manager", item.manager)
      put("participants", item.participants); put("memo", item.memo); put("progress", item.progress)
      put("created_at", item.createdAt); put("updated_at", System.currentTimeMillis())
    }
    return if (item.id == 0L) writableDatabase.insertOrThrow("projects", null, values)
    else { writableDatabase.update("projects", values, "id=?", arrayOf(item.id.toString())); item.id }
  }

  @Synchronized
  fun saveWork(item: WorkItem): Long {
    val values = ContentValues().apply {
      putNullable("project_id", item.projectId); put("title", item.title); put("description", item.description)
      put("assignee", item.assignee); putNullable("due_at", item.dueAt); put("priority", item.priority.name)
      put("status", item.status.name); put("progress", item.progress); put("deliverable", item.deliverable)
      put("created_at", item.createdAt)
    }
    return if (item.id == 0L) writableDatabase.insertOrThrow("works", null, values)
    else { writableDatabase.update("works", values, "id=?", arrayOf(item.id.toString())); item.id }
  }

  @Synchronized
  fun saveSchedule(item: ScheduleItem): Long {
    val values = ContentValues().apply {
      putNullable("project_id", item.projectId); put("title", item.title); put("description", item.description)
      put("event_at", item.eventAt); put("reminder_days", item.reminderDays); put("created_at", item.createdAt)
    }
    return writableDatabase.insertOrThrow("schedules", null, values)
  }

  @Synchronized
  fun saveKpi(item: KpiItem): Long = writableDatabase.insertOrThrow("kpis", null, ContentValues().apply {
    put("project_id", item.projectId); put("name", item.name); put("target", item.target)
    put("current_value", item.current); put("unit", item.unit)
  })

  @Synchronized
  fun saveChecklist(item: ChecklistItem): Long = writableDatabase.insertOrThrow("checklist", null, ContentValues().apply {
    put("work_id", item.workId); put("title", item.title); put("checked", if (item.checked) 1 else 0)
  })

  @Synchronized
  fun checklist(workId: Long): List<ChecklistItem> = readableDatabase.query(
    "checklist", null, "work_id=?", arrayOf(workId.toString()), null, null, "id"
  ).mapRows { ChecklistItem(long("id"), long("work_id"), string("title"), int("checked") == 1) }

  @Synchronized
  fun toggleChecklist(id: Long, checked: Boolean) {
    writableDatabase.update("checklist", ContentValues().apply { put("checked", if (checked) 1 else 0) }, "id=?", arrayOf(id.toString()))
  }

  @Synchronized
  fun saveDocument(item: DocumentItem): Long {
    val values = ContentValues().apply {
      putNullable("project_id", item.projectId); put("title", item.title); put("category", item.category)
      put("version", item.version); put("mime_type", item.mimeType); put("local_path", item.localPath)
      put("original_name", item.originalName); put("extracted_text", item.extractedText)
      putNullable("indexed_at", item.indexedAt); put("created_at", item.createdAt)
    }
    val id = writableDatabase.insertOrThrow("documents", null, values)
    indexDocument(id, item.extractedText)
    return id
  }

  private fun indexDocument(documentId: Long, text: String) {
    if (text.isBlank()) return
    val chunks = text.replace("\r", "").split(Regex("\n{2,}"))
      .flatMap { paragraph -> paragraph.chunked(900) }.map { it.trim() }.filter { it.length >= 20 }
    writableDatabase.beginTransaction()
    try {
      chunks.forEachIndexed { index, chunk ->
        writableDatabase.insert("document_chunks", null, ContentValues().apply {
          put("document_id", documentId); put("chunk_index", index); put("text", chunk)
          put("normalized", normalize(chunk))
        })
      }
      writableDatabase.setTransactionSuccessful()
    } finally { writableDatabase.endTransaction() }
  }

  @Synchronized
  fun searchEvidence(query: String, projectId: Long?, limit: Int = 6): List<Evidence> {
    val terms = normalize(query).split(' ').filter { it.length >= 2 }.distinct()
    if (terms.isEmpty()) return emptyList()
    val where = buildString {
      append(terms.joinToString(" OR ") { "c.normalized LIKE ?" })
      if (projectId != null) append(" AND d.project_id=?")
    }
    val args = terms.map { "%$it%" }.toMutableList().apply { if (projectId != null) add(projectId.toString()) }
    val sql = """SELECT c.document_id,c.chunk_index,c.text,d.title,COALESCE(p.name,'공통') project_name FROM document_chunks c JOIN documents d ON d.id=c.document_id LEFT JOIN projects p ON p.id=d.project_id WHERE $where LIMIT 40"""
    return readableDatabase.rawQuery(sql, args.toTypedArray()).mapRows {
      val body = string("text"); val normalized = normalize(body)
      Evidence(long("document_id"), string("title"), string("project_name"), int("chunk_index"), body, terms.count { normalized.contains(it) })
    }.sortedByDescending { it.score }.take(limit)
  }

  @Synchronized
  fun saveChat(message: ChatMessage): Long = writableDatabase.insertOrThrow("chat_messages", null, ContentValues().apply {
    put("role", message.role); put("content", message.content); put("evidence", message.evidence); put("created_at", message.createdAt)
  })

  @Synchronized
  fun delete(table: String, id: Long) {
    require(table in setOf("projects", "works", "schedules", "documents", "kpis"))
    if (table == "documents") readableDatabase.rawQuery("SELECT local_path FROM documents WHERE id=?", arrayOf(id.toString())).use {
      if (it.moveToFirst()) File(it.getString(0)).delete()
    }
    writableDatabase.delete(table, "id=?", arrayOf(id.toString()))
  }

  @Synchronized
  fun writeBackup(output: java.io.OutputStream) {
    writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close()
    ZipOutputStream(output).use { zip ->
      val dbFile = appContext.getDatabasePath(DATABASE_NAME)
      if (dbFile.exists()) { zip.putNextEntry(ZipEntry("database/$DATABASE_NAME")); dbFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
      val docs = File(appContext.filesDir, "secretary_documents")
      docs.listFiles()?.forEach { file -> zip.putNextEntry(ZipEntry("documents/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
    }
  }

  @Synchronized
  fun restoreBackup(input: java.io.InputStream) {
    val staging = File(appContext.cacheDir, "secretary_restore_${System.currentTimeMillis()}").apply { mkdirs() }
    try {
      ZipInputStream(input).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          val target = File(staging, entry.name).canonicalFile
          require(target.path.startsWith(staging.canonicalPath + File.separator)) { "잘못된 백업 경로입니다." }
          if (!entry.isDirectory) { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
          zip.closeEntry(); entry = zip.nextEntry
        }
      }
      val restoredDb = File(staging, "database/$DATABASE_NAME")
      require(restoredDb.exists() && restoredDb.length() > 0) { "Sec_Ond 백업 데이터베이스가 없습니다." }
      close()
      val targetDb = appContext.getDatabasePath(DATABASE_NAME)
      targetDb.parentFile?.mkdirs()
      File(targetDb.path + "-wal").delete(); File(targetDb.path + "-shm").delete()
      restoredDb.copyTo(targetDb, overwrite = true)
      val docsTarget = File(appContext.filesDir, "secretary_documents").apply { mkdirs() }
      val restoredDocs = File(staging, "documents")
      if (restoredDocs.exists()) restoredDocs.listFiles()?.forEach { it.copyTo(File(docsTarget, it.name), overwrite = true) }
      readableDatabase.rawQuery("PRAGMA integrity_check", null).use { cursor ->
        require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "복원된 데이터베이스가 손상되었습니다." }
      }
    } finally { staging.deleteRecursively() }
  }

  private fun SQLiteDatabase.query(table: String, order: String = "id") = query(table, null, null, null, null, null, order)
  private inline fun <T> Cursor.mapRows(block: Cursor.() -> T): List<T> = use { buildList { while (moveToNext()) add(block()) } }
  private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name)) ?: ""
  private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
  private fun Cursor.longOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
  private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
  private fun Cursor.project() = Project(long("id"), string("name"), string("agency"), longOrNull("start_at"), longOrNull("end_at"), enumOr(ProjectStatus.PLANNING, string("status")), string("manager"), string("participants"), string("memo"), int("progress"), long("created_at"), long("updated_at"))
  private fun Cursor.work() = WorkItem(long("id"), longOrNull("project_id"), string("title"), string("description"), string("assignee"), longOrNull("due_at"), enumOr(Priority.NORMAL, string("priority")), enumOr(WorkStatus.TODO, string("status")), int("progress"), string("deliverable"), long("created_at"))
  private fun Cursor.schedule() = ScheduleItem(long("id"), longOrNull("project_id"), string("title"), string("description"), long("event_at"), string("reminder_days"), long("created_at"))
  private fun Cursor.document() = DocumentItem(long("id"), longOrNull("project_id"), string("title"), string("category"), string("version"), string("mime_type"), string("local_path"), string("original_name"), string("extracted_text"), longOrNull("indexed_at"), long("created_at"))
  private fun Cursor.kpi() = KpiItem(long("id"), long("project_id"), string("name"), string("target"), string("current_value"), string("unit"))
  private fun Cursor.chat() = ChatMessage(long("id"), string("role"), string("content"), string("evidence"), long("created_at"))
  private inline fun <reified T : Enum<T>> enumOr(fallback: T, value: String) = enumValues<T>().firstOrNull { it.name == value } ?: fallback
  private fun ContentValues.putNullable(key: String, value: Long?) { if (value == null) putNull(key) else put(key, value) }

  companion object {
    const val DATABASE_NAME = "secretary_ondevice.db"
    const val DATABASE_VERSION = 1
    fun normalize(value: String) = value.lowercase().replace(Regex("[^가-힣a-z0-9]+"), " ").trim()
  }
}
