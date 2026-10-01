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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MaterialLiquidTabBar(
            tabs = mainItems,
            selectedIndex = lastMainIndex,
            isMainTabActive = selectedMainIndex >= 0,
            currentRoute = currentRoute,
            navigationItems = navigationItems,
            pureBlack = pureBlack,
            slimNav = slimNav,
            barHeight = barHeight,
            onItemClick = onItemClick,
            backdrop = if (glassEnabled) backdrop else null,
            glassEnabled = glassEnabled,
            glassLayer = glassLayer,
            luminance = luminanceAnimation.value,
        )

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

            var searchPressed by remember { mutableStateOf(false) }
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collectLatest { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> searchPressed = true
                        is PressInteraction.Release,
                        is PressInteraction.Cancel -> searchPressed = false
                    }
                }
            }

            val searchGlassScale by animateFloatAsState(
                targetValue = if (searchPressed) 1.07f else 1f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.62f,
                    stiffness = 520f,
                ),
                label = "SearchLiquidGlassScale",
            )

            val searchShape = CircleShape

            if (glassEnabled && backdrop != null) {
                Box(
                    modifier = Modifier
                        .size(fabSize)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) {
                            if (onSearchLongClick == null) {
                                onItemClick(searchItem, currentIsSearchSelected)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = searchGlassScale
                                scaleY = searchGlassScale
                            }
                            .clip(searchShape)
                            .background(
                                if (pureBlack) {
                                    Color.White.copy(alpha = 0.055f)
                                } else {
                                    Color.White.copy(alpha = 0.12f)
                                }
                            )
                    )

                    Icon(
                        painter = painterResource(
                            id = if (isSearchSelected) {
                                searchItem.iconIdActive
                            } else {
                                searchItem.iconIdInactive
                            }
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
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isSearchSelected) {
                                    searchItem.iconIdActive
                                } else {
                                    searchItem.iconIdInactive
                                }
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
) {
    val tabsCount = tabs.size
    if (tabsCount == 0) return

    val density = LocalDensity.current
    val tabWidth = if (slimNav) 68.dp else 80.dp
    val blobHeight = if (slimNav) 38.dp else 44.dp
    val tabWidthPx = with(density) { tabWidth.toPx() }
    val totalWidth = tabWidth * tabsCount
    val animationScope = rememberCoroutineScope()
    val draggedFlag = remember { booleanArrayOf(false) }
    val totalDragDistance = remember { floatArrayOf(0f) }

    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val currentRouteState by rememberUpdatedState(currentRoute)
    val currentTabs by rememberUpdatedState(tabs)
    val currentNavItems by rememberUpdatedState(navigationItems)

    val dampedDrag = remember(animationScope, tabsCount) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.coerceIn(0, tabsCount - 1).toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.10f,
            onDragStarted = {
                draggedFlag[0] = false
                totalDragDistance[0] = 0f
            },
            onDragStopped = {
                if (draggedFlag[0]) {
                    val target = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    animateToValue(target.toFloat())
                    val screen = currentTabs[target]
                    val isSelected = isRouteSelected(currentRouteState, screen.route, currentNavItems)
                    currentOnItemClick(screen, isSelected)
                }
            },
            onDrag = { _, dragAmount ->
                totalDragDistance[0] += kotlin.math.abs(dragAmount.x)
                if (totalDragDistance[0] > 8f) draggedFlag[0] = true
                updateValue(
                    (targetValue + dragAmount.x / tabWidthPx)
                        .coerceIn(0f, (tabsCount - 1).toFloat()),
                )
            },
        )
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            dampedDrag.animateToValue(selectedIndex.coerceIn(0, tabsCount - 1).toFloat())
        }
    }

    val capsuleShape = RoundedCornerShape(50)
    val selectedShape = RoundedCornerShape(50)
    val isDarkGlass = pureBlack
    val l = (luminance * 2f - 1f).let { sign(it) * it * it }

    Box(
        modifier = Modifier
            .height(barHeight)
            .width(totalWidth)
            .then(dampedDrag.modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (glassEnabled && backdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { capsuleShape },
                        effects = {
                            vibrancy()
                            colorControls(
                                brightness = 0.02f,
                                contrast = 1.02f,
                                saturation = 1.32f,
                            )
                            val blurRadius = if (l >= 0f) {
                                androidx.compose.ui.util.lerp(10.dp.toPx(), 20.dp.toPx(), l)
                            } else {
                                androidx.compose.ui.util.lerp(10.dp.toPx(), 7.dp.toPx(), -l)
                            }
                            blur(blurRadius)
                            lens(
                                size.minDimension * 0.22f,
                                size.minDimension * 0.46f,
                                chromaticAberration = true,
                            )
                        },
                        highlight = { Highlight.Default.copy(alpha = 0.52f) },
                        shadow = { Shadow(radius = 12.dp, alpha = 0.24f) },
                        innerShadow = { InnerShadow(radius = 7.dp, alpha = 0.22f) },
                        onDrawBackdrop = { drawBackdrop ->
                            drawBackdrop()
                            glassLayer?.record { drawBackdrop() }
                        },
                        onDrawSurface = {
                            val normalized = ((luminance - 0.3f) / 0.5f).coerceIn(0f, 1f)
                            val scrim = if (isDarkGlass) {
                                androidx.compose.ui.util.lerp(0.14f, 0.34f, normalized)
                            } else {
                                androidx.compose.ui.util.lerp(0.10f, 0.22f, normalized)
                            }
                            drawRect(
                                (if (isDarkGlass) Color.Black else Color.White).copy(alpha = scrim),
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = if (isDarkGlass) 0.07f else 0.18f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.8.dp.toPx()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    x = size.minDimension * 0.5f,
                                    y = size.minDimension * 0.5f,
                                ),
                            )
                        },
                    ),
            )
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .shadow(12.dp, capsuleShape)
                    .clip(capsuleShape)
                    .background(floatingToolbarContainerColor(pureBlack)),
            )
        }

        val indicatorOpacity by animateFloatAsState(
            targetValue = if (isMainTabActive) 1f else 0f,
            label = "GlassIndicatorOpacity",
        )

        Box(
            Modifier
                .graphicsLayer {
                    translationX = dampedDrag.value * tabWidthPx
                    alpha = indicatorOpacity
                    val velocity = (dampedDrag.velocity / 10f).coerceIn(-0.20f, 0.20f)
                    scaleX = dampedDrag.scaleX / (1f - velocity * 0.30f)
                    scaleY = dampedDrag.scaleY * (1f - velocity * 0.08f)
                }
                .width(tabWidth)
                .height(blobHeight)
                .padding(horizontal = 5.dp)
                .then(
                    if (glassEnabled && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { selectedShape },
                            effects = {
                                vibrancy()
                                colorControls(
                                    brightness = 0.035f,
                                    contrast = 1.03f,
                                    saturation = 1.38f,
                                )
                                blur(14.dp.toPx() + if (l > 0f) 7.dp.toPx() * l else 0f)
                                lens(8.dp.toPx(), 13.dp.toPx(), chromaticAberration = true)
                            },
                            highlight = { Highlight.Default.copy(alpha = 0.68f) },
                            shadow = { Shadow(radius = 6.dp, alpha = 0.30f) },
                            innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.30f) },
                            onDrawSurface = {
                                val normalized = ((luminance - 0.3f) / 0.5f).coerceIn(0f, 1f)
                                val scrim = if (isDarkGlass) {
                                    androidx.compose.ui.util.lerp(0.20f, 0.42f, normalized)
                                } else {
                                    androidx.compose.ui.util.lerp(0.13f, 0.25f, normalized)
                                }
                                drawRect(
                                    (if (isDarkGlass) Color.Black else Color.White).copy(alpha = scrim),
                                )
                                drawRoundRect(
                                    color = Color.White.copy(alpha = if (isDarkGlass) 0.10f else 0.24f),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.7.dp.toPx()),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        x = size.minDimension * 0.5f,
                                        y = size.minDimension * 0.5f,
                                    ),
                                )
                            },
                        )
                    } else {
                        Modifier
                            .clip(selectedShape)
                            .background(floatingToolbarSelectedItemContainerColor(pureBlack))
                    },
                ),
        )

        Row(
            Modifier
                .fillMaxSize()
                .then(dampedDrag.modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { position, screen ->
                val isSelected = isRouteSelected(currentRouteState, screen.route, currentNavItems)
                val color = if (isSelected) {
                    floatingToolbarSelectedItemContentColor(pureBlack)
                } else {
                    floatingToolbarItemContentColor(pureBlack)
                }
                val animatedColor by animateColorAsState(color, label = "GlassTabColor")

                Column(
                    Modifier
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(selectedShape)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                        ) {
                            if (!draggedFlag[0]) {
                                currentOnItemClick(screen, isSelected)
                            }
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isSelected) screen.iconIdActive else screen.iconIdInactive,
                        ),
                        contentDescription = stringResource(screen.titleId),
                        tint = animatedColor,
                        modifier = Modifier.size(24.dp),
                    )
                    if (!slimNav) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(screen.titleId),
                            style = MaterialTheme.typography.labelMedium,
                            color = animatedColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
