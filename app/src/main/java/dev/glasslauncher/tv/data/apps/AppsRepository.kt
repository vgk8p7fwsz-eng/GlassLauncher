package dev.glasslauncher.tv.data.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import dev.glasslauncher.tv.data.db.AppPrefEntity
import dev.glasslauncher.tv.data.db.LauncherDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppsRepository(
    context: Context,
    private val db: LauncherDatabase,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val dao = db.appPrefDao()

    private val installed = MutableStateFlow<List<AppInfo>?>(null)

    init {
        scope.launch { reload() }
        scope.launch {
            packageEvents()
                .debounce(350)
                .collect { reload() }
        }
    }

    val state: Flow<AppsState> = combine(installed.filterNotNull(), dao.observeAll()) { apps, prefs ->
        build(apps, prefs)
    }.flowOn(Dispatchers.Default)

    suspend fun reload() {
        val fresh = withContext(Dispatchers.IO) {
            runCatching { queryApps() }
                .onFailure { Log.w(TAG, "queryApps failed", it) }
                .getOrNull()
        }
        when {
            fresh != null -> {
                Log.i(TAG, "apps loaded: ${fresh.size}")
                installed.value = fresh
            }
            installed.value == null -> installed.value = emptyList()
        }
    }

    /** Оба источника: LEANBACK_LAUNCHER (TV-приложения) и LAUNCHER (мобильные сайдлоады). */
    private fun queryApps(): List<AppInfo> {
        val result = LinkedHashMap<String, AppInfo>()
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in queryActivities(leanback)) toAppInfo(ri, true)?.let { result.putIfAbsent(it.packageName, it) }
        for (ri in queryActivities(launcher)) toAppInfo(ri, false)?.let { result.putIfAbsent(it.packageName, it) }
        result.remove(appContext.packageName)
        return result.values.toList()
    }

    private fun queryActivities(intent: Intent): List<ResolveInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }.getOrDefault(emptyList())

    private fun toAppInfo(ri: ResolveInfo, leanback: Boolean): AppInfo? {
        val ai = ri.activityInfo ?: return null
        val appInfo = ai.applicationInfo ?: return null
        if (!appInfo.enabled || !ai.enabled) return null
        val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: ai.packageName
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        val stamp = runCatching { File(appInfo.sourceDir).lastModified() }.getOrDefault(0L)
        return AppInfo(ai.packageName, ai.name, label, leanback, isSystem, stamp)
    }

    private fun build(apps: List<AppInfo>, prefs: List<AppPrefEntity>): AppsState {
        val byPkg = prefs.associateBy { it.packageName }
        val labelOrder = compareBy<AppInfo> { it.label.lowercase() }
        val visible = apps.filter { byPkg[it.packageName]?.hidden != true }
        val favorites = visible
            .filter { byPkg[it.packageName]?.favorite == true }
            .sortedWith(compareBy<AppInfo> { byPkg[it.packageName]?.favoriteIndex ?: Int.MAX_VALUE }.then(labelOrder))
        val rest = visible
            .filter { byPkg[it.packageName]?.favorite != true }
            .sortedWith(compareBy<AppInfo> { byPkg[it.packageName]?.sortIndex ?: Int.MAX_VALUE }.then(labelOrder))
        val hidden = apps.filter { byPkg[it.packageName]?.hidden == true }.sortedWith(labelOrder)
        return AppsState(
            loaded = true,
            favorites = favorites,
            apps = rest,
            hidden = hidden,
            all = apps.sortedWith(labelOrder),
        )
    }

    private suspend fun edit(packageName: String, transform: (AppPrefEntity) -> AppPrefEntity) {
        db.withTransaction {
            val current = dao.get(packageName) ?: AppPrefEntity(packageName)
            dao.upsert(transform(current))
        }
    }

    suspend fun setFavorite(packageName: String, favorite: Boolean) {
        val next = (dao.maxFavoriteIndex()?.takeIf { it != Int.MAX_VALUE } ?: -1) + 1
        edit(packageName) {
            it.copy(
                favorite = favorite,
                favoriteIndex = if (favorite) next else Int.MAX_VALUE,
                hidden = if (favorite) false else it.hidden,
            )
        }
    }

    suspend fun setHidden(packageName: String, hidden: Boolean) = edit(packageName) {
        it.copy(hidden = hidden, favorite = if (hidden) false else it.favorite)
    }

    suspend fun saveOrder(packages: List<String>) = db.withTransaction {
        packages.forEachIndexed { index, pkg ->
            val current = dao.get(pkg) ?: AppPrefEntity(pkg)
            dao.upsert(current.copy(sortIndex = index))
        }
    }

    suspend fun saveFavoriteOrder(packages: List<String>) = db.withTransaction {
        packages.forEachIndexed { index, pkg ->
            val current = dao.get(pkg) ?: AppPrefEntity(pkg)
            dao.upsert(current.copy(favorite = true, favoriteIndex = index))
        }
    }

    suspend fun resetOrder() = dao.resetOrder()

    /** Первый запуск: кладём в док популярные стриминги, если они установлены, иначе первые приложения. */
    suspend fun seedFavorites(apps: List<AppInfo>, count: Int) {
        val installedPkgs = apps.map { it.packageName }.toSet()
        val picked = POPULAR.filter { it in installedPkgs }.toMutableList()
        if (picked.size < count) {
            apps.filter { it.isLeanback && it.packageName !in picked }
                .take(count - picked.size)
                .forEach { picked += it.packageName }
        }
        saveFavoriteOrder(picked.take(count))
    }

    fun launchIntent(app: AppInfo): Intent {
        val intent = (if (app.isLeanback) pm.getLeanbackLaunchIntentForPackage(app.packageName) else null)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(if (app.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(app.packageName, app.activityName))
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    /** Установка/удаление/обновление пакетов. Ресивер динамический: манифестные implicit-broadcast'ы на 26+ не приходят. */
    private fun packageEvents(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED ||
                    (intent.action == Intent.ACTION_PACKAGE_REMOVED && !replacing)
                ) {
                    if (pkg != null) scope.launch { runCatching { dao.delete(pkg) } }
                }
                trySend(Unit)
            }
        }
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val externalFilter = IntentFilter().apply {
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        }
        ContextCompat.registerReceiver(appContext, receiver, pkgFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(appContext, receiver, externalFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    companion object {
        private const val TAG = "AppsRepository"
        private val POPULAR = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "ru.kinopoisk.tv",
            "com.google.android.apps.youtube.music.pwa",
            "com.spotify.tv.android",
            "com.google.android.videos",
        )
    }
}
