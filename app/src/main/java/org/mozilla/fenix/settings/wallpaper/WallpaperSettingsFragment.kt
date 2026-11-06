/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.telemetry.glean.private.NoExtras
import org.mozilla.fenix.GleanMetrics.Wallpapers
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.core.Action
import org.mozilla.fenix.compose.snackbar.Snackbar
import org.mozilla.fenix.compose.snackbar.SnackbarState
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.openToBrowser
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.wallpapers.Wallpaper

/** Settings screen allowing users to choose what wallpaper the application should use. */
class WallpaperSettingsFragment : Fragment(), SystemInsetsPaddedFragment {
    private val appStore by lazy {
        requireComponents.appStore
    }

    private val wallpaperUseCases by lazy {
        requireComponents.useCases.wallpaperUseCases
    }

    // Cube: pick a custom wallpaper from the gallery.
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val wallpaper = withContext(Dispatchers.IO) { saveCustomWallpaper(uri) }
                    if (wallpaper != null) {
                        wallpaperUseCases.selectWallpaper(wallpaper)
                    }
                }
            }
        }

    private fun saveCustomWallpaper(uri: Uri): Wallpaper? {
        return try {
            val resolver = requireContext().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > CUSTOM_MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap: Bitmap =
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: return null
            val name = "custom_${System.currentTimeMillis()}"
            val thumbScale = CUSTOM_THUMB_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val thumb =
                Bitmap.createScaledBitmap(
                    bitmap,
                    maxOf(1, (bitmap.width * thumbScale).toInt()),
                    maxOf(1, (bitmap.height * thumbScale).toInt()),
                    true,
                )
            val root = requireContext().filesDir
            Wallpaper.ImageType.entries.forEach { type ->
                val file = File(root, Wallpaper.getLocalPath(name, type))
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    val src = if (type == Wallpaper.ImageType.Thumbnail) thumb else bitmap
                    src.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            Wallpaper(
                name = name,
                collection = Wallpaper.DefaultCollection,
                textColor = 0xFFFFFFFFL,
                cardColorLight = 0xB3000000L,
                cardColorDark = 0xB3000000L,
                thumbnailFileState = Wallpaper.ImageFileState.Downloaded,
                assetsFileState = Wallpaper.ImageFileState.Downloaded,
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        Wallpapers.wallpaperSettingsOpened.record(NoExtras())
        val wallpaperSettings =
            ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    FirefoxTheme {
                        val wallpapers =
                            appStore
                                .observeAsComposableState { state ->
                                    state.wallpaperState.availableWallpapers
                                }
                                .value
                        val currentWallpaper =
                            appStore
                                .observeAsComposableState { state ->
                                    state.wallpaperState.currentWallpaper
                                }
                                .value

                        val coroutineScope = rememberCoroutineScope()

                        WallpaperSettings(
                            wallpaperGroups = wallpapers.groupByDisplayableCollection(),
                            selectedWallpaper = currentWallpaper,
                            loadWallpaperResource = { wallpaper, size ->
                                wallpaperUseCases.loadThumbnail(wallpaper, size)
                            },
                            onSelectWallpaper = {
                                if (it.name != currentWallpaper.name) {
                                    coroutineScope.launch {
                                        val result = wallpaperUseCases.selectWallpaper(it)
                                        onWallpaperSelected(it, result, requireView())
                                    }
                                }
                            },
                            onLearnMoreClick = { url, collectionName ->
                                findNavController().openToBrowser()
                                requireComponents.useCases.fenixBrowserUseCases.loadUrlOrSearch(
                                    searchTermOrURL = url,
                                    newTab = true,
                                )
                                Wallpapers.learnMoreLinkClick.record(
                                    Wallpapers.LearnMoreLinkClickExtra(
                                        url = url,
                                        collectionName = collectionName,
                                    )
                                )
                            },
                        )
                    }
                }
            }

        // Using CoordinatorLayout as a parent view for the fragment gives the benefit of hiding
        // snackbars automatically when the fragment is closed.
        return CoordinatorLayout(requireContext()).apply {
            val pickButton =
                Button(requireContext()).apply {
                    text = "Custom wallpaper (Gallery)"
                    setOnClickListener { pickImage.launch("image/*") }
                }
            val column =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        pickButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(wallpaperSettings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                }
            addView(
                column,
                CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun onWallpaperSelected(
        wallpaper: Wallpaper,
        result: Wallpaper.ImageFileState,
        view: View,
    ) {
        when (result) {
            Wallpaper.ImageFileState.Downloaded -> {
                Wallpapers.wallpaperSelected.record(
                    Wallpapers.WallpaperSelectedExtra(
                        name = wallpaper.name,
                        source = "settings",
                        themeCollection = wallpaper.collection.name,
                    )
                )
            }
            Wallpaper.ImageFileState.Error -> {
                Snackbar.make(
                        snackBarParentView = view,
                        snackbarState =
                            SnackbarState(
                                message = getString(R.string.wallpaper_download_error_snackbar_message),
                                action =
                                    Action(
                                        label = getString(R.string.wallpaper_download_error_snackbar_action),
                                        onClick = {
                                            viewLifecycleOwner.lifecycleScope.launch {
                                                val retryResult = wallpaperUseCases.selectWallpaper(wallpaper)
                                                onWallpaperSelected(wallpaper, retryResult, view)
                                            }
                                        },
                                    ),
                            ),
                    )
                    .show()
            }
            else -> {
                /* noop */
            }
        }

        view.context.components.settings.showWallpaperOnboarding = false
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.customize_wallpapers))
    }
}

private const val CUSTOM_MAX_SIDE = 2048
private const val CUSTOM_THUMB_SIDE = 300
