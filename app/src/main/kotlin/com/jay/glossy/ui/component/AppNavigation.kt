@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.jay.glossy.ui.component

import androidx.compose.animation.animateColorAsState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlin.math.roundToInt

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
                    // Kyant0-style glass surface is a separate layer.
                    // Only this surface deforms; the icon stays at its original size.
                    Box(
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = searchGlassScale
                                scaleY = searchGlassScale
                            }
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { searchShape },
                                effects = {
                                    vibrancy()
                                    colorControls(
                                        brightness = 0.04f,
                                        contrast = 1f,
                                        saturation = 1.35f,
                                    )
                                    blur(8f.dp.toPx())
                                    lens(
                                        18f.dp.toPx(),
                                        24f.dp.toPx(),
                                        chromaticAberration = true,
                                    )
                                },
                                highlight = {
                                    Highlight.Default.copy(
                                        alpha = if (searchPressed) 0.85f else 0.45f
                                    )
                                },
                                shadow = {
                                    Shadow(
                                        radius = 8.dp,
                                        alpha = if (searchPressed) 0.30f else 0.20f,
                                    )
                                },
                                innerShadow = {
                                    InnerShadow(
                                        radius = 7.dp,
                                        alpha = if (searchPressed) 0.45f else 0.25f,
                                    )
                                },
                                onDrawSurface = {
                                    drawRect(
                                        if (pureBlack) {
                                            Color.White.copy(alpha = 0.055f)
                                        } else {
                                            Color.White.copy(alpha = 0.12f)
                                        }
                                    }
                                },
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
) {
    val tabsCount = tabs.size
    if (tabsCount == 0) return

    val tabWidth = if (slimNav) 64.dp else 80.dp
    val blobHeight = if (slimNav) 36.dp else 44.dp
    val tabWidthPx = with(LocalDensity.current) { tabWidth.toPx() }
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
            initialValue = selectedIndex.coerceAtLeast(0).toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // Keep the physical deformation on the glass surface.
            // The actual tab content never receives this scale.
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
                    val isSelected = isRouteSelected(
                        currentRouteState,
                        screen.route,
                        currentNavItems
                    )
                    currentOnItemClick(screen, isSelected)
                }
            },
            onDrag = { _, dragAmount ->
                totalDragDistance[0] += kotlin.math.abs(dragAmount.x)
                if (totalDragDistance[0] > 8f) draggedFlag[0] = true

                updateValue(
                    (
                        targetValue + dragAmount.x / tabWidthPx
                    ).coerceIn(0f, (tabsCount - 1).toFloat())
                )
            }
        )
    }

    LaunchedEffect(selectedIndex) {
        dampedDrag.animateToValue(selectedIndex.toFloat())
    }

    val capsuleShape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .height(barHeight)
            .width(totalWidth)
            // Drag belongs to the complete pill, not the content.
            .then(dampedDrag.modifier)
            .pointerInput(dampedDrag) {
                // A simple press/hold must also trigger the glass deformation.
                detectPress {
                    dampedDrag.press()
                }
                dampedDrag.release()
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        /*
         * IMPORTANT:
         * The glass is a separate visual layer behind the navigation content.
         * Therefore its rubber deformation does NOT scale the text/icons.
         */
        if (glassEnabled && backdrop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val progress = dampedDrag.pressProgress
                        val scale = androidx.compose.ui.util.lerp(1f, 1.075f, progress)

                        // Kyant0-style subtle squash/stretch from drag velocity.
                        val velocity = (dampedDrag.velocity / 10f)
                            .coerceIn(-0.20f, 0.20f)

                        scaleX = scale / (1f - velocity * 0.35f)
                        scaleY = scale * (1f - velocity * 0.10f)
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { capsuleShape },
                        effects = {
                            val progress = dampedDrag.pressProgress

                            vibrancy()
                            colorControls(
                                brightness = 0.04f,
                                contrast = 1f,
                                saturation = 1.45f,
                            )
                            blur(8f.dp.toPx())

                            // The refraction becomes stronger while pressed.
                            lens(
                                20f.dp.toPx() + 8f.dp.toPx() * progress,
                                28f.dp.toPx() + 8f.dp.toPx() * progress,
                                chromaticAberration = true,
                            )
                        },
                        highlight = {
                            val progress = dampedDrag.pressProgress
                            Highlight.Default.copy(
                                alpha = 0.38f + 0.48f * progress
                            )
                        },
                        shadow = {
                            val progress = dampedDrag.pressProgress
                            Shadow(
                                radius = 9.dp + 3.dp * progress,
                                alpha = 0.18f + 0.12f * progress,
                            )
                        },
                        innerShadow = {
                            val progress = dampedDrag.pressProgress
                            InnerShadow(
                                radius = 7.dp + 2.dp * progress,
                                alpha = 0.24f + 0.22f * progress,
                            )
                        },
                        onDrawSurface = {
                            drawRect(
                                if (pureBlack) {
                                    Color.White.copy(alpha = 0.055f)
                                } else {
                                    Color.White.copy(alpha = 0.10f)
                                }
                            )

                            val progress = dampedDrag.pressProgress
                            if (progress > 0f) {
                                drawRect(
                                    Color.White.copy(alpha = 0.035f * progress)
                                )
                            }
                        },
                    )
            )
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .shadow(12.dp, capsuleShape)
                    .clip(capsuleShape)
                    .background(floatingToolbarContainerColor(pureBlack))
            )
        }

        val indicatorOpacity by animateFloatAsState(
            targetValue = if (isMainTabActive) 1f else 0f,
            label = "Opacity"
        )

        // Selected glass capsule.
        // It moves with the drag, but DOES NOT independently scale.
        Box(
            Modifier
                .graphicsLayer {
                    translationX = dampedDrag.value * tabWidthPx
                    alpha = indicatorOpacity
                }
                .width(tabWidth)
                .height(blobHeight)
                .padding(horizontal = 6.dp)
                .then(
                    if (glassEnabled && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(50) },
                            effects = {
                                vibrancy()
                                colorControls(
                                    brightness = 0.05f,
                                    contrast = 1f,
                                    saturation = 1.35f,
                                )
                                blur(10f.dp.toPx())
                                lens(
                                    8f.dp.toPx(),
                                    12f.dp.toPx(),
                                    chromaticAberration = true,
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = 0.58f)
                            },
                            shadow = {
                                Shadow(
                                    radius = 4.dp,
                                    alpha = 0.24f
                                )
                            },
                            innerShadow = {
                                InnerShadow(
                                    radius = 6.dp,
                                    alpha = 0.30f
                                )
                            },
                            onDrawSurface = {
                                drawRect(
                                    if (pureBlack) {
                                        Color.White.copy(alpha = 0.075f)
                                    } else {
                                        Color.White.copy(alpha = 0.12f)
                                    }
                                )
                            },
                        )
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                floatingToolbarSelectedItemContainerColor(pureBlack)
                            )
                    }
                )
        )

        // Navigation content is deliberately NOT attached to the rubber scale.
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { position, screen ->
                val isSelected = remember(currentRouteState, screen.route) {
                    isRouteSelected(
                        currentRouteState,
                        screen.route,
                        currentNavItems
                    )
                }
                val currentIsSelected by rememberUpdatedState(isSelected)

                val iconRes =
                    if (isSelected) screen.iconIdActive else screen.iconIdInactive

                val contentColor =
                    if (isSelected) {
                        floatingToolbarSelectedItemContentColor(pureBlack)
                    } else {
                        floatingToolbarItemContentColor(pureBlack)
                    }

                val animatedColor by animateColorAsState(
                    targetValue = contentColor,
                    label = "Color"
                )

                Column(
                    Modifier
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = {
                                if (!draggedFlag[0]) {
                                    currentOnItemClick(
                                        screen,
                                        currentIsSelected
                                    )
                                }
                            }
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = stringResource(screen.titleId),
                        tint = animatedColor,
                        modifier = Modifier.size(24.dp)
                    )

                    if (!slimNav) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(screen.titleId),
                            style = MaterialTheme.typography.labelMedium,
                            color = animatedColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
Glossy Kyant0-style Liquid Glass floating navigation patch
Source base: Glossy-test-master (6)
Replacement:
app/src/main/kotlin/com/jay/glossy/ui/component/AppNavigation.kt

Uses the existing io.github.kyant0:backdrop:2.0.1 dependency already present in Glossy.

The glass surface is a separate visual layer from the navigation content, so press/rubber deformation does not scale the text/icons. The selected capsule translates but does not independently scale.
Haze is not used for the floating glass surface in this patch; Kyant0 backdrop is the primary renderer.
