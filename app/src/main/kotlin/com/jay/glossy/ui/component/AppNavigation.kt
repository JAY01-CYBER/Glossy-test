@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.jay.glossy.ui.component

import androidx.compose.animation.animateColorAsState
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.jay.glossy.constants.EnableGlassFloatingNavBarKey
import com.jay.glossy.constants.UseFloatingNavBarKey
import com.jay.glossy.ui.screens.Screens
import com.jay.glossy.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import java.nio.IntBuffer
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds

@Immutable
private data class NavItemState(
    val isSelected: Boolean,
    val iconRes: Int
)

@Stable
private fun isRouteSelected(currentRoute: String?, screenRoute: String, navigationItems: List<Screens>): Boolean {
    if (currentRoute == null) return false
    if (currentRoute == screenRoute) return true
    if (navigationItems.any { it.route == screenRoute } &&
        currentRoute.startsWith("$screenRoute/")) return true

    if (screenRoute == "search_input" &&
        (currentRoute.startsWith("search/") || currentRoute == "search/{query}")) return true

    return false
}

// ----------------------------------------------------
// Navigation Rail (For Tablet/Landscape Mode)
// ----------------------------------------------------
@Composable
fun AppNavigationRail(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null
) {
    val containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current


    NavigationRail(
        modifier = modifier,
        containerColor = containerColor
    ) {
        Spacer(modifier = Modifier.weight(1f))

        navigationItems.forEach { screen ->
            val isSelected = remember(currentRoute, screen.route) {
                isRouteSelected(currentRoute, screen.route, navigationItems)
            }
            val currentIsSelected by rememberUpdatedState(isSelected)
            val iconRes = remember(isSelected, screen) {
                if (isSelected) screen.iconIdActive else screen.iconIdInactive
            }

            val isSearchItem = screen == Screens.Search && onSearchLongClick != null
            val interactionSource = remember { MutableInteractionSource() }

            if (isSearchItem) {
                LaunchedEffect(interactionSource) {
                    var isLongClick = false
                    interactionSource.interactions.collectLatest { interaction ->
                        when (interaction) {
                            is PressInteraction.Press -> {
                                isLongClick = false
                                delay(viewConfiguration.longPressTimeoutMillis)
                                isLongClick = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSearchLongClick.invoke()
                            }
                            is PressInteraction.Release -> {
                                if (!isLongClick) {
                                    onItemClick(screen, currentIsSelected)
                                }
                            }
                            is PressInteraction.Cancel -> {
                                isLongClick = false
                            }
                        }
                    }
                }
            }

            NavigationRailItem(
                selected = isSelected,
                onClick = {
                    if (!isSearchItem) {
                        onItemClick(screen, currentIsSelected)
                    }
                },
                interactionSource = interactionSource,
                icon = {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = stringResource(screen.titleId)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

// ----------------------------------------------------
// Main Navigation Bar Router
// ----------------------------------------------------
@Composable
fun AppNavigationBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    slimNav: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null,
    backdrop: com.kyant.backdrop.Backdrop? = null,
    hazeState: HazeState? = null,
) {
    val (useFloatingNavBar) = rememberPreference(UseFloatingNavBarKey, defaultValue = true)

    if (useFloatingNavBar) {
        FloatingAppNavigationBar(
            navigationItems = navigationItems,
            currentRoute = currentRoute,
            onItemClick = onItemClick,
            modifier = modifier,
            pureBlack = pureBlack,
            slimNav = slimNav,
            onSearchLongClick = onSearchLongClick,
            backdrop = backdrop,
            hazeState = hazeState,
        )
    } else {
        StandardAppNavigationBar(
            navigationItems = navigationItems,
            currentRoute = currentRoute,
            onItemClick = onItemClick,
            modifier = modifier,
            pureBlack = pureBlack,
            slimNav = slimNav,
            onSearchLongClick = onSearchLongClick
        )
    }
}

// ----------------------------------------------------
// Premium MD3 Liquid Navigation Bar (Squash & Stretch)
// ----------------------------------------------------
@Composable
private fun FloatingAppNavigationBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    slimNav: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null,
    backdrop: com.kyant.backdrop.Backdrop? = null,
    hazeState: HazeState? = null,
) {
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current

    val (glassEnabled) = rememberPreference(
        EnableGlassFloatingNavBarKey,
        defaultValue = false,
    )

    val glassLayer = rememberGraphicsLayer()
    val luminanceAnimation = remember { Animatable(0.5f) }

    // Sample the recorded backdrop periodically so the glass surface can adapt to the artwork
    // behind it. Bright backgrounds get a denser scrim; dark backgrounds stay more transparent.
    LaunchedEffect(glassLayer, glassEnabled, backdrop) {
        if (!glassEnabled || backdrop == null) {
            luminanceAnimation.snapTo(0.5f)
            return@LaunchedEffect
        }
        val buffer = IntBuffer.allocate(25)
        while (isActive) {
            try {
                withContext(Dispatchers.Default) {
                    val bitmap = glassLayer.toImageBitmap().asAndroidBitmap()
                        .scale(5, 5, false)
                        .copy(Bitmap.Config.ARGB_8888, false)
                    buffer.rewind()
                    bitmap.copyPixelsToBuffer(buffer)
                }
                var total = 0.0
                for (i in 0 until 25) {
                    val color = buffer.get(i)
                    val r = ((color shr 16) and 0xFF) / 255.0
                    val g = ((color shr 8) and 0xFF) / 255.0
                    val b = (color and 0xFF) / 255.0
                    total += 0.2126 * r + 0.7152 * g + 0.0722 * b
                }
                luminanceAnimation.animateTo(
                    total.div(25.0).coerceIn(0.25, 0.85).toFloat(),
                    animationSpec = androidx.compose.animation.core.tween(450),
                )
            } catch (_: Throwable) {
                // Keep the last good luminance if a frame cannot be sampled.
            }
            delay(700.milliseconds)
        }
    }

    val searchItem = navigationItems.find { it == Screens.Search }
    val mainItems = navigationItems.filter { it != Screens.Search }

    val selectedMainIndex = mainItems.indexOfFirst { screen ->
        isRouteSelected(currentRoute, screen.route, navigationItems)
    }

    var lastMainIndex by remember { mutableIntStateOf(maxOf(0, selectedMainIndex)) }
    LaunchedEffect(selectedMainIndex) {
        if (selectedMainIndex >= 0) lastMainIndex = selectedMainIndex
    }

    val barHeight = if (slimNav) 48.dp else 56.dp
    val fabSize = if (slimNav) 48.dp else 56.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val searchItem = navigationItems.find { it == Screens.Search }
        val fabSize = if (slimNav) 48.dp else 56.dp
        val searchSpace = if (searchItem != null) 16.dp + fabSize else 0.dp
        val glassBarWidth = (maxWidth - searchSpace).coerceAtLeast(0.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mainItems.isNotEmpty()) {
                MaterialLiquidTabBar(
                    tabs = mainItems,
                    selectedIndex = lastMainIndex,
                    isMainTabActive = selectedMainIndex >= 0,
                    currentRoute = currentRoute,
                    navigationItems = navigationItems,
                    pureBlack = pureBlack,
                    slimNav = slimNav,
                    barHeight = if (slimNav) 56.dp else 64.dp,
                    onItemClick = onItemClick,
                    backdrop = if (glassEnabled) backdrop else null,
                    glassEnabled = glassEnabled,
                    glassLayer = glassLayer,
                    luminance = luminanceAnimation.value,
                    availableWidth = glassBarWidth,
                )
            }

            if (searchItem != null) {
                Spacer(modifier = Modifier.width(16.dp))

                val isSearchSelected = remember(currentRoute, searchItem.route) {
                    isRouteSelected(currentRoute, searchItem.route, navigationItems)
                }
                val currentIsSearchSelected by rememberUpdatedState(isSearchSelected)
                val interactionSource = remember { MutableInteractionSource() }

                if (onSearchLongClick != null) {
                    LaunchedEffect(interactionSource) {
                        var isLongClick = false
                        interactionSource.interactions.collectLatest { interaction ->
                            when (interaction) {
                                is PressInteraction.Press -> {
                                    isLongClick = false
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    isLongClick = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSearchLongClick.invoke()
                                }
                                is PressInteraction.Release -> {
                                    if (!isLongClick) {
                                        onItemClick(searchItem, currentIsSearchSelected)
                                    }
                                }
                                is PressInteraction.Cancel -> isLongClick = false
                            }
                        }
                    }
                }

                if (glassEnabled && backdrop != null) {
                    val searchInteraction = rememberGlassInteraction()

                    Box(
                        modifier = Modifier
                            .size(fabSize)
                            .drawInteractiveGlass(
                                isDark = pureBlack || androidx.compose.foundation.isSystemInDarkTheme(),
                                backdrop = backdrop,
                                layer = glassLayer,
                                luminanceAnimation = luminanceAnimation.value,
                                shape = CircleShape,
                                interaction = searchInteraction,
                            )
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) {
                                if (onSearchLongClick == null) {
                                    onItemClick(searchItem, currentIsSearchSelected)
                                }
                            }
                            .then(searchInteraction.gestureModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isSearchSelected) {
                                    searchItem.iconIdActive
                                } else {
                                    searchItem.iconIdInactive
                                },
                            ),
                            contentDescription = stringResource(searchItem.titleId),
                            modifier = Modifier.size(24.dp),
                            tint = if (isSearchSelected) {
                                floatingToolbarSelectedItemContentColor(pureBlack)
                            } else {
                                floatingToolbarFabContentColor(pureBlack)
                            },
                        )
                    }
                } else {
                    Surface(
                        onClick = {
                            if (onSearchLongClick == null) {
                                onItemClick(searchItem, currentIsSearchSelected)
                            }
                        },
                        interactionSource = interactionSource,
                        shape = CircleShape,
                        color = if (isSearchSelected) {
                            floatingToolbarSelectedItemContainerColor(pureBlack)
                        } else {
                            floatingToolbarFabContainerColor(pureBlack)
                        },
                        contentColor = if (isSearchSelected) {
                            floatingToolbarSelectedItemContentColor(pureBlack)
                        } else {
                            floatingToolbarFabContentColor(pureBlack)
                        },
                        shadowElevation = 12.dp,
                        modifier = Modifier.size(fabSize),
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isSearchSelected) {
                                    searchItem.iconIdActive
                                } else {
                                    searchItem.iconIdInactive
                                },
                            ),
                            contentDescription = stringResource(searchItem.titleId),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialLiquidTabBar(
    tabs: List<Screens>,
    selectedIndex: Int,
    isMainTabActive: Boolean,
    currentRoute: String?,
    navigationItems: List<Screens>,
    pureBlack: Boolean,
    slimNav: Boolean,
    barHeight: androidx.compose.ui.unit.Dp,
    onItemClick: (Screens, Boolean) -> Unit,
    backdrop: com.kyant.backdrop.Backdrop? = null,
    glassEnabled: Boolean = false,
    glassLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    luminance: Float = 0.5f,
    availableWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
) {
    if (tabs.isEmpty()) return

    if (glassEnabled && backdrop != null) {
        LiquidGlassTabBar(
            tabs = tabs,
            selectedTab = selectedIndex.coerceIn(0, tabs.lastIndex),
            backdrop = backdrop,
            layer = glassLayer,
            luminance = luminance,
            pureBlack = pureBlack,
            modifier = Modifier,
            availableWidth = availableWidth,
            onTabSelected = { index ->
                val screen = tabs[index]
                val selected = isRouteSelected(currentRoute, screen.route, navigationItems)
                onItemClick(screen, selected)
            },
        )
    } else {
        // Keep the existing floating navigation appearance when the Glass Floating
        // Navigation Bar preference is disabled.
        Box(
            modifier = Modifier
                .height(barHeight)
                .width(
                    if (availableWidth != Dp.Unspecified && availableWidth > 0.dp) {
                        availableWidth.coerceAtMost(480.dp)
                    } else {
                        (80.dp * tabs.size)
                    },
                )
                .clip(RoundedCornerShape(50))
                .background(floatingToolbarContainerColor(pureBlack)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { screen ->
                    val selected = isRouteSelected(currentRoute, screen.route, navigationItems)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                            ) {
                                onItemClick(screen, selected)
                            },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (selected) screen.iconIdActive else screen.iconIdInactive,
                            ),
                            contentDescription = stringResource(screen.titleId),
                            tint = if (selected) {
                                floatingToolbarSelectedItemContentColor(pureBlack)
                            } else {
                                floatingToolbarItemContentColor(pureBlack)
                            },
                            modifier = Modifier.size(24.dp),
                        )
                        if (!slimNav) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(screen.titleId),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    floatingToolbarSelectedItemContentColor(pureBlack)
                                } else {
                                    floatingToolbarItemContentColor(pureBlack)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// Standard Old Navigation Bar
// ----------------------------------------------------
@Composable
private fun StandardAppNavigationBar(
    navigationItems: List<Screens>,
    currentRoute: String?,
    onItemClick: (Screens, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean = false,
    slimNav: Boolean = false,
    onSearchLongClick: (() -> Unit)? = null
) {
    val containerColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (pureBlack) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current

    NavigationBar(
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        navigationItems.forEach { screen ->
            val isSelected = remember(currentRoute, screen.route) {
                isRouteSelected(currentRoute, screen.route, navigationItems)
            }
            val currentIsSelected by rememberUpdatedState(isSelected)
            val iconRes = remember(isSelected, screen) {
                if (isSelected) screen.iconIdActive else screen.iconIdInactive
            }

            val isSearchItem = screen == Screens.Search && onSearchLongClick != null
            val interactionSource = remember { MutableInteractionSource() }

            if (isSearchItem) {
                LaunchedEffect(interactionSource) {
                    var isLongClick = false
                    interactionSource.interactions.collectLatest { interaction ->
                        when (interaction) {
                            is PressInteraction.Press -> {
                                isLongClick = false
                                delay(viewConfiguration.longPressTimeoutMillis)
                                isLongClick = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSearchLongClick.invoke()
                            }
                            is PressInteraction.Release -> {
                                if (!isLongClick) {
                                    onItemClick(screen, currentIsSelected)
                                }
                            }
                            is PressInteraction.Cancel -> {
                                isLongClick = false
                            }
                        }
                    }
                }
            }

            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (!isSearchItem) {
                        onItemClick(screen, currentIsSelected)
                    }
                },
                interactionSource = interactionSource,
                icon = {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = stringResource(screen.titleId)
                    )
                },
                label = if (!slimNav) {
                    {
                        Text(
                            text = stringResource(screen.titleId),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else null
            )
        }
    }
}

// ----------------------------------------------------
// Color Providers
// ----------------------------------------------------
@Composable
private fun floatingToolbarContainerColor(pureBlack: Boolean): Color = if (pureBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainer
@Composable
private fun floatingToolbarFabContainerColor(pureBlack: Boolean): Color = if (pureBlack) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.tertiaryContainer
@Composable
private fun floatingToolbarFabContentColor(pureBlack: Boolean): Color = if (pureBlack) Color.White else MaterialTheme.colorScheme.onTertiaryContainer
@Composable
private fun floatingToolbarSelectedItemContainerColor(pureBlack: Boolean): Color = if (pureBlack) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.secondaryContainer
@Composable
private fun floatingToolbarSelectedItemContentColor(pureBlack: Boolean): Color = if (pureBlack) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
@Composable
private fun floatingToolbarItemContentColor(pureBlack: Boolean): Color = if (pureBlack) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
