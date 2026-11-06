/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import android.content.Context
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/** Cube: simple multi-profile storage. Each non-default profile uses its own Gecko contextId (separate cookies). */
object CubeProfiles {
    const val DEFAULT_ID = "default"
    private const val PREFS = "cube_profiles"
    private const val KEY_ACTIVE = "active"
    private const val KEY_LIST = "list"

    data class Profile(val id: String, val name: String)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(context: Context): List<Profile> {
        val result = mutableListOf(Profile(DEFAULT_ID, "Default"))
        prefs(context).getString(KEY_LIST, "")?.split("\n")?.filter { it.isNotBlank() }?.forEach {
            val parts = it.split("\t")
            if (parts.size == 2) result.add(Profile(parts[0], parts[1]))
        }
        return result
    }

    fun activeId(context: Context): String = prefs(context).getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun add(context: Context, name: String): Profile {
        val clean = name.replace("\t", " ").replace("\n", " ").trim().ifEmpty { "Profile" }
        val profile = Profile("profile_${System.currentTimeMillis()}", clean)
        val current = prefs(context).getString(KEY_LIST, "") ?: ""
        prefs(context).edit().putString(KEY_LIST, current + "${profile.id}\t${profile.name}\n").apply()
        return profile
    }
}

/** Assigns the active profile's contextId to every new normal tab (links opened from a tab inherit its profile). */
class CubeProfileMiddleware(private val context: Context) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is TabListAction.AddTabAction && !action.tab.content.private && action.tab.contextId == null) {
            val parentId = action.tab.parentId
            val parent = if (parentId != null) store.state.findTab(parentId) else null
            val contextId =
                if (parent != null) {
                    parent.contextId
                } else {
                    CubeProfiles.activeId(context).takeIf { it != CubeProfiles.DEFAULT_ID }
                }
            if (contextId != null) {
                next(action.copy(tab = action.tab.copy(contextId = contextId)))
                return
            }
        }
        next(action)
    }
}
