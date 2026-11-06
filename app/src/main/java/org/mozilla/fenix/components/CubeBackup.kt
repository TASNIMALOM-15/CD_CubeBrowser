/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mozilla.components.support.webextensions.WebExtensionSupport
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Cube: export/import of settings, bookmarks + history, wallpapers and the extension list to a zip the user
 * keeps outside the app (survives uninstall). Import is staged and applied at the next app start.
 * Saved passwords are NOT included (their encryption keys live in the Android Keystore and cannot be restored).
 */
object CubeBackup {
    private const val STAGE_DIR = "cube_restore"
    const val ADDONS_PENDING = "cube_addons_pending.txt"

    private fun toast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }

    private fun dataDir(context: Context) = File(context.applicationInfo.dataDir)

    private fun placesFiles(context: Context): List<File> {
        val roots = listOf(context.filesDir, File(dataDir(context), "databases"))
        return roots.flatMap { root ->
            root.walkTopDown().maxDepth(3).filter { it.isFile && it.name.startsWith("places.sqlite") }.toList()
        }
    }

    private fun relativeToData(context: Context, f: File): String = f.relativeTo(dataDir(context)).path

    fun showDialog(
        context: Context,
        onExport: () -> Unit,
        onImport: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Backup & Restore")
            .setItems(arrayOf("Export backup (zip)", "Import backup (zip)")) { _, which ->
                if (which == 0) onExport() else onImport()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun export(context: Context, uri: Uri) {
        val app = context.applicationContext
        val extensionIds = WebExtensionSupport.installedExtensions
            .filterValues { !it.isBuiltIn() }
            .map { it.value.id }
        thread {
            try {
                val data = dataDir(app)
                app.contentResolver.openOutputStream(uri)?.use { raw ->
                    ZipOutputStream(raw).use { zip ->
                        fun addFile(name: String, file: File) {
                            zip.putNextEntry(ZipEntry(name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                        File(data, "shared_prefs").listFiles()
                            ?.filter { it.name == "${app.packageName}_preferences.xml" || it.name.startsWith("cube_") }
                            ?.forEach { addFile("prefs/${it.name}", it) }
                        placesFiles(app).forEach { addFile("db/${relativeToData(app, it)}", it) }
                        val wall = File(app.filesDir, "wallpapers")
                        wall.walkTopDown().filter { it.isFile }.forEach {
                            addFile("wallpapers/${it.relativeTo(wall).path}", it)
                        }
                        zip.putNextEntry(ZipEntry("addons.txt"))
                        zip.write(extensionIds.joinToString("\n").toByteArray())
                        zip.closeEntry()
                    }
                }
                toast(app, "Backup saved")
            } catch (e: Exception) {
                toast(app, "Backup failed: ${e.message}")
            }
        }
    }

    /** Extracts the zip to a staging folder; applied on next start by [applyPendingRestore]. */
    fun stageImport(context: Context, uri: Uri, onDone: () -> Unit) {
        val app = context.applicationContext
        thread {
            try {
                val stage = File(app.filesDir, STAGE_DIR)
                stage.deleteRecursively()
                stage.mkdirs()
                app.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val out = File(stage, entry.name)
                            if (!entry.isDirectory && out.canonicalPath.startsWith(stage.canonicalPath + File.separator)) {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zip.copyTo(it) }
                            }
                            entry = zip.nextEntry
                        }
                    }
                }
                Handler(Looper.getMainLooper()).post { onDone() }
            } catch (e: Exception) {
                toast(app, "Import failed: ${e.message}")
            }
        }
    }

    /** Must run before anything reads preferences or databases (called from attachBaseContext). */
    fun applyPendingRestore(context: Context) {
        try {
            val data = File(context.applicationInfo.dataDir)
            val stage = File(data, "files/$STAGE_DIR")
            if (!stage.isDirectory) return
            val shared = File(data, "shared_prefs").apply { mkdirs() }
            File(stage, "prefs").listFiles()?.forEach { it.copyTo(File(shared, it.name), overwrite = true) }
            val dbRoot = File(stage, "db")
            if (dbRoot.isDirectory) {
                // Remove old places files first so stale WAL files cannot corrupt the restored database.
                dbRoot.walkTopDown().filter { it.isFile }.forEach { src ->
                    val target = File(data, src.relativeTo(dbRoot).path)
                    target.parentFile?.mkdirs()
                    File(target.parentFile, target.name.substringBefore("sqlite") + "sqlite-wal").delete()
                    File(target.parentFile, target.name.substringBefore("sqlite") + "sqlite-shm").delete()
                }
                dbRoot.walkTopDown().filter { it.isFile }.forEach { src ->
                    src.copyTo(File(data, src.relativeTo(dbRoot).path), overwrite = true)
                }
            }
            val wallSrc = File(stage, "wallpapers")
            if (wallSrc.isDirectory) {
                val wallDst = File(data, "files/wallpapers")
                wallDst.deleteRecursively()
                wallSrc.copyRecursively(wallDst, overwrite = true)
            }
            File(stage, "addons.txt").takeIf { it.isFile }
                ?.copyTo(File(data, "files/$ADDONS_PENDING"), overwrite = true)
            stage.deleteRecursively()
        } catch (e: Exception) {
            // Best effort: never block app start.
        }
    }
}
