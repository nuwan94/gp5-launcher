package com.gp5.launcher.feature.apps

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gp5.launcher.core.data.AppsRepository
import com.gp5.launcher.core.design.AppIcon
import com.gp5.launcher.core.design.DockGlyph
import com.gp5.launcher.core.design.DockSlot
import com.gp5.launcher.core.design.EmptyDockSlot
import com.gp5.launcher.core.design.GpCard
import com.gp5.launcher.core.design.Gp5Dimens
import com.gp5.launcher.core.design.Gp5Motion
import com.gp5.launcher.core.design.Gp5Palette
import com.gp5.launcher.core.design.Gp5Type
import com.gp5.launcher.core.design.IconLoader
import com.gp5.launcher.core.design.LocalGp5Look
import com.gp5.launcher.core.design.R
import com.gp5.launcher.core.design.pressScale
import com.gp5.launcher.core.design.rememberBounds
import com.gp5.launcher.core.design.trackBounds
import com.gp5.launcher.core.model.AppCategory
import com.gp5.launcher.core.model.AppEntry
import com.gp5.launcher.obd.VehicleStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val COLS = 6
private const val ROWS = 3
private const val PAGE_SIZE = COLS * ROWS

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val repo: AppsRepository,
    vehicle: VehicleStateRepository,
) : ViewModel() {
    val apps = repo.state
    val vehicle = vehicle.state
    val icons: IconLoader = { pkg, px -> repo.icon(pkg, px) }

    fun launch(pkg: String, bounds: Rect?) = repo.launch(pkg, bounds)
    fun appInfo(pkg: String, bounds: Rect?) = repo.openAppInfo(pkg, bounds)
    fun addToDock(pkg: String, done: (Boolean) -> Unit = {}) = viewModelScope.launch { done(repo.addToDock(pkg)) }
    fun removeFromDock(pkg: String) = viewModelScope.launch { repo.removeFromDock(pkg) }
    fun hide(pkg: String) = viewModelScope.launch { repo.hide(pkg) }
    fun show(pkg: String) = viewModelScope.launch { repo.show(pkg) }
}

private class ToastMsg(val text: String, val undo: (() -> Unit)? = null)

/**
 * Apps drawer (DESIGN.md section 15, design/apps.html): search, categories, a 6 x 3 paged grid,
 * long-press menu (dock / hide / app info), hidden apps sheet and the dock row with a Home slot.
 */
@Composable
fun AppsScreen(onHome: () -> Unit, vm: AppsViewModel = hiltViewModel()) {
    val s by vm.apps.collectAsStateWithLifecycle()
    val car by vm.vehicle.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(AppCategory.All) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var toast by remember { mutableStateOf<ToastMsg?>(null) }

    val q = query.trim()
    val visible = remember(s.apps, s.hidden, q, category) {
        s.apps.filter { it.packageName !in s.hidden }.filter {
            // Search runs across every category (apps.html)
            if (q.isNotEmpty()) it.label.contains(q, ignoreCase = true)
            else category == AppCategory.All || it.category == category
        }
    }
    val pages = remember(visible) { visible.chunked(PAGE_SIZE) }
    val pager = rememberPagerState { maxOf(1, pages.size) }
    LaunchedEffect(q, category) { pager.scrollToPage(0) }
    LaunchedEffect(toast) { if (toast != null) { delay(4_000); toast = null } }
    BackHandler(enabled = sheetOpen || menuFor != null) { sheetOpen = false; menuFor = null }

    fun remove(app: AppEntry) {
        vm.removeFromDock(app.packageName)
        toast = ToastMsg("Removed ${app.label} from the dock", undo = { vm.addToDock(app.packageName) })
    }

    Box(Modifier.fillMaxSize().clipToBounds()) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(car.isDriving, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                DrivingHint()
            }
            TopBar(
                query = query, onQuery = { query = it },
                category = category, onCategory = { category = it },
                hiddenCount = s.hiddenApps.size, onHidden = { sheetOpen = true },
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.loaded && visible.isEmpty()) {
                    EmptyState(if (q.isNotEmpty()) "No apps match “$q”" else "No apps in ${category.label}", Modifier.align(Alignment.Center))
                } else {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                        AppPage(
                            apps = pages.getOrElse(page) { emptyList() },
                            query = q,
                            enterKey = category,
                            isDocked = s::isDocked,
                            menuFor = menuFor,
                            icons = vm.icons,
                            onLaunch = { app, b -> vm.launch(app.packageName, b) },
                            onMenu = { menuFor = it },
                            onAddToDock = { app ->
                                vm.addToDock(app.packageName) { ok ->
                                    toast = ToastMsg(if (ok) "Added ${app.label} to the dock" else "Dock is full: remove an app first")
                                }
                            },
                            onRemoveFromDock = ::remove,
                            onHide = { app ->
                                vm.hide(app.packageName)
                                toast = ToastMsg("${app.label} hidden", undo = { vm.show(app.packageName) })
                            },
                            onInfo = { app, b -> vm.appInfo(app.packageName, b) },
                        )
                    }
                }
            }
            PageDots(pager, pages.size)
            Row(Modifier.fillMaxWidth().height(Gp5Dimens.Dock), horizontalArrangement = Arrangement.spacedBy(Gp5Dimens.Gutter)) {
                s.dock.forEachIndexed { i, app ->
                    if (app != null) {
                        DockSlot(
                            app.packageName, app.label, vm.icons,
                            onClick = { b -> vm.launch(app.packageName, b) },
                            onLongClick = { remove(app) },
                            phaseDeg = i * 60f,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        EmptyDockSlot(onClick = { toast = ToastMsg("Long-press an app to add it here") }, modifier = Modifier.weight(1f))
                    }
                }
                DockGlyph(R.drawable.ic_rail_home, "Home", onClick = onHome, phaseDeg = 30f, modifier = Modifier.weight(1f))
            }
        }

        ToastHost(toast, onDone = { toast = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = Gp5Dimens.Dock + 16.dp))
        HiddenSheet(
            open = sheetOpen,
            apps = s.hiddenApps,
            icons = vm.icons,
            onShow = { vm.show(it.packageName) },
            onClose = { sheetOpen = false },
        )
    }
}

// ---- Top bar -----------------------------------------------------------------------------------

@Composable
private fun DrivingHint() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).height(30.dp)
            .clip(RoundedCornerShape(15.dp)).background(Gp5Palette.Warn.copy(alpha = 0.12f)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Driving: search is easier when parked", style = Gp5Type.Label.copy(color = Gp5Palette.Warn))
    }
}

@Composable
private fun TopBar(
    query: String, onQuery: (String) -> Unit,
    category: AppCategory, onCategory: (AppCategory) -> Unit,
    hiddenCount: Int, onHidden: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchField(query, onQuery, Modifier.weight(1f))
        CategorySegments(category, dimmed = query.isNotBlank(), onSelect = onCategory)
        Pill(onClick = onHidden) {
            Icon(painterResource(R.drawable.ic_eye_off), null, tint = Gp5Palette.Text2, modifier = Modifier.size(20.dp))
            Text("Hidden ($hiddenCount)", style = Gp5Type.Label)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val look = LocalGp5Look.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface1)
            .border(1.dp, Gp5Palette.Line, RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = Gp5Palette.Text2, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Gp5Type.Body.copy(color = Gp5Palette.Text),
            cursorBrush = SolidColor(look.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Search apps", style = Gp5Type.Body.copy(color = Gp5Palette.Text3))
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { onChange(""); keyboard?.hide() }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_x), "Clear search", tint = Gp5Palette.Text2, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** All / Media / Navigation / Car / Tools with the sliding accent pill (apps.html .seg). */
@Composable
private fun CategorySegments(selected: AppCategory, dimmed: Boolean, onSelect: (AppCategory) -> Unit) {
    val look = LocalGp5Look.current
    val w = 88.dp
    val x by animateDpAsState(w * selected.ordinal, spring(dampingRatio = 0.7f, stiffness = 500f), label = "seg")
    val alpha by animateFloatAsState(if (dimmed) 0.45f else 1f, label = "segAlpha")
    Box(Modifier.graphicsLayer { this.alpha = alpha }.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface1).padding(4.dp)) {
        Box(Modifier.offset(x = x).size(w, 40.dp).clip(RoundedCornerShape(20.dp)).background(look.accentSoft))
        Row {
            AppCategory.entries.forEach { c ->
                Box(Modifier.width(w).height(40.dp).clip(RoundedCornerShape(20.dp)).clickable { onSelect(c) }, contentAlignment = Alignment.Center) {
                    Text(c.label, style = Gp5Type.Label.copy(color = if (c == selected) look.accent else Gp5Palette.Text2))
                }
            }
        }
    }
}

@Composable
private fun Pill(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface1).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

// ---- Grid --------------------------------------------------------------------------------------

@Composable
private fun AppPage(
    apps: List<AppEntry>,
    query: String,
    enterKey: Any,
    isDocked: (String) -> Boolean,
    menuFor: String?,
    icons: IconLoader,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onMenu: (String?) -> Unit,
    onAddToDock: (AppEntry) -> Unit,
    onRemoveFromDock: (AppEntry) -> Unit,
    onHide: (AppEntry) -> Unit,
    onInfo: (AppEntry, Rect?) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        for (r in 0 until ROWS) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until COLS) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val app = apps.getOrNull(r * COLS + c) ?: return@Box
                        val docked = isDocked(app.packageName)
                        AppCell(
                            app = app,
                            query = query,
                            pinned = docked,
                            wave = r + c,
                            enterKey = enterKey,
                            lifted = menuFor == app.packageName,
                            dimmed = menuFor != null && menuFor != app.packageName,
                            icons = icons,
                            onClick = { b -> onMenu(null); onLaunch(app, b) },
                            onLongClick = { onMenu(app.packageName) },
                            onDismiss = { onMenu(null) },
                            menu = { bounds ->
                                MenuItem(if (docked) "Remove from dock" else "Add to dock", R.drawable.ic_pin) {
                                    onMenu(null); if (docked) onRemoveFromDock(app) else onAddToDock(app)
                                }
                                MenuItem("Hide app", R.drawable.ic_eye_off) { onMenu(null); onHide(app) }
                                MenuItem("App info", R.drawable.ic_info) { onMenu(null); onInfo(app, bounds()) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCell(
    app: AppEntry,
    query: String,
    pinned: Boolean,
    wave: Int,
    enterKey: Any,
    lifted: Boolean,
    dimmed: Boolean,
    icons: IconLoader,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    onDismiss: () -> Unit,
    menu: @Composable (bounds: () -> Rect?) -> Unit,
) {
    val look = LocalGp5Look.current
    val source = remember { MutableInteractionSource() }
    val bounds = rememberBounds()
    val press = pressScale(source)
    val lift by animateFloatAsState(if (lifted) 1.12f else 1f, spring(dampingRatio = 0.6f), label = "lift")
    val dim by animateFloatAsState(if (dimmed) 0.35f else 1f, tween(Gp5Motion.Base), label = "dim")

    // Cells enter in a diagonal wave when the category changes (50 ms stagger, apps.html)
    val enter = remember(enterKey) { Animatable(if (look.ambient) 0f else 1f) }
    LaunchedEffect(enterKey) {
        if (enter.value < 1f) {
            delay(wave * Gp5Motion.Stagger.toLong())
            enter.animateTo(1f, tween(Gp5Motion.Base, easing = Gp5Motion.EaseOut))
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .graphicsLayer {
                    val s = press * lift
                    scaleX = s; scaleY = s
                    alpha = dim * enter.value
                    translationY = (1f - enter.value) * 12.dp.toPx()
                }
                .clip(RoundedCornerShape(Gp5Dimens.RadiusCard))
                .combinedClickable(interactionSource = source, indication = null, onLongClick = onLongClick) { onClick(bounds.rect) }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.trackBounds(bounds)) {
                AppIcon(app.packageName, 56.dp, icons)
                if (pinned) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(20.dp).clip(CircleShape).background(look.accent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_pin), "In dock", tint = Color.White, modifier = Modifier.size(12.dp)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                highlight(app.label, query, look.accent),
                style = Gp5Type.Label.copy(color = Gp5Palette.Text, fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(132.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        DropdownMenu(
            expanded = lifted,
            onDismissRequest = onDismiss,
            containerColor = Gp5Palette.Surface2,
            shape = RoundedCornerShape(Gp5Dimens.RadiusCard),
        ) { menu { bounds.rect } }
    }
}

@Composable
private fun MenuItem(text: String, icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = Gp5Type.Body.copy(color = Gp5Palette.Text)) },
        leadingIcon = { Icon(painterResource(icon), null, tint = Gp5Palette.Text2, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(),
        modifier = Modifier.height(Gp5Dimens.TouchMin).width(240.dp),
    )
}

private fun highlight(label: String, q: String, color: Color): AnnotatedString {
    val i = if (q.isEmpty()) -1 else label.indexOf(q, ignoreCase = true)
    if (i < 0) return AnnotatedString(label)
    return buildAnnotatedString {
        append(label.substring(0, i))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(label.substring(i, i + q.length)) }
        append(label.substring(i + q.length))
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_search), null, tint = Gp5Palette.Text3, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, style = Gp5Type.Body.copy(color = Gp5Palette.Text2))
    }
}

/** Page dots: 24 dp targets; the active one stretches to 20 dp in the accent. */
@Composable
private fun PageDots(pager: PagerState, count: Int) {
    val look = LocalGp5Look.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (count > 1) repeat(count) { i ->
            val on = pager.currentPage == i
            val w by animateDpAsState(if (on) 20.dp else 8.dp, spring(dampingRatio = 0.7f), label = "dot")
            Box(Modifier.size(28.dp, 24.dp).clickable { scope.launch { pager.animateScrollToPage(i) } }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(w, 8.dp).clip(CircleShape).background(if (on) look.accent else Gp5Palette.LineStrong))
            }
        }
    }
}

// ---- Overlays ----------------------------------------------------------------------------------

@Composable
private fun ToastHost(toast: ToastMsg?, onDone: () -> Unit, modifier: Modifier) {
    val look = LocalGp5Look.current
    var last by remember { mutableStateOf<ToastMsg?>(null) }
    if (toast != null) last = toast
    AnimatedVisibility(toast != null, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        val t = last ?: return@AnimatedVisibility
        Row(
            Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Gp5Palette.Surface2)
                .border(1.dp, Gp5Palette.LineStrong, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(t.text, style = Gp5Type.Body.copy(color = Gp5Palette.Text))
            t.undo?.let { undo ->
                Text(
                    "Undo",
                    style = Gp5Type.Body.copy(color = look.accent, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { undo(); onDone() }.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun HiddenSheet(open: Boolean, apps: List<AppEntry>, icons: IconLoader, onShow: (AppEntry) -> Unit, onClose: () -> Unit) {
    val look = LocalGp5Look.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(Gp5Motion.Base, easing = Gp5Motion.EaseOut)) { it } + fadeIn(),
            exit = slideOutVertically(tween(Gp5Motion.Fast)) { it } + fadeOut(),
        ) {
            GpCard(
                Modifier.fillMaxWidth().heightIn(max = 400.dp)
                    // swallow taps so they never reach the scrim behind
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hidden apps", style = Gp5Type.Title, color = Gp5Palette.Text)
                            Text("Hidden apps stay installed; they just leave the drawer", style = Gp5Type.Label)
                        }
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_x), "Close", tint = Gp5Palette.Text2, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (apps.isEmpty()) {
                        Text("Nothing hidden. Long-press an app and choose Hide app.", style = Gp5Type.Body.copy(color = Gp5Palette.Text2), modifier = Modifier.padding(vertical = 24.dp))
                    } else {
                        LazyColumn {
                            items(apps, key = { it.packageName }) { app ->
                                Row(Modifier.fillMaxWidth().height(Gp5Dimens.TouchMin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    AppIcon(app.packageName, 40.dp, icons)
                                    Text(app.label, style = Gp5Type.Body.copy(color = Gp5Palette.Text), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Pill(onClick = { onShow(app) }) { Text("Show", style = Gp5Type.Label.copy(color = look.accent)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
