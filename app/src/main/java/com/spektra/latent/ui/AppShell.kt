package com.spektra.latent.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.spektra.latent.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ThemeMode(val label: String, val icon: Glyph) {
    Auto("跟随系统", Glyph.Auto), Light("亮", Glyph.Sun), Dark("暗", Glyph.Moon);

    fun light(systemDark: Boolean) = this == Light || this == Auto && !systemDark

    companion object {
        fun saved(context: Context): ThemeMode =
            runCatching { valueOf(context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", "Auto")!!) }.getOrDefault(Auto)
    }
}

/** The distance between floating glass and the edge of the screen. */
val GAP = 10.dp
private val RAIL = 76.dp
private val RAIL_OPEN = 272.dp

/**
 * What the shell and the pages share: the sidebar, the theme, and how much of
 * the left side the sidebar takes on a wide screen.
 */
@Stable
class Shell internal constructor(private val prefs: SharedPreferences) {
    var theme by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", "Auto")!!) }.getOrDefault(ThemeMode.Auto))
        internal set
    var reduced by mutableStateOf(prefs.getBoolean("reduceMotion", false))
        private set

    /** A wide screen keeps the sidebar: it folds to a rail instead of leaving. */
    var docked by mutableStateOf(false)
        internal set
    var navOpen by mutableStateOf(false)
        internal set
    /** Room at the left that the docked sidebar takes from every page. Read where it is used: it moves as the sidebar folds. */
    val insetLeft: Dp get() = inset.value
    internal var inset: State<Dp> = mutableStateOf(0.dp)

    /**
     * A page whose ground is not the theme's page colour says which scheme its chrome is drawn in: a viewfinder
     * is dark whatever the theme, a lit light table is light. Null: the theme decides.
     */
    var forced by mutableStateOf<Boolean?>(null)
        internal set

    /** How far the sidebar has come out of its capsule, 0..1. */
    internal val nav = Animatable(0f)
    /** The width of the capsule the sidebar pours out of. */
    internal var capsule by mutableIntStateOf(0)
    internal var onTheme: (ThemeMode, Offset?) -> Unit = { _, _ -> }
    internal var onSplash: () -> Unit = {}

    /** [origin]: where on the screen the change was asked for; the new theme spreads from there. */
    fun changeTheme(mode: ThemeMode, origin: Offset? = null) = onTheme(mode, origin)

    fun changeReduced(on: Boolean) {
        reduced = on
        prefs.edit().putBoolean("reduceMotion", on).apply()
    }

    fun openNav() {
        navOpen = true
        if (docked) prefs.edit().putBoolean("navExpanded", true).apply()
    }

    fun closeNav() {
        navOpen = false
        if (docked) prefs.edit().putBoolean("navExpanded", false).apply()
    }

    fun toggleNav() = if (navOpen) closeNav() else openNav()

    /** Shows the splash once more. */
    fun replaySplash() = onSplash()
}

val LocalShell = staticCompositionLocalOf<Shell> { error("no shell") }

/** One line of the sidebar. [badge] is a live reading shown at its right: the film in the camera, how many frames. */
class Place(val screen: Screen, val icon: Glyph, val label: String, val badge: String? = null)

internal tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Says which scheme this page's chrome is drawn in, whatever the theme: true for a bright ground (a lit light
 * table), false for a dark one (a viewfinder). The sidebar and the sheets over the page follow.
 */
@Composable
fun PageScheme(light: Boolean) {
    val shell = LocalShell.current
    DisposableEffect(light) {
        shell.forced = light
        onDispose { if (shell.forced == light) shell.forced = null }
    }
}

/**
 * Says whether the clock and the battery are drawn dark, for a page whose top
 * is not the colour of the theme's page (a viewfinder, a lit table).
 */
@Composable
fun StatusBarIcons(dark: Boolean) {
    val context = LocalContext.current
    val light = Ink.light
    DisposableEffect(dark, light) {
        val window = context.activity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.isAppearanceLightStatusBars = dark
        onDispose { bars?.isAppearanceLightStatusBars = Ink.light }
    }
}

/**
 * The shell: the page fills the whole window, and every piece of chrome
 * floats over it as glass. The sidebar and the sheets are drawn here, outside
 * the page, so that they can look through all of it.
 *
 * @param places the sidebar's lines; the last one is kept at the bottom
 * @param current which of them the person is in
 */
@Composable
fun AppShell(
    places: List<Place>,
    current: Screen,
    live: Boolean,
    onPlace: (Screen) -> Unit,
    onSplash: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    val shell = remember { Shell(prefs) }
    val overlays = remember { Overlays() }
    val backdrop = rememberBackdrop(live)
    shell.onSplash = onSplash

    val systemDark = isSystemInDarkTheme()
    val themed = shell.theme.light(systemDark)
    val light = shell.forced ?: themed
    SideEffect { Ink.light = light }
    // a page that follows the theme takes it back at once
    LaunchedEffect(current) { if (current == Screen.Library || current == Screen.Settings) shell.forced = null }
    DisposableEffect(light) {
        val window = context.activity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.isAppearanceLightStatusBars = light
        bars?.isAppearanceLightNavigationBars = light
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (light) 0xFFEDE7DA.toInt() else 0xFF08080A.toInt()))
        onDispose {}
    }

    // ---- wide screens keep the sidebar
    val configuration = LocalConfiguration.current
    // A phone in landscape needs its width for the photograph. Dock only
    // when the window has enough room in both directions.
    val wide = configuration.screenWidthDp >= 900 && configuration.screenHeightDp >= 600
    LaunchedEffect(wide) {
        shell.docked = wide
        shell.navOpen = wide && prefs.getBoolean("navExpanded", true)
        shell.nav.snapTo(if (shell.navOpen || wide) 1f else 0f)
    }
    val reduced = shell.reduced || animationsOff(context)
    LaunchedEffect(shell.navOpen, shell.docked) {
        if (shell.docked) shell.nav.snapTo(1f)
        else shell.nav.animateTo(if (shell.navOpen) 1f else 0f, tween(if (reduced) 0 else 620, easing = Motion.Ease))
    }
    // (the pages read the moving value itself; handed over through an effect it would only arrive when the shell
    // happened to be composed again, and the pages would stay under the sidebar)
    shell.inset = animateDpAsState(
        if (!shell.docked) 0.dp else if (shell.navOpen) RAIL_OPEN + GAP else RAIL + GAP,
        tween(if (reduced) 0 else 550, easing = Motion.Ease), label = "inset",
    )
    BackHandler(shell.navOpen && !shell.docked) { shell.closeNav() }

    // ---- a new theme spreads from the control that asked for it
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val reveal = remember { Animatable(1f) }
    shell.onTheme = { next, from ->
        if (next != shell.theme) {
            val changes = shell.forced == null && next.light(systemDark) != themed
            prefs.edit().putString("theme", next.name).apply()
            if (!changes || reduced) {
                shell.theme = next
            } else {
                capture(view, context.activity()) { before ->
                    origin = from ?: Offset(view.width / 2f, view.height / 2f)
                    shot = before
                    shell.theme = next
                    scope.launch {
                        reveal.snapTo(0f)
                        reveal.animateTo(1f, tween(640, easing = Motion.Ease))
                        shot = null
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        // ---- the page
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            CompositionLocalProvider(
                LocalShell provides shell,
                LocalOverlays provides overlays,
                LocalBackdrop provides null,
                LocalNavigation provides shell::openNav,
                LocalReducedMotion provides reduced,
            ) { content() }
        }
        // ---- over the page: the sidebar, the sheets, the toasts
        CompositionLocalProvider(
            LocalShell provides shell,
            LocalOverlays provides overlays,
            LocalBackdrop provides backdrop,
            LocalReducedMotion provides reduced,
        ) {
            Sidebar(shell, places, current, onPlace)
            OverlayHost(overlays)
        }
        shot?.let { before ->
            Canvas(Modifier.fillMaxSize()) {
                val r = hypot(max(origin.x, size.width - origin.x), max(origin.y, size.height - origin.y)) * reveal.value
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(Rect(origin.x - r, origin.y - r, origin.x + r, origin.y + r))
                }
                clipPath(outside) { drawImage(before, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            }
        }
    }
}

/** A picture of the window as it is now, surfaces included; [done] is called with it on the main thread. */
private fun capture(view: android.view.View, activity: Activity?, done: (ImageBitmap?) -> Unit) {
    if (view.width <= 0 || view.height <= 0) return done(null)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val window = activity?.window
    if (window == null) {
        view.draw(AndroidCanvas(bitmap))
        return done(bitmap.asImageBitmap())
    }
    try {
        PixelCopy.request(window, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) view.draw(AndroidCanvas(bitmap))
            done(bitmap.asImageBitmap())
        }, Handler(Looper.getMainLooper()))
    } catch (e: IllegalArgumentException) {
        view.draw(AndroidCanvas(bitmap))
        done(bitmap.asImageBitmap())
    }
}

// ------------------------------------------------------------------ the top capsules

/**
 * The two capsules at the top of every page. The left one says where the
 * person is ([title] and a reading under it, or [tabs]) and carries the mark,
 * which is the handle of the sidebar; the right one holds what can be done
 * here. A page puts this over whatever its glass looks through.
 */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    title: String = "",
    sub: String = "",
    subInk: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    tabs: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val shell = LocalShell.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = GAP + shell.insetLeft + leftInset(), end = GAP + rightInset(), top = safeTopInset() + GAP)
            .height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .widthIn(max = 500.dp)
                // the capsule hands over to the sidebar while the sidebar is out
                .graphicsLayer { alpha = if (!shell.docked && shell.nav.value > 0f) 0f else 1f }
                .onSizeChanged { shell.capsule = it.width }
                .glass(27.dp, shadow = false)
                .height(54.dp)
                .padding(start = if (!shell.docked) 5.dp else if (tabs != null) 6.dp else 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!shell.docked) AppMark(shell::openNav)
            if (tabs != null) {
                Gap(width = 2.dp)
                tabs()
            } else {
                Column(Modifier.padding(start = 5.dp, end = 14.dp), verticalArrangement = Arrangement.Center) {
                    Txt(title, Type.body.copy(fontSize = 15.5.sp, letterSpacing = .04.em, lineHeight = 19.sp), color = Ink.GlassText)
                    if (sub.isNotEmpty()) Txt(sub, Type.numMicro.copy(lineHeight = 14.sp), color = if (subInk != androidx.compose.ui.graphics.Color.Unspecified) subInk else Ink.GlassDim)
                }
            }
        }
        if (actions != null) {
            Row(
                Modifier.padding(start = 8.dp).glass(27.dp, shadow = false).height(54.dp).padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** The height the top capsules take, status bar included: what a page keeps clear of. */
@Composable
fun topInset(): Dp = safeTopInset() + GAP + 54.dp + 10.dp

@Composable
private fun safeTopInset(): Dp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

/** The height of the system's navigation bar or gesture line at the bottom. */
@Composable
fun bottomInset(): Dp = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

/** Space occupied by a landscape cutout or a system navigation bar at either side. */
@Composable
fun leftInset(): Dp = WindowInsets.safeDrawing.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr)

@Composable
fun rightInset(): Dp = WindowInsets.safeDrawing.asPaddingValues().calculateRightPadding(LayoutDirection.Ltr)

// ------------------------------------------------------------------ the sidebar

@Composable
private fun Sidebar(shell: Shell, places: List<Place>, current: Screen, onPlace: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val docked = shell.docked
        val width by animateDpAsState(
            if (!docked) min(maxWidth.value - 2 * GAP.value - 44f, 316f).dp else if (shell.navOpen) RAIL_OPEN else RAIL,
            tween(if (LocalReducedMotion.current || !docked) 0 else 550, easing = Motion.Ease), label = "side",
        )
        val widthPx = with(density) { width.toPx() }
        val out by remember { derivedStateOf { shell.nav.value > 0f } }

        if (!docked) {
            // pulled out from the left edge, following the finger
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(top = 90.dp)
                    .width(14.dp)
                    .fillMaxHeight()
                    .systemGestureExclusion()
                    .pointerInput(widthPx) {
                        var pulled = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { pulled = 0f },
                            onDragEnd = {
                                if (shell.nav.value > .4f) shell.openNav() else scope.launch { shell.nav.animateTo(0f, tween(300, easing = Motion.Ease)) }
                            },
                        ) { change, dx ->
                            change.consume()
                            pulled += dx
                            scope.launch { shell.nav.snapTo((pulled / (widthPx * .7f)).coerceIn(0f, 1f)) }
                        }
                    },
            )
            if (out) {
                val scrim = Ink.Scrim
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind { drawRect(scrim.copy(alpha = scrim.alpha * shell.nav.value.coerceIn(0f, 1f))) }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { shell.closeNav() },
                )
            }
        }
        if (docked || out) {
            Box(
                Modifier
                    .padding(start = GAP + leftInset(), end = GAP + rightInset(), top = safeTopInset() + GAP, bottom = GAP + bottomInset())
                    .width(width)
                    .fillMaxHeight()
                    .then(
                        if (docked) Modifier else Modifier
                            // the plate pours out of the capsule at the top left, and folds back into it
                            .drawWithContent {
                                val k = shell.nav.value.coerceIn(0f, 1f)
                                val w0 = shell.capsule.toFloat().coerceIn(54.dp.toPx(), size.width)
                                val shape = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            0f, 0f, w0 + (size.width - w0) * k, 54.dp.toPx() + (size.height - 54.dp.toPx()) * k,
                                            CornerRadius(27.dp.toPx()),
                                        ),
                                    )
                                }
                                clipPath(shape) { this@drawWithContent.drawContent() }
                            }
                            .pointerInput(widthPx) {
                                var pushed = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { pushed = 0f },
                                    onDragEnd = {
                                        if (shell.nav.value > .4f) scope.launch { shell.nav.animateTo(1f, tween(300, easing = Motion.Ease)) } else shell.closeNav()
                                    },
                                ) { change, dx ->
                                    pushed += dx
                                    if (pushed < 0f) {
                                        change.consume()
                                        scope.launch { shell.nav.snapTo((1f + pushed / (widthPx * .7f)).coerceIn(0f, 1f)) }
                                    }
                                }
                            },
                    ),
            ) {
                SidePlate(shell, places, current, onPlace)
            }
        }
    }
}

@Composable
private fun SidePlate(shell: Shell, places: List<Place>, current: Screen, onPlace: (Screen) -> Unit) {
    val docked = shell.docked
    val open = shell.navOpen
    // on a rail there is only room for the icons
    val words by animateFloatAsState(if (!docked || open) 1f else 0f, tween(if (open) 350 else 200, delayMillis = if (open && docked) 120 else 0), label = "words")
    var opened by remember { mutableIntStateOf(0) }
    LaunchedEffect(open) { if (open) opened++ }

    BoxWithConstraints(Modifier.fillMaxSize().glass(27.dp, GlassTone.Plate, shadow = docked)) {
        val compact = maxHeight < 480.dp
        val rowHeight = if (compact) 46.dp else 52.dp
        // Short windows scroll the complete menu, including appearance and
        // settings. A fixed footer must not consume the space of the places.
        Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            // ---- the mark and the name
            Row(Modifier.height(54.dp).padding(start = if (docked) 16.dp else 5.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppMark(shell::toggleNav, label = if (open) "收起导航" else "展开导航")
                Column(
                    Modifier.padding(start = 6.dp).graphicsLayer {
                        // the name leaves first when the plate folds back, so the capsule's own label can take over
                        alpha = if (docked) words else ((shell.nav.value - .55f) / .45f).coerceIn(0f, 1f)
                    },
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    ScrambleText(
                        "SPEKTRAMAT", Type.film.copy(fontSize = 15.sp, letterSpacing = .17.em, lineHeight = 17.sp),
                        color = Ink.GlassText, hot = Ink.Accent, delay = if (docked) 200 else 260, trigger = opened,
                    )
                    Txt("胶片色彩工作站", Type.micro.copy(letterSpacing = .14.em, lineHeight = 14.sp), color = Ink.GlassDim)
                }
            }
            // ---- the places
            val bounds = remember { mutableStateMapOf<Int, Rect>() }
            val at = places.indexOfFirst { it.screen::class == current::class }
            val droplet = rememberDroplet(at, bounds[at], vertical = true)
            BoxWithConstraints(if (compact) Modifier.height(rowHeight * places.size + 30.dp) else Modifier.weight(1f)) {
                // the last place (settings) is kept at the bottom, however few the others are
                val rest = maxHeight - 18.dp - rowHeight * places.size
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(if (compact) Modifier else Modifier.verticalScroll(rememberScrollState()))
                        .padding(start = if (docked) 11.dp else 8.dp, end = if (docked) 11.dp else 8.dp, top = 10.dp, bottom = 8.dp)
                        .drawBehind { drawDroplet(droplet, 17.dp.toPx(), vertical = true) },
                ) {
                    places.forEachIndexed { i, place ->
                        val on = i == at
                        if (i == places.lastIndex) Spacer(Modifier.height(if (compact) 8.dp else if (rest > 24.dp) rest else 24.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .onGloballyPositioned { bounds[i] = it.boundsInParent() }
                                .clip(RoundedCornerShape(17.dp))
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                                    if (!docked) shell.closeNav()
                                    onPlace(place.screen)
                                }
                                .semantics {
                                    selected = on
                                    contentDescription = place.label
                                }
                                .padding(start = if (docked) 16.dp else 15.dp, end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(place.icon, tint = if (on) Ink.GlassText else Ink.GlassDim, size = 22.dp)
                            Txt(
                                place.label, Type.body.copy(fontSize = 15.5.sp, letterSpacing = .04.em),
                                Modifier.weight(1f).padding(start = 14.dp).graphicsLayer { alpha = words },
                                color = if (on) Ink.GlassText else Ink.GlassDim,
                            )
                            if (place.badge != null) {
                                Txt(
                                    place.badge, Type.num.copy(fontSize = 12.sp, letterSpacing = .06.em),
                                    Modifier.graphicsLayer { alpha = words }, color = if (on) Ink.Accent else Ink.Faint,
                                )
                            }
                        }
                    }
                }
            }
            // ---- the theme, and what is running underneath
            Hairline()
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = if (compact) 6.dp else 10.dp, bottom = if (compact) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
                if (!docked || open) {
                    var touched by remember { mutableStateOf<Offset?>(null) }
                    var corner by remember { mutableStateOf(Offset.Zero) }
                    Seg(
                        ThemeMode.entries.map { Opt(it.name, it.label, it.icon, text = it == ThemeMode.Auto, fit = it == ThemeMode.Auto) },
                        shell.theme.name,
                        { shell.changeTheme(ThemeMode.valueOf(it), touched) },
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { corner = it.positionInRoot() }
                            .pointerInput(Unit) {
                                awaitEachGesture { touched = corner + awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
                            },
                        style = SegStyle.Small, block = true,
                    )
                } else {
                    var corner by remember { mutableStateOf(Offset.Zero) }
                    IconButton(
                        shell.theme.icon, "外观：${shell.theme.label}",
                        { shell.changeTheme(ThemeMode.entries[(shell.theme.ordinal + 1) % ThemeMode.entries.size], corner) },
                        Modifier.align(Alignment.CenterHorizontally).onGloballyPositioned { corner = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (!docked || open) Arrangement.Start else Arrangement.Center) {
                    Lamp()
                    if (!docked || open) {
                        Txt("光谱胶片引擎", Type.micro, Modifier.weight(1f).padding(start = 8.dp).graphicsLayer { alpha = words }, color = Ink.GlassDim)
                        Txt(BuildConfig.VERSION_NAME, Type.numMicro, Modifier.graphicsLayer { alpha = words }, color = Ink.Faint)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the splash

/**
 * The name of the app standing out of the character matrix while the film
 * data is read. It is the one thing here that moves without being asked.
 * A tap sends it away early.
 */
@Composable
fun Splash(visible: Boolean, onTap: () -> Unit) {
    val k by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 0 else 600, easing = Motion.Ease), label = "splash")
    if (k == 0f) return
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = k }
            .background(Ink.Bg)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    ) {
        // ten letters across a phone are set in dots, a cell to a dot: the grid is made just fine enough for them to fit
        val cell = (maxWidth.value * .86f / 61f).coerceIn(4.5f, 9f)
        GlyphField(GlyphSubject.Word("SPEKTRAMAT"), Modifier.fillMaxSize(), cell = cell.dp, fps = 14)
        Txt(
            "胶片色彩工作站", Type.label.copy(letterSpacing = .3.em),
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = maxHeight * .13f), color = Ink.Dim,
        )
    }
}

/** How long the splash stands at least. */
suspend fun splashHold(context: Context) {
    if (!animationsOff(context)) delay(1500)
}
