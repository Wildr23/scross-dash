package ua.odesa.scrossdash.data.log

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LogExporter(
    private val context: Context,
    private val logger: TelemetryCsvLogger,
    private val extraZipEntriesProvider: (() -> Map<String, String>)? = null
) {

    fun exportAndShareLatestLog() {
        logger.flush()
        val logFile = logger.getLatestLogFile()
        if (logFile == null || !logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(context, "Немає доступних CSV логів для експорту", Toast.LENGTH_SHORT).show()
            return
        }
        exportAndShareSingleFile(logFile)
    }

    fun exportAndShareLogByName(fileName: String) {
        logger.flush()
        val logFile = logger.getLogFileByName(fileName)
        if (logFile == null || !logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(context, "CSV файл не знайдено або видалено", Toast.LENGTH_SHORT).show()
            return
        }
        exportAndShareSingleFile(logFile)
    }

    fun exportAllLogsAsZip() {
        logger.flush()
        val extras = extraZipEntriesProvider?.invoke().orEmpty()
        exportAllLogsAsZip(context, extras)
    }

    private fun exportAndShareSingleFile(logFile: File) {
        saveToDownloads(context, logFile, "text/csv")

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "ua.odesa.scrossdash.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "S-Cross Telemetry Log: ${logFile.name}")
                putExtra(Intent.EXTRA_TEXT, "Телеметрія Suzuki S-Cross 1.4 BoosterJet 48V: ${logFile.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Поділитися CSV логом").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Помилка експорту: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        fun exportAllLogsAsZip(context: Context, extraEntries: Map<String, String> = emptyMap()) {
            val logDir = File(context.filesDir, "logs")
            val csvFiles = logDir.listFiles { _, name -> name.endsWith(".csv") }
                ?.filter { it.exists() && it.length() > 0L }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            val validExtras = extraEntries.filter { (name, content) ->
                name.isNotBlank() && content.isNotBlank()
            }

            if (csvFiles.isEmpty() && validExtras.isEmpty()) {
                Toast.makeText(context, "Немає доступних CSV логів для архівування", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val zipFile = File(context.cacheDir, "scross_telemetry_all_$stamp.zip")
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    val buffer = ByteArray(8192)
                    for (csv in csvFiles) {
                        FileInputStream(csv).use { fis ->
                            zos.putNextEntry(ZipEntry(csv.name))
                            var len: Int
                            while (fis.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                            zos.closeEntry()
                        }
                    }
                    for ((entryName, content) in validExtras) {
                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(content.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }

                saveToDownloads(context, zipFile, "application/zip")

                val uri = FileProvider.getUriForFile(
                    context,
                    "ua.odesa.scrossdash.fileprovider",
                    zipFile
                )

                val totalFiles = csvFiles.size + validExtras.size
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "S-Cross Telemetry Archive ($totalFiles файлів)")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Архів даних Suzuki S-Cross 48V (${csvFiles.size} телеметричних CSV + ${validExtras.size} звітів витрат/ТО)"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val chooser = Intent.createChooser(shareIntent, "Експортувати дані (ZIP)").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Помилка створення ZIP: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        private fun saveToDownloads(context: Context, sourceFile: File, mimeType: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SCrossDash")
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { output ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
