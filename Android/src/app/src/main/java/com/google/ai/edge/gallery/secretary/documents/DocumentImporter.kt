/* Copyright 2026 Jenous. Licensed under the Apache License, Version 2.0. */
package com.google.ai.edge.gallery.secretary.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

data class ImportedDocument(val file: File, val displayName: String, val mimeType: String, val text: String)

class DocumentImporter(private val context: Context) {
  fun import(uri: Uri): ImportedDocument {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      if (it.moveToFirst()) it.getString(0) else null
    } ?: "document_${System.currentTimeMillis()}"
    val mime = resolver.getType(uri) ?: mimeFromName(name)
    val directory = File(context.filesDir, "secretary_documents").apply { mkdirs() }
    val safeName = name.replace(Regex("[^가-힣a-zA-Z0-9._-]"), "_")
    val target = File(directory, "${UUID.randomUUID()}_$safeName")
    resolver.openInputStream(uri).use { input -> requireNotNull(input) { "파일을 열 수 없습니다." }; target.outputStream().use { input.copyTo(it) } }
    val text = runCatching { extract(target, mime, name) }.getOrElse { "" }
    return ImportedDocument(target, name, mime, text)
  }

  private fun extract(file: File, mime: String, name: String): String = when {
    mime == "application/pdf" || name.endsWith(".pdf", true) -> extractPdf(file)
    name.endsWith(".docx", true) -> extractZipXml(file, "word/document.xml")
    name.endsWith(".hwpx", true) -> extractHwpx(file)
    mime.startsWith("text/") || name.endsWith(".txt", true) || name.endsWith(".md", true) -> file.readText()
    name.endsWith(".hwp", true) -> "[HWP 바이너리 문서는 현재 원본 보관만 지원합니다. PDF 또는 HWPX로 변환하면 AI 검색이 가능합니다.]"
    else -> ""
  }

  private fun extractPdf(file: File): String {
    PDFBoxResourceLoader.init(context)
    return PDDocument.load(file).use { document -> PDFTextStripper().apply { sortByPosition = true }.getText(document) }
  }

  private fun extractHwpx(file: File): String = ZipFile(file).use { zip ->
    zip.entries().asSequence().filter { !it.isDirectory && it.name.matches(Regex("Contents/section\\d+\\.xml")) }
      .sortedBy { it.name }.joinToString("\n\n") { entry -> xmlToText(zip.getInputStream(entry).bufferedReader().readText()) }
  }

  private fun extractZipXml(file: File, entryName: String): String = ZipFile(file).use { zip ->
    val entry = zip.getEntry(entryName) ?: return@use ""
    xmlToText(zip.getInputStream(entry).bufferedReader().readText())
  }

  private fun xmlToText(xml: String): String = xml
    .replace(Regex("</(w:p|hp:p|p)>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    .replace(Regex("[ \\t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()

  private fun mimeFromName(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "hwpx" -> "application/vnd.hancom.hwpx"
    "hwp" -> "application/x-hwp"
    else -> "application/octet-stream"
  }
}
