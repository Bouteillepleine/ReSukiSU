package com.resukisu.resukisu.ui.activity.component

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailColors
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.resukisu.resukisu.ksuApp
import com.resukisu.resukisu.ui.screen.BottomBarDestination
import com.resukisu.resukisu.ui.theme.CardConfig
import com.resukisu.resukisu.ui.theme.ThemeConfig
import com.resukisu.resukisu.ui.theme.blurEffect
import com.resukisu.resukisu.ui.theme.liquidGlassEffect
import com.resukisu.resukisu.ui.util.LocalHandlePageChange
import com.resukisu.resukisu.ui.util.LocalSelectedPage
import com.resukisu.resukisu.ui.util.getModuleCount
import com.resukisu.resukisu.ui.util.getSuperuserCount
import com.resukisu.resukisu.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("ContextCastToActivity")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NavigationBar(
    destinations: List<BottomBarDestination>,
    isBottomBar: Boolean
) {
    // 是否隐藏 badge
    val homeViewModel = viewModel<HomeViewModel>(viewModelStoreOwner = ksuApp)
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val isHideOtherInfo = uiState.isHideOtherInfo

    // 翻页处理
    val page = LocalSelectedPage.current
    val handlePageChange = LocalHandlePageChange.current

    // 收集计数数据
    var superuserCountSaved by rememberSaveable { mutableIntStateOf(0) }
    var moduleCountSaved by rememberSaveable { mutableIntStateOf(0) }

    val superuserCount by produceState(initialValue = superuserCountSaved) {
        withContext(Dispatchers.IO) {
            value = getSuperuserCount()
            superuserCountSaved = value
        }
    }
    val moduleCount by produceState(initialValue = moduleCountSaved) {
        withContext(Dispatchers.IO) {
            value = getModuleCount()
            moduleCountSaved = value
        }
    }

    if (isBottomBar) {
        FloatingBottomBar(
            destinations = destinations,
            selectedIndex = page,
            onSelect = handlePageChange,
            superuserCount = superuserCount,
            moduleCount = moduleCount,
            isHideOtherInfo = isHideOtherInfo,
        )
    } else {
        WideNavigationRail(
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                )
                .blurEffect(),
            colors = WideNavigationRailColors(
                containerColor =
                    if (ThemeConfig.isEnableBlur)
                        Color.Transparent
                    else
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(CardConfig.cardAlpha),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modalContainerColor = WideNavigationRailDefaults.colors().modalContainerColor,
                modalScrimColor = WideNavigationRailDefaults.colors().modalScrimColor,
                modalContentColor = WideNavigationRailDefaults.colors().modalContentColor,
            ),
        ) {
            destinations.forEachIndexed { index, destination ->
                NavigationRailItem(
                    isSelected = index == page,
                    destination = destination,
                    onClick = {
                        handlePageChange(index)
                    },
                    superuserCount = superuserCount,
                    moduleCount = moduleCount,
                    isHideOtherInfo = isHideOtherInfo,
                )
            }
        }
    }
}

@Composable
private fun NavigationRailItem(
    isSelected: Boolean,
    destination: BottomBarDestination,
    onClick: () -> Unit,
    superuserCount: Int,
    moduleCount: Int,
    isHideOtherInfo: Boolean
) {
    WideNavigationRailItem(
        railExpanded = false,
        selected = isSelected,
        onClick = onClick,
        icon = {
            BadgedBox(
                badge = {
                    DestinationBadge(
                        dest = destination,
                        superUser = superuserCount,
                        module = moduleCount,
                        isHideOtherInfo = isHideOtherInfo,
                    )
                }
            ) {
                if (isSelected) {
                    Icon(destination.iconSelected, stringResource(destination.label))
                } else {
                    Icon(destination.iconNotSelected, stringResource(destination.label))
                }
            }
        },
        label = {
            Text(
                stringResource(destination.label),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        },
    )
}

/**
 * Floating "liquid" bottom bar, ported from KernelSU-Next:
 * a detached capsule with a springy sliding pill indicator that
 * can also be dragged between the destination icons.
 */
@Composable
private fun FloatingBottomBar(
    destinations: List<BottomBarDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    superuserCount: Int,
    moduleCount: Int,
    isHideOtherInfo: Boolean
) {
    if (destinations.isEmpty()) return

    val currentIndex = selectedIndex.coerceIn(0, destinations.lastIndex)

    // Drag state
    var isDraggingPill by remember { mutableStateOf(false) }
    var dragTargetIndex by remember { mutableIntStateOf(currentIndex) }

    // During drag, animate toward dragTargetIndex; otherwise animate toward currentIndex
    val animatedSelectedIndex by animateFloatAsState(
        targetValue = (if (isDraggingPill) dragTargetIndex else currentIndex).toFloat(),
        animationSpec = if (isDraggingPill) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "selectedIndex"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
    ) {
        val screenWidth = maxWidth
        val horizontalScreenPadding = when {
            screenWidth > 600.dp -> 32.dp
            screenWidth > 400.dp -> 24.dp
            else -> 16.dp
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalScreenPadding, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            val capsuleShape = RoundedCornerShape(24.dp)

            Surface(
                modifier = Modifier
                    .wrapContentWidth()
                    .liquidGlassEffect(capsuleShape)
                    // Specular rim. Drawn in both modes so the capsule still
                    // reads as glass when backdrop blur is unavailable.
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.45f),
                                Color.White.copy(alpha = 0.10f),
                                Color.White.copy(alpha = 0.18f)
                            )
                        ),
                        shape = capsuleShape
                    ),
                shape = capsuleShape,
                color =
                    if (ThemeConfig.isEnableBlur)
                        Color.Transparent
                    else
                        // Without a backdrop blur there is nothing to hide the
                        // content scrolling underneath, so keep the frost dense
                        // enough to stay readable.
                        MaterialTheme.colorScheme.surfaceContainerHigh
                            .copy(alpha = CardConfig.cardAlpha.coerceAtLeast(0.82f)),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp
            ) {
                val itemSize = 56.dp
                val itemSpacing = 4.dp
                val containerPadding = 7.dp

                val navBarWidth = (itemSize * destinations.size) +
                        (itemSpacing * (destinations.size - 1)) +
                        (containerPadding * 2)

                val density = LocalDensity.current
                val itemSizePx = with(density) { itemSize.toPx() }
                val itemSpacingPx = with(density) { itemSpacing.toPx() }
                val containerPaddingPx = with(density) { containerPadding.toPx() }

                Box(
                    modifier = Modifier
                        .width(navBarWidth)
                        .height(72.dp)
                        // Curvature sheen: light gathers along the top edge and
                        // falls away toward the bottom, like a glass surface.
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.10f),
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.06f)
                            )
                        )
                        .pointerInput(destinations, currentIndex) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val extraTouchArea = with(density) { 20.dp.toPx() }

                                    val pillLeft = containerPaddingPx +
                                            currentIndex * (itemSizePx + itemSpacingPx) - extraTouchArea

                                    val pillRight = pillLeft + itemSizePx + (extraTouchArea * 2)

                                    if (offset.x in pillLeft..pillRight) {
                                        isDraggingPill = true
                                        dragTargetIndex = currentIndex
                                    }
                                },
                                onDragEnd = {
                                    if (isDraggingPill) {
                                        onSelect(dragTargetIndex)
                                        isDraggingPill = false
                                    }
                                },
                                onDragCancel = {
                                    isDraggingPill = false
                                },
                                onDrag = { change, _ ->
                                    if (isDraggingPill) {
                                        change.consume()
                                        // Map finger X to nearest icon index
                                        val index = ((change.position.x - containerPaddingPx) /
                                                (itemSizePx + itemSpacingPx))
                                            .toInt()
                                            .coerceIn(0, destinations.lastIndex)
                                        dragTargetIndex = index
                                    }
                                }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = containerPadding)
                    ) {
                        // Sliding pill indicator
                        val indicatorOffset = (itemSizePx + itemSpacingPx) * animatedSelectedIndex

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(vertical = 8.dp)
                                .offset {
                                    IntOffset(x = indicatorOffset.toInt(), y = 0)
                                }
                                .width(itemSize)
                                // Subtle scale-up when dragging, like iOS
                                .graphicsLayer {
                                    scaleX = if (isDraggingPill) 1.1f else 1f
                                    scaleY = if (isDraggingPill) 1.1f else 1f
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val pillShape = RoundedCornerShape(16.dp)
                            val pillColor = MaterialTheme.colorScheme.secondaryContainer

                            Box(
                                modifier = Modifier
                                    .size(itemSize)
                                    .clip(pillShape)
                                    // Lit from above: bright crown, base body, shaded floor
                                    .background(
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                lerp(pillColor, Color.White, 0.34f),
                                                pillColor,
                                                lerp(pillColor, Color.Black, 0.20f)
                                            )
                                        )
                                    )
                                    // Glass rim: catches light on the top edge
                                    .border(
                                        width = 1.dp,
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.60f),
                                                Color.White.copy(alpha = 0.06f)
                                            )
                                        ),
                                        shape = pillShape
                                    )
                            )
                        }
                        // Navigation items
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            destinations.forEachIndexed { index, destination ->
                                val isSelected =
                                    index == (if (isDraggingPill) dragTargetIndex else currentIndex)

                                Box(
                                    modifier = Modifier
                                        .size(itemSize)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            if (index != currentIndex) onSelect(index)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BadgedBox(
                                        badge = {
                                            DestinationBadge(
                                                dest = destination,
                                                superUser = superuserCount,
                                                module = moduleCount,
                                                isHideOtherInfo = isHideOtherInfo,
                                            )
                                        }
                                    ) {
                                        DimensionalIcon(
                                            imageVector = destination.solidIcon,
                                            contentDescription = stringResource(destination.label),
                                            baseColor = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            isSelected = isSelected
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Solid counterparts of the destination icons. The outlined TwoTone variants
 * used elsewhere have too little interior area for the lighting in
 * [DimensionalIcon] to sculpt, so the floating bar uses filled glyphs.
 */
private val BottomBarDestination.solidIcon: ImageVector
    get() = when (this) {
        BottomBarDestination.Home -> Icons.Filled.Home
        BottomBarDestination.SuperUser -> Icons.Filled.AdminPanelSettings
        BottomBarDestination.Module -> Icons.Filled.Extension
        BottomBarDestination.Settings -> Icons.Filled.Settings
    }

/**
 * Icon with a sculpted, lit-from-above look: a soft cast shadow underneath for
 * depth, and a vertical light ramp across the glyph so it reads as a raised
 * solid rather than a flat silhouette. Selected icons sit higher (deeper
 * shadow, stronger highlight) than unselected ones.
 */
@Composable
private fun DimensionalIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    baseColor: Color,
    isSelected: Boolean
) {
    val lift by animateDpAsState(
        targetValue = if (isSelected) 3.dp else 2.dp,
        label = "iconLift"
    )
    // Wide crown-to-floor spread is what sells the volume on a solid glyph.
    val crown = lerp(baseColor, Color.White, if (isSelected) 0.85f else 0.6f)
    val floor = lerp(baseColor, Color.Black, if (isSelected) 0.5f else 0.42f)

    Box(contentAlignment = Alignment.Center) {
        // Cast shadow — Modifier.blur is a no-op below API 31, which just
        // leaves a crisper (still valid) shadow on older devices.
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.Black.copy(alpha = if (isSelected) 0.65f else 0.5f),
            modifier = Modifier
                .offset(y = lift)
                .blur(3.5.dp, BlurredEdgeTreatment.Unbounded)
        )
        // Body, shaded top-to-bottom, with a specular cap on the very top edge
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White,
                            0.18f to crown,
                            0.55f to baseColor,
                            1f to floor
                        ),
                        blendMode = BlendMode.SrcAtop
                    )
                }
        )
    }
}

@Composable
private fun DestinationBadge(
    dest: BottomBarDestination,
    superUser: Int,
    module: Int,
    isHideOtherInfo: Boolean
) {
    val count = when (dest) {
        BottomBarDestination.SuperUser -> superUser
        BottomBarDestination.Module -> module
        else -> 0
    }

    AnimatedVisibility(
        visible = count > 0 && !isHideOtherInfo,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Badge(
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Text(count.toString())
        }
    }
}
