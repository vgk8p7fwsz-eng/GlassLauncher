package dev.glasslauncher.tv.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.glasslauncher.tv.data.settings.WallpaperType
import dev.glasslauncher.tv.data.wallpaper.VIDEO_EXTENSIONS
import dev.glasslauncher.tv.data.wallpaper.WallpaperPresets
import dev.glasslauncher.tv.ui.MainViewModel
import dev.glasslauncher.tv.ui.Screen
import dev.glasslauncher.tv.ui.SettingsPage
import dev.glasslauncher.tv.ui.UiState
import dev.glasslauncher.tv.ui.components.SectionTitle
import dev.glasslauncher.tv.ui.components.SettingRow
import dev.glasslauncher.tv.ui.components.requestFocusWhenReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun WallpaperPage(state: UiState, vm: MainViewModel, first: FocusRequester) {
    val context = LocalContext.current
    val s = state.settings
    // Системного выбора файлов (DocumentsUI) на многих ТВ-прошивках нет - проверяем заранее.
    val hasPicker = remember {
        Intent(Intent.ACTION_GET_CONTENT).setType("image/*").resolveActivity(context.packageManager) != null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importWallpaper(uri)
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            SettingRow(
                "Из файла (USB-флешка / память)",
                subtitle = "JPG, PNG, WebP. Картинка копируется к лаунчеру - флешку потом можно вынуть.",
                focusRequester = first,
            ) { vm.push(Screen.Settings(SettingsPage.FILES)) }
        }
        item {
            SettingRow(
                "Из галереи",
                subtitle = if (hasPicker) null else "На этом устройстве нет системного выбора файлов - используйте «Из файла»",
                value = if (hasPicker) null else "недоступно",
            ) { if (hasPicker) runCatching { picker.launch("image/*") } }
        }
        item { SectionTitle("Пресеты") }
        itemsIndexed(WallpaperPresets.all) { index, preset ->
            val selected = s.wallpaperType == WallpaperType.PRESET && s.wallpaperPreset == index
            SettingRow(
                preset.name,
                value = if (selected) "✓" else null,
                leading = {
                    Box(
                        Modifier
                            .size(64.dp, 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(preset.colors.map { Color(it) })),
                    )
                },
            ) { vm.setWallpaperPreset(index) }
        }
        item { SectionTitle("Однотонный цвет") }
        itemsIndexed(WallpaperPresets.solidColors) { index, color ->
            val selected = s.wallpaperType == WallpaperType.COLOR && s.wallpaperColor == color
            SettingRow(
                "Цвет ${index + 1}",
                value = if (selected) "✓" else null,
                leading = {
                    Box(
                        Modifier
                            .size(64.dp, 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(color)),
                    )
                },
            ) { vm.setWallpaperColor(color) }
        }
    }
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

private data class StorageRoot(val title: String, val dir: File)

private fun storageRoots(context: Context): List<StorageRoot> {
    val result = mutableListOf<StorageRoot>()
    val sm = context.getSystemService(StorageManager::class.java)
    runCatching {
        sm?.storageVolumes?.forEach { v ->
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
            val dir = if (Build.VERSION.SDK_INT >= 30) {
                v.directory
            } else if (v.isPrimary) {
                @Suppress("DEPRECATION")
                Environment.getExternalStorageDirectory()
            } else {
                v.uuid?.let { File("/storage/$it") }
            }
            if (dir != null) result += StorageRoot(v.getDescription(context) ?: dir.name, dir)
        }
    }
    if (result.isEmpty()) {
        @Suppress("DEPRECATION")
        result += StorageRoot("Внутренняя память", Environment.getExternalStorageDirectory())
    }
    return result
}

private fun listDir(dir: File): List<File> {
    val files = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
        .filter { !it.name.startsWith(".") }
    val dirs = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
    val media = files
        .filter { it.isFile && it.extension.lowercase().let { e -> e in IMAGE_EXT || e in VIDEO_EXTENSIONS } }
        .sortedBy { it.name.lowercase() }
    return dirs + media
}

/** Простой файловый браузер для D-pad: тома → папки → изображения и ролики (живые обои). */
@Composable
internal fun FileBrowser(vm: MainViewModel, first: FocusRequester) {
    val context = LocalContext.current
    // Android 13+: картинки и видео - отдельные разрешения; без READ_MEDIA_VIDEO ролики в папках не видны.
    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    var granted by remember {
        mutableStateOf(
            permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { ok -> ok }
    }
    val roots = remember { storageRoots(context) }
    var dir by remember { mutableStateOf<File?>(null) }

    // Back внутри браузера - на уровень вверх; с корня - обычный возврат на страницу обоев.
    BackHandler(enabled = dir != null) {
        val current = dir
        dir = if (current == null || roots.any { it.dir == current }) null else current.parentFile
    }

    val entries by produceState(initialValue = emptyList<File>(), dir, granted) {
        val d = dir
        value = if (d == null || !granted) emptyList() else withContext(Dispatchers.IO) { listDir(d) }
    }
    LaunchedEffect(dir, entries, granted) { first.requestFocusWhenReady() }

    LazyColumn(Modifier.fillMaxWidth()) {
        when {
            !granted -> item {
                SettingRow(
                    "Разрешить доступ к изображениям",
                    subtitle = "Нужно, чтобы читать картинки с USB и из памяти",
                    focusRequester = first,
                ) { runCatching { permissionLauncher.launch(permissions) } }
            }
            dir == null -> itemsIndexed(roots) { i, root ->
                SettingRow(
                    root.title,
                    subtitle = root.dir.absolutePath,
                    focusRequester = if (i == 0) first else null,
                ) { dir = root.dir }
            }
            else -> {
                val current = dir!!
                item {
                    SettingRow("..  Назад", subtitle = current.absolutePath, focusRequester = first) {
                        dir = if (roots.any { it.dir == current }) null else current.parentFile
                    }
                }
                if (entries.isEmpty()) {
                    item { SettingRow("Здесь нет изображений") }
                }
                itemsIndexed(entries, key = { _, f -> f.absolutePath }) { _, f ->
                    if (f.isDirectory) {
                        SettingRow("▸  ${f.name}") { dir = f }
                    } else {
                        val isVideo = f.extension.lowercase() in VIDEO_EXTENSIONS
                        SettingRow(
                            if (isVideo) "▶  ${f.name}" else f.name,
                            subtitle = if (isVideo) "Живые обои: видео без звука по кругу" else null,
                            value = "${f.length() / 1024} КБ",
                        ) { vm.importWallpaper(f) }
                    }
                }
            }
        }
    }
}
