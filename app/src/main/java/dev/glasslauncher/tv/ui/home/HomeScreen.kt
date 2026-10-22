package dev.glasslauncher.tv.ui.home

import android.os.Build
import dev.glasslauncher.tv.ui.theme.LocalBackdropImage
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.imageLoader
import coil.request.ImageRequest
import dev.glasslauncher.tv.data.apps.AppImage
import dev.glasslauncher.tv.data.apps.AppInfo
import dev.glasslauncher.tv.data.settings.ShelfMode
import dev.glasslauncher.tv.data.tv.TvSource
import dev.glasslauncher.tv.data.wallpaper.Wallpaper
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import dev.glasslauncher.tv.ui.glass.VideoWallpaper
import dev.glasslauncher.tv.ui.glass.backdropSource
import dev.glasslauncher.tv.ui.MainViewModel
import dev.glasslauncher.tv.ui.UiState
import dev.glasslauncher.tv.ui.glass.GlassPanel

import dev.glasslauncher.tv.ui.theme.GlassType
import dev.glasslauncher.tv.ui.theme.LocalGlass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.min

// ---------- модель раскладки ----------

@Immutable
sealed interface HomeItem {
    val key: String

    data class App(val app: AppInfo) : HomeItem {
        override val key: String get() = app.key
    }

    data class Source(val source: TvSource) : HomeItem {
        override val key: String get() = source.key
    }

    data class Action(override val key: String, val label: String, val glyph: ActionGlyph) : HomeItem
}

@Immutable
private sealed interface HomeEntry {
    val id: String
}

/** Top Shelf: название в фокусе, баннер-предупреждение, капсула времени/громкости. */
private data class HeroEntry(val bannerActions: List<String>) : HomeEntry {
    override val id: String get() = "hero"
}

/** Стеклянный док с избранным (первый ряд, как в tvOS). */
private data class DockEntry(val items: List<HomeItem>) : HomeEntry {
    override val id: String get() = "dock"
}

private data class TilesEntry(val items: List<HomeItem>, override val id: String) : HomeEntry

/**
 * rows - ряды навигации (списки ключей), rowLazyIndex - в каком элементе LazyColumn лежит ряд.
 * Ряд 0 - кнопки баннера-предупреждения (обычно пуст), ряд 1 - док, дальше сетка.
 */
@Immutable
private class HomeLayout(
    val entries: List<HomeEntry>,
    val rows: List<List<String>>,
    val rowLazyIndex: List<Int>,
    val apps: Map<String, AppInfo>,
) {
    val keyPos: Map<String, Pair<Int, Int>> = buildMap {
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, k -> put(k, r to c) } }
    }

    /** Первая иконка дока (или сетки, если док пуст). */
    val firstContentKey: String? =
        rows.indices.firstOrNull { rowLazyIndex[it] >= 1 && rows[it].isNotEmpty() }?.let { rows[it].first() }
            ?: rows.firstOrNull { it.isNotEmpty() }?.firstOrNull()

    fun nearestKey(row: Int, col: Int): String? {
        if (rows.isEmpty()) return null
        val r = row.coerceIn(0, rows.lastIndex)
        val candidates = (0..rows.size).flatMap { d -> listOf(r + d, r - d) }.distinct()
        val target = candidates.firstOrNull { it in rows.indices && rows[it].isNotEmpty() } ?: return null
        val list = rows[target]
        return list[min(col, list.lastIndex)]
    }
}

private val ACTION_ITEMS = listOf(
    HomeItem.Action(MainViewModel.BAR_LAUNCHER_SETTINGS, "Настройки", ActionGlyph.GEAR),
    HomeItem.Action(MainViewModel.BAR_TV_SETTINGS, "Настройки TV", ActionGlyph.TV),
)

private fun buildLayout(
    bannerActions: List<String>,
    favorites: List<AppInfo>,
    sources: List<TvSource>,
    apps: List<AppInfo>,
    columns: Int,
    showSources: Boolean,
): HomeLayout {
    val dock = favorites.take(columns).map { HomeItem.App(it) }
    val entries = mutableListOf<HomeEntry>(HeroEntry(bannerActions), DockEntry(dock))
    val rows = mutableListOf(bannerActions, dock.map { it.key })
    val lazyIdx = mutableListOf(0, 1)

    fun addTiles(items: List<HomeItem>, prefix: String) {
        items.chunked(columns).forEachIndexed { i, chunk ->
            lazyIdx += entries.size
            rows += chunk.map { it.key }
            entries += TilesEntry(chunk, "$prefix$i")
        }
    }

    // В доке ровно один ряд избранного. Лишнее избранное (если уменьшили число иконок в ряду)
    // показываем первым в общей сетке. Затем все приложения и «Настройки», входы - в самом низу.
    addTiles((favorites.drop(columns) + apps).map { HomeItem.App(it) } + ACTION_ITEMS, "app")
    if (showSources && sources.isNotEmpty()) addTiles(sources.map { HomeItem.Source(it) }, "src")
    val appMap = (favorites + apps).associateBy { it.key }
    return HomeLayout(entries, rows, lazyIdx, appMap)
}

// ---------- экран ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: UiState,
    wallpaper: Wallpaper,
    vm: MainViewModel,
    focus: HomeFocus,
    listState: LazyListState,
    scrollTopRequested: Boolean,
) {
    val glass = LocalGlass.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    val showA11yBanner = !state.system.accessibilityEnabled && !state.bannerDismissed
    val showStockBanner = !showA11yBanner && state.system.stockReenabled && !state.bannerDismissed
    val bannerActions = when {
        showA11yBanner -> listOf(MainViewModel.BAR_FIX_SERVICE, MainViewModel.BAR_HELP, MainViewModel.BAR_DISMISS)
        showStockBanner -> listOf(MainViewModel.BAR_HELP, MainViewModel.BAR_STOCK_OK)
        else -> emptyList()
    }

    val columns = settings.columns
    val layout = remember(bannerActions, state.apps.favorites, state.sources, state.apps.apps, columns, settings.showSources) {
        buildLayout(bannerActions, state.apps.favorites, state.sources, state.apps.apps, columns, settings.showSources)
    }
    val currentLayout by rememberUpdatedState(layout)
    val menuOpen = state.menuFor != null
    val currentMenuOpen by rememberUpdatedState(menuOpen)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = maxHeight
        val screenW = maxWidth
        val heroH = screenH * 0.62f
        val hPad = 56.dp
        val gap = 30.dp
        val tileW: Dp = (screenW - hPad * 2 - gap * (columns - 1)) / columns
        val tileH: Dp = tileW * TILE_ASPECT
        val heroPx = with(density) { heroH.toPx() }

        // 0 - виден Top Shelf и док, 1 - пролистали в сетку. Читается только в фазе отрисовки.
        val gridProgress: () -> Float = {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
            }
        }

        // --- что показывать в Top Shelf (с небольшим debounce, чтобы не мигать при быстрой навигации) ---
        // Как в tvOS: верхняя зона реагирует только на док; в сетке фон замирает размытым.
        var shelf by remember { mutableStateOf<ShelfContent>(ShelfContent.WallpaperShelf) }
        // Живые обои выбирают, чтобы их видеть: с ними верхняя зона всегда показывает ролик,
        // а режим «баннер в фокусе» действует только для неподвижных обоев.
        val showAppBanners = settings.shelfMode == ShelfMode.FOCUSED_APP && wallpaper.video == null
        LaunchedEffect(layout, showAppBanners) {
            if (!showAppBanners) shelf = ShelfContent.WallpaperShelf
            snapshotFlow { focus.focusedKey }
                .debounce(120)
                .collect { key ->
                    val row = key?.let { currentLayout.keyPos[it]?.first }
                    val inTop = row == null || (currentLayout.rowLazyIndex.getOrNull(row) ?: 0) <= 1
                    if (!inTop) return@collect
                    val app = key?.let { currentLayout.apps[it] }
                    shelf = if (showAppBanners && app != null) {
                        ShelfContent.AppShelf(app.image(AppImage.Kind.SHELF), app.label)
                    } else {
                        ShelfContent.WallpaperShelf
                    }
                }
        }

        // --- что видно сквозь стекло дока: размытая версия текущего Top Shelf (считается один раз) ---
        var backdropImage by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(shelf, wallpaper.blurred) {
            backdropImage = when (val s = shelf) {
                is ShelfContent.AppShelf -> runCatching {
                    val request = ImageRequest.Builder(context).data(s.image).allowHardware(false).build()
                    (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
                }.getOrNull() ?: wallpaper.blurred
                ShelfContent.WallpaperShelf -> wallpaper.blurred
            }
        }

        // --- прокрутка: док и выше - к самому верху (виден Top Shelf), остальное - с рядом-контекстом сверху ---
        var scrollJob by remember { mutableStateOf<Job?>(null) }
        suspend fun scrollToRow(row: Int, instant: Boolean) {
            val li = currentLayout.rowLazyIndex.getOrNull(row) ?: return
            val target = if (li <= 1) 0 else li - 1
            if (!glass.animations || instant) {
                listState.scrollToItem(target)
                return
            }
            // Плавный переход с кривой tvOS. Смещение считаем относительно текущей позиции
            // (с учётом ещё не доехавшей прошлой прокрутки), только если цель уже на экране.
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (visible != null) {
                if (visible.offset != 0) {
                    listState.animateScrollBy(visible.offset.toFloat(), tween(durationMillis = 480, easing = TvEasing))
                }
            } else {
                listState.animateScrollToItem(target)
            }
        }

        // --- фокус: восстановление после смены раскладки / возврата с других экранов ---
        val lastPos = remember { intArrayOf(1, 0) }
        LaunchedEffect(Unit) {
            snapshotFlow { focus.focusedKey }.collect { k ->
                val pos = k?.let { currentLayout.keyPos[it] } ?: return@collect
                lastPos[0] = pos.first
                lastPos[1] = pos.second
            }
        }
        val initialFocusDone = remember { booleanArrayOf(false) }
        LaunchedEffect(layout, state.apps.loaded) {
            // До загрузки списка приложений раскладка пустая - не раздаём стартовый фокус.
            if (currentMenuOpen || !state.apps.loaded) return@LaunchedEffect
            // Раскладка обновилась (например, сменился статус HDMI-входа), а фокус на месте и плитка жива -
            // ничего не трогаем. Иначе прокрутка «прыгала» посреди навигации пользователя.
            val now = focus.focusedNow
            if (initialFocusDone[0] && now != null && now in layout.keyPos) return@LaunchedEffect
            val k = focus.focusedKey
            val target = when {
                // Первый показ: всегда первая иконка дока. Фокус, который Compose раздал сам
                // до загрузки приложений (на «Настройки»), не считаем выбором пользователя.
                !initialFocusDone[0] -> layout.firstContentKey
                k != null && k in layout.keyPos -> k
                k == null -> layout.firstContentKey
                else -> layout.nearestKey(lastPos[0], lastPos[1])
            } ?: return@LaunchedEffect
            if (layout.apps.isNotEmpty()) initialFocusDone[0] = true
            // Плитка цели могла оказаться за экраном (LazyColumn её не создал) - сначала прокручиваем к её ряду.
            layout.keyPos[target]?.let { (row, _) -> scrollToRow(row, instant = true) }
            focus.pendingKey = target
            // Окно могло ещё не получить фокус (холодный старт, анимация перехода) - повторяем, пока не сфокусируемся.
            repeat(10) {
                delay(300)
                // Пользователь уже нажимает стрелки (есть фокус или запрос фокуса) - не перебиваем его.
                if (focus.focusedNow != null || focus.pendingKey != null || currentMenuOpen) return@LaunchedEffect
                focus.pendingKey = focus.focusedKey?.takeIf { it in currentLayout.keyPos } ?: target
            }
        }
        LaunchedEffect(menuOpen) {
            if (!menuOpen) focus.focusedKey?.takeIf { it in currentLayout.keyPos }?.let { focus.pendingKey = it }
        }
        // Режим перемещения: фокус и прокрутка следуют за перемещаемой иконкой.
        LaunchedEffect(layout, state.moving) {
            val pkg = state.moving?.packageName ?: return@LaunchedEffect
            val key = "app:$pkg"
            val pos = layout.keyPos[key] ?: return@LaunchedEffect
            focus.pendingKey = key
            scrollToRow(pos.first, instant = false)
        }
        // Home при открытом лаунчере: плавно к началу, фокус на первую иконку дока.
        LaunchedEffect(scrollTopRequested) {
            if (!scrollTopRequested) return@LaunchedEffect
            vm.consumeScrollTop()
            focus.desiredColumn = 0
            currentLayout.firstContentKey?.let { focus.go(it, 0, 0) }
            if (glass.animations) listState.animateScrollToItem(0) else listState.scrollToItem(0)
        }
        // Предзагрузка размытых баннеров для Top Shelf - после старта, чтобы не мешать холодному запуску.
        LaunchedEffect(layout) {
            delay(1500)
            val loader = context.imageLoader
            (state.apps.favorites + state.apps.apps.take(columns * 2)).forEach {
                loader.enqueue(ImageRequest.Builder(context).data(it.image(AppImage.Kind.SHELF)).build())
            }
        }

        fun onNavKey(e: KeyEvent): Boolean {
            val n = e.nativeKeyEvent
            val (dx, dy) = when (n.keyCode) {
                NativeKeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
                NativeKeyEvent.KEYCODE_DPAD_UP -> 0 to -1
                NativeKeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
                else -> return false
            }
            if (n.action != NativeKeyEvent.ACTION_DOWN) return true
            if (state.moving != null) {
                vm.moveBy(dx, dy)
                return true
            }
            val l = currentLayout
            // Если предыдущее нажатие ещё не успело перенести фокус (плитка создаётся после прокрутки),
            // считаем от того, куда фокус уже едет, - иначе два быстрых нажатия давали один шаг или скачок.
            val from = focus.pendingKey?.takeIf { it in l.keyPos } ?: focus.focusedKey
            val pos = from?.let { l.keyPos[it] }
            if (pos == null) {
                l.firstContentKey?.let { focus.go(it, 0, 0) }
                return true
            }
            val (r, c) = pos
            if (dx != 0) {
                val row = l.rows[r]
                val nc = c + dx
                if (nc in row.indices) {
                    focus.desiredColumn = nc
                    focus.go(row[nc], dx, 0)
                }
                return true
            }
            var nr = r + dy
            while (nr in l.rows.indices && l.rows[nr].isEmpty()) nr += dy
            if (nr !in l.rows.indices) return true
            val row = l.rows[nr]
            focus.go(row[min(focus.desiredColumn, row.lastIndex)], 0, dy)
            // Одна прокрутка за раз: новая отменяет предыдущую, а не догоняет её.
            scrollJob?.cancel()
            scrollJob = scope.launch { scrollToRow(nr, instant = n.repeatCount > 0) }
            return true
        }

        // Под контекстным меню - только затемнение: размытие всего экрана на GPU этого телевизора
        // стоило десятки миллисекунд на кадр.
        val video = wallpaper.video
        var videoShowing by remember(video, wallpaper.videoStamp) { mutableStateOf(false) }
        // Ролик виден только на верхнем экране, когда в Top Shelf обои (а не баннер приложения);
        // в остальное время он на паузе - декодер и композитор не работают впустую.
        val videoVisible by remember(shelf) {
            derivedStateOf {
                shelf == ShelfContent.WallpaperShelf &&
                    listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset < heroPx * 0.9f
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (video != null) {
                VideoWallpaper(
                    video,
                    wallpaper.videoStamp,
                    playing = videoVisible,
                    modifier = Modifier.fillMaxSize(),
                    onShowingChange = { videoShowing = it },
                )
            }
            Box(Modifier.fillMaxSize()) {
                // Как в Apple TV: в сетке фон - размытая версия верхней зоны, а не другая картинка.
                // Она же - «то, что за стеклом» (backdropSource): уже размыта заранее, поэтому стеклу
                // не нужен живой RenderEffect-блюр, только линза по краям.
                // Пока Top Shelf полностью непрозрачен (сверху, p = 0), сама подложка на экран не выводится.
                ShelfBlurUnderlay(
                    shelf,
                    wallpaper,
                    glass.animations,
                    Modifier.fillMaxSize().backdropSource(visible = { gridProgress() > 0f }),
                )
                // Резкий Top Shelf: при уходе в сетку уезжает вверх и растворяется.
                ShelfBackdrop(
                    content = shelf,
                    wallpaper = wallpaper,
                    height = screenH,
                    animations = glass.animations,
                    // Пока ролик не выдал живые кадры - на месте окна неподвижный первый кадр, без чёрной вспышки.
                    videoBehind = video != null && videoShowing,
                    modifier = Modifier.graphicsLayer {
                        val p = gridProgress()
                        translationY = -heroPx * 0.35f * p
                        alpha = 1f - p
                    },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = gridProgress() }
                        .background(Color.Black.copy(alpha = 0.38f)),
                )
            }

            // На TV Compose сам прокручивает фокус к «оси» на 30% высоты и уводит Top Shelf за экран.
            // Прокруткой управляет scrollToRow(), поэтому автопрокрутку к фокусу отключаем.
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides NoAutoScroll,
                LocalBackdropImage provides backdropImage,
            ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = screenH * 0.4f),
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { onNavKey(it) },
            ) {
                items(layout.entries, key = { it.id }, contentType = { it::class }) { entry ->
                    when (entry) {
                        is HeroEntry -> HeroItem(entry, shelf, state, vm, focus, Modifier.height(heroH))
                        is DockEntry -> DockItem(entry, state, vm, focus, tileW, tileH, gap, hPad)
                        is TilesEntry -> Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = hPad, end = hPad, top = 22.dp)
                                .height(tileH + LABEL_SPACE_DP.dp),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            entry.items.forEach { item ->
                                key(item.key) { HomeTile(item, state, vm, focus, tileW) }
                            }
                        }
                    }
                }
            }
            }
        }

        if (state.moving != null) {
            MoveHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
        }

        state.menuFor?.let { app ->
            AppContextMenu(
                app = app,
                isFavorite = state.apps.favorites.any { it.packageName == app.packageName },
                favoritesLimit = state.settings.columns,
                favoritesCount = state.apps.favorites.size,
                onAction = { vm.onMenuAction(app, it) },
                onDismiss = vm::closeMenu,
            )
        }
    }
}

@Composable
private fun HomeTile(item: HomeItem, state: UiState, vm: MainViewModel, focus: HomeFocus, tileW: Dp) {
    when (item) {
        is HomeItem.App -> AppTile(
            app = item.app,
            width = tileW,
            focus = focus,
            moving = state.moving?.packageName == item.app.packageName,
            onClick = { vm.launchApp(item.app) },
            onLongClick = { vm.openMenu(item.app) },
        )
        is HomeItem.Source -> SourceTile(
            source = item.source,
            width = tileW,
            focus = focus,
            onClick = { vm.launchSource(item.source) },
        )
        is HomeItem.Action -> ActionTile(
            key = item.key,
            label = item.label,
            glyph = item.glyph,
            width = tileW,
            focus = focus,
            onClick = { vm.onBarAction(item.key) },
        )
    }
}

@Composable
private fun DockItem(
    entry: DockEntry,
    state: UiState,
    vm: MainViewModel,
    focus: HomeFocus,
    tileW: Dp,
    tileH: Dp,
    gap: Dp,
    hPad: Dp,
) {
    val inset = 18.dp
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = hPad - inset),
    ) {
        // Стеклянная подложка дока - видна всегда, в том числе когда фокус ушёл в общую сетку.
        GlassPanel(
            Modifier.matchParentSize(),
            cornerRadius = 34.dp,
        ) {}
        if (entry.items.isEmpty()) {
            Text(
                "Избранное пусто: долгий OK на приложении → «Добавить в избранное»",
                style = GlassType.BodyDim,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 40.dp),
            )
        } else {
            Row(
                Modifier
                    .padding(start = inset, end = inset, top = 22.dp)
                    .height(tileH + LABEL_SPACE_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                entry.items.forEach { item ->
                    key(item.key) { HomeTile(item, state, vm, focus, tileW) }
                }
            }
        }
    }
}

@Composable
private fun HeroItem(
    entry: HeroEntry,
    shelf: ShelfContent,
    state: UiState,
    vm: MainViewModel,
    focus: HomeFocus,
    modifier: Modifier,
) {
    val glass = LocalGlass.current
    Box(modifier.fillMaxWidth().padding(start = 56.dp, end = 48.dp, top = 34.dp)) {
        Column(Modifier.align(Alignment.TopStart)) {
            HeroTitle(shelf, glass.animations)
            if (entry.bannerActions.isNotEmpty()) {
                val a11y = !state.system.accessibilityEnabled
                StatusBanner(
                    title = if (a11y) "Кнопка Home не перехватывается" else "Прошивка снова включила стоковый лаунчер",
                    text = if (a11y) {
                        "Служба специальных возможностей Glass Launcher выключена (часто - после обновления прошивки). " +
                            "Без неё Home откроет стоковый лаунчер Google TV."
                    } else {
                        "Обновление снова включило ${state.system.stockEnabled.joinToString()}. " +
                            "Перехват Home продолжит работать, а команды adb для отключения - в «Как исправить»."
                    },
                    modifier = Modifier.padding(top = 20.dp),
                ) {
                    entry.bannerActions.forEach { k -> BarPill(k, barTitle(k), focus, { vm.onBarAction(k) }) }
                }
            }
        }
        LockClock(Modifier.align(Alignment.TopEnd))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val NoAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

private fun barTitle(key: String): String = when (key) {
    MainViewModel.BAR_FIX_SERVICE -> "Включить"
    MainViewModel.BAR_HELP -> "Как исправить"
    MainViewModel.BAR_DISMISS -> "Позже"
    MainViewModel.BAR_STOCK_OK -> "Понятно"
    else -> key
}

/** Кривая прокрутки tvOS: быстрый старт и долгое мягкое торможение. */
private val TvEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
