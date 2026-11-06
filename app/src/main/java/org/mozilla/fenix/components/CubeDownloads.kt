/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Cube: choose between the built-in downloader and an external download manager app (e.g. 1DM). */
object CubeDownloads {
    private const val PREFS = "cube_download"
    private const val KEY_MODE = "mode"
    private const val KEY_PKG = "pkg"
    const val MODE_ASK = "ask"
    const val MODE_INTERNAL = "internal"
    const val MODE_EXTERNAL = "external"
    private val KNOWN = listOf(
        "idm.internet.download.manager.plus",
        "idm.internet.download.manager",
        "com.dv.adm",
        "com.dv.adm.pay",
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun mode(c: Context) = prefs(c).getString(KEY_MODE, MODE_ASK) ?: MODE_ASK
    private fun pkg(c: Context): String? = prefs(c).getString(KEY_PKG, null)

    private data class App(val pkg: String, val label: String)

    private fun sampleIntent(): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/file.zip"), "application/octet-stream")

    private fun candidateApps(c: Context): List<App> {
        val pm = c.packageManager
        val found = pm.queryIntentActivities(sampleIntent(), 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != c.packageName }
            .distinctBy { it.pkg }
        return found.sortedBy { app -> KNOWN.indexOf(app.pkg).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private fun labelOf(c: Context, pkg: String): String =
        try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

    /** Dialog to pick the external download manager app. */
    fun pickApp(context: Context, onPicked: (String) -> Unit = {}) {
        val apps = candidateApps(context)
        if (apps.isEmpty()) {
            Toast.makeText(context, "No external download app found", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Select download manager app")
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                prefs(context).edit().putString(KEY_PKG, apps[which].pkg).apply()
                Toast.makeText(context, apps[which].label, Toast.LENGTH_SHORT).show()
                onPicked(apps[which].pkg)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Settings dialog. */
    fun showSettings(context: Context) {
        val modes = listOf(MODE_ASK, MODE_INTERNAL, MODE_EXTERNAL)
        val current = pkg(context)?.let { " (${labelOf(context, it)})" } ?: ""
        val labels = arrayOf(
            "Ask every time",
            "Always in Cube",
            "Always external app",
            "Choose external app$current...",
        )
        MaterialAlertDialogBuilder(context)
            .setTitle("Downloads")
            .setSingleChoiceItems(labels, modes.indexOf(mode(context)).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                if (which < modes.size) {
                    prefs(context).edit().putString(KEY_MODE, modes[which]).apply()
                    if (modes[which] == MODE_EXTERNAL && pkg(context) == null) pickApp(context)
                } else {
                    pickApp(context)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launch(
        context: Context,
        pkg: String,
        url: String,
        contentType: String?,
        userAgent: String?,
        referrer: String?,
    ): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val headers = Bundle()
        userAgent?.let { headers.putString("User-Agent", it) }
        referrer?.let { headers.putString("Referer", it) }
        intent.putExtra("com.android.browser.headers", headers)
        intent.putExtra("referer", referrer)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Called for each normal download. Returns true when Cube handled the prompt (dialog shown or external launch);
     * false to let the normal in-browser flow continue.
     */
    @Suppress("LongParameterList")
    fun intercept(
        context: Context,
        url: String,
        contentType: String?,
        userAgent: String?,
        referrer: String?,
        startInternal: () -> Unit,
        cancel: () -> Unit,
    ): Boolean {
        val m = mode(context)
        if (m == MODE_INTERNAL) return false

        fun external(p: String) {
            if (launch(context, p, url, contentType, userAgent, referrer)) {
                cancel()
            } else {
                Toast.makeText(context, "External app failed, using Cube", Toast.LENGTH_SHORT).show()
                startInternal()
            }
        }

        val saved = pkg(context)?.takeIf {
            try {
                context.packageManager.getApplicationInfo(it, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
        if (m == MODE_EXTERNAL) {
            if (saved != null) {
                external(saved)
            } else {
                pickApp(context) { external(it) }
            }
            return true
        }
        val extLabel = saved?.let { labelOf(context, it) } ?: "External app..."
        MaterialAlertDialogBuilder(context)
            .setTitle("Download")
            .setItems(arrayOf("Download in Cube", extLabel)) { _, which ->
                if (which == 0) {
                    startInternal()
                } else if (saved != null) {
                    external(saved)
                } else {
                    pickApp(context) { external(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .show()
        return true
    }
}
