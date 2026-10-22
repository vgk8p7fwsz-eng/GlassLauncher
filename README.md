# Glass Launcher — лаунчер для Android TV / Google TV

Главный экран в стиле Apple TV (Top Shelf, док, сетка с фокус-эффектом tvOS), визуальный язык
Liquid Glass, перехват Home через AccessibilityService (как Projectivy). Целевое устройство —
Xiaomi TV A Pro 2025 (MiTV-MZTU1, Google TV), но работает на любом Android TV 8+.

> Проект написан без сборки: на машине, где он создавался, не было Android SDK.
> Первая сборка в Android Studio может потребовать мелких правок импортов/API.

## 1. Структура

```
GlassLauncher/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml                 # version catalog
│   └── wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── xml/accessibility_service_config.xml
        │   ├── values/strings.xml, themes.xml
        │   └── drawable/app_banner.xml, ic_launcher.xml
        └── java/dev/glasslauncher/tv/
            ├── GlassLauncherApp.kt            # Application + Coil ImageLoader
            ├── di/AppContainer.kt             # ручной DI
            ├── data/
            │   ├── apps/AppInfo.kt            # модели
            │   ├── apps/AppsRepository.kt     # PackageManager + BroadcastReceiver + Room
            │   ├── apps/AppImageLoader.kt     # баннер → иконка → заглушка; Coil Fetcher/Keyer
            │   ├── db/LauncherDatabase.kt     # Room: порядок, избранное, скрытые
            │   ├── settings/SettingsRepository.kt  # DataStore
            │   ├── tv/TvInputsRepository.kt   # HDMI/тюнер через TvInputManager
            │   └── wallpaper/                 # обои, пресеты, BlurUtil (blur один раз)
            ├── system/
            │   ├── DeviceProfile.kt           # детект слабого железа
            │   ├── SystemStatus.kt            # служба, дефолтный HOME, launcherx, adb-команды
            │   └── SystemIntents.kt           # системные экраны с фолбэками
            ├── service/
            │   ├── HomeAccessibilityService.kt  # Home, окна launcherx, кнопки пульта
            │   ├── VolumeController.kt        # шаг / удержание 2 с = mute
            │   └── KeyEventBus.kt             # лог keycode, режим «обучения»
            ├── receiver/BootReceiver.kt
            └── ui/
                ├── MainActivity.kt, MainViewModel.kt, LauncherRoot.kt
                ├── theme/Theme.kt
                ├── glass/Glass.kt             # GlassPanel, AGSL-преломление
                ├── components/Focusables.kt   # кнопки, строки настроек, OK/долгий OK
                ├── home/                      # HomeScreen, Tiles, TopShelf, ContextMenu, HomeFocus
                ├── onboarding/OnboardingScreen.kt
                └── settings/                  # SettingsScreen, WallpaperScreens (файлы с USB)
```

## 2. Сборка и установка

Нужны Android Studio (Ladybug или новее) или JDK 17 + Android SDK 35.

1. Откройте папку `GlassLauncher` в Android Studio → она создаст Gradle wrapper (`gradlew`) и скачает зависимости.
   Без Studio: установите Gradle 8.11+, выполните `gradle wrapper` в папке проекта.
2. Сборка:

```bash
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk` (подписан debug-ключом — для сайдлоада достаточно).

3. Включите на телевизоре: Настройки → Система → О устройстве → 7 раз «Сборка ОС Android TV» →
   Для разработчиков → «Отладка по USB» / «Отладка по сети». Подключитесь:

```bash
adb connect 192.168.1.50:5555
```

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

4. Включите службу спец. возможностей (на экране онбординга есть кнопка) или через adb:

```bash
adb shell settings put secure enabled_accessibility_services dev.glasslauncher.tv/dev.glasslauncher.tv.service.HomeAccessibilityService
```

```bash
adb shell settings put secure accessibility_enabled 1
```

Первая команда **перезаписывает** список служб. Если у вас уже включены другие (например, Button Mapper),
сначала посмотрите текущее значение `adb shell settings get secure enabled_accessibility_services`
и допишите нашу через `:`.

5. Рекомендуется: разрешить лаунчеру самому восстанавливать службу после обновлений прошивки:

```bash
adb shell pm grant dev.glasslauncher.tv android.permission.WRITE_SECURE_SETTINGS
```

## 3. Лаунчер по умолчанию и стоковый лаунчер

**Рекомендуемый режим на Google TV — НЕ отключать launcherx.** Glass Launcher перехватывает Home службой
и мгновенно перекрывает launcherx, если тот всплыл. Так сохраняются панель быстрых настроек,
переключатель входов, профили и уведомления, которые на Google TV живут внутри launcherx.

Назначить по умолчанию (на Google TV часто игнорируется — это нормально, служба всё равно работает):

```bash
adb shell cmd package set-home-activity dev.glasslauncher.tv/.ui.MainActivity
```

### Отключение стокового лаунчера (на свой риск)

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

```bash
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Вернуть:

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
```

```bash
adb shell pm enable com.google.android.tungsten.setupwraith
```

**Риски:**
- Пропадут: панель быстрых настроек Google TV (долгий Home / кнопка настроек), часть настроек аккаунта и
  профилей, рекомендации, иногда — переключатель входов на кнопке Input. Входы остаются в ряду «Источники».
- `setupwraith` без отключённого launcherx отключать нельзя — и наоборот, без отключения setupwraith после
  перезагрузки может появиться мастер настройки / цикл загрузки.
- Если Glass Launcher удалён или упал, а стоковый отключён — телевизор загрузится в чёрный экран.
  Держите включённой «Отладку по сети» и запишите команды `pm enable` выше.
- Сброс до заводских настроек вернёт всё как было; OTA-обновление Xiaomi/Google часто включает
  launcherx обратно — лаунчер это заметит и покажет баннер «Прошивка снова включила стоковый лаунчер».
- `pm uninstall` для системных пакетов не используйте: откатить сложнее, чем `disable-user`.

## 4. Как это работает

| Требование | Реализация |
|---|---|
| Home на Google TV | `HomeAccessibilityService.onKeyEvent(KEYCODE_HOME)` → `MainActivity` (NEW_TASK \| CLEAR_TOP). Повторный Home → `onNewIntent` → прокрутка к началу и фокус на первую иконку |
| launcherx всплыл сам | `TYPE_WINDOW_STATE_CHANGED`: если пакет стоковый и класс окна похож на главный экран (Home/Main/Launcher в имени, без dashboard/input/panel…) — открываем свой. Классы окон видны в «Диагностике» |
| Долгий Home | Открывает системную панель (`GLOBAL_ACTION_QUICK_SETTINGS`, иначе Настройки) — выключается в настройках |
| Кнопки Netflix / Prime / YouTube | Настройки → Кнопки пульта → «Назначить кнопку…» → нажать кнопку → выбрать приложение. Все keycode пишутся в лог (экран и `adb logcat -s GlassKeys`) |
| Ассистент | По умолчанию не трогается. Назначить можно явно, с предупреждением |
| Громкость | Короткое нажатие — шаг с системным индикатором; удержание ≥ 2 с — mute с запоминанием уровня; следующее нажатие возвращает уровень |
| Медиа-кнопки, SETTINGS | Не поглощаются ни службой, ни UI |
| Back | На главном — ничего; меню / перемещение / подстраницы — шаг назад |
| После перезагрузки | Система сама переподключает включённую службу; `BootReceiver` восстанавливает её при наличии WRITE_SECURE_SETTINGS и выводит лаунчер; при старте/onResume — проверка службы и launcherx с баннером |
| Источники | `TvInputManager`: HDMI → `buildChannelUriForPassthroughInput`, тюнер → `buildChannelsUriForInput`, фолбэк на ТВ-приложения прошивки |
| Liquid Glass | Подложка — размытая один раз копия обоев в экранных координатах + тонировка, specular-блик, градиентная обводка. FULL: AGSL-преломление края (API 33+) и blur контента под меню (API 31+) |
| Слабое железо | `isLowRamDevice`, ОЗУ < ~2.5 ГБ «видимых» (т.е. 2 ГБ модели) или Android < 12 → режим «Лёгкое» по умолчанию |

## 5. Где требования расходятся с возможностями платформы

1. **«Live-blur фона под панелью».** В Compose (и в View без root/системного окна) нет backdrop-blur
   «того, что нарисовано под панелью». `Modifier.blur`/`RenderEffect` размывают *сам* элемент.
   Альтернатива (сделано): размытые один раз обои, отрисованные в экранных координатах, — визуально то же
   стекло над обоями; под контекстным меню в режиме FULL размывается весь контент одним `RenderEffect`.
   Иконки *сквозь* док-панель не просвечивают размыто — это и есть цена 60 fps.
2. **AGSL на Android 12.** `RuntimeShader` появился в Android 13 (API 33), не в 12. На API 31–32 — только blur.
3. **KEYCODE_HOME через службу.** Работает, если прошивка обрабатывает Home в `interceptKeyBeforeDispatching`
   (так на Google TV и большинстве приставок). Если вендор обрабатывает Home раньше (до фильтра доступности),
   срабатывает второй механизм — перекрытие окна launcherx; при этом на долю секунды виден стоковый экран.
4. **Кнопки Netflix/YouTube/Prime на Google TV.** Часть прошивок обрабатывает их до служб доступности
   (через системный «button mapper») или шлёт не keycode, а intent. Если кнопка не появляется в логе
   нажатий — переназначить её из стороннего приложения нельзя. Проверьте лог в «Кнопки пульта».
5. **Панель быстрых настроек Google TV.** Публичного интента у неё нет. Кнопка «Настройки TV» открывает
   `android.settings.SETTINGS` (полные настройки); «Быстрые настройки» пробуют `GLOBAL_ACTION_QUICK_SETTINGS`,
   который на части ТВ-прошивок ничего не делает.
6. **Удержание громкости = mute** отнимает автоповтор: зажать «+», чтобы быстро прибавить, больше нельзя —
   только нажатиями. Если звук идёт по HDMI ARC с фиксированной громкостью, служба отдаёт кнопки системе.
7. **Выбор обоев «из галереи».** На большинстве ТВ нет DocumentsUI — пункт помечается «недоступно»,
   работает встроенный браузер файлов (USB/внутренняя память).
8. **BOOT_COMPLETED не означает, что лаунчер покажется.** С Android 10 запуск Activity из ресивера в фоне
   запрещён; разрешён, только когда служба доступности уже привязана. Если нет — лаунчер откроется, как
   только служба увидит launcherx. «Спящий режим» Xiaomi — это не перезагрузка, и BOOT_COMPLETED не приходит.
9. **MIUI-подобная «очистка памяти»** не убивает привязанную службу доступности, но обновление прошивки
   может выключить её — для этого WRITE_SECURE_SETTINGS + баннер на главном экране.
10. **Холодный старт ≤ 1 с** достижим на 2 ГБ MediaTek для release-сборки с R8 и baseline profile
    (`profileinstaller` подключён). Debug-сборка будет заметно медленнее — замеряйте release.

## 6. Известные ограничения по устройствам

- **Xiaomi TV A Pro 2025 / Google TV:** launcherx содержит панель настроек и переключатель входов —
  поэтому перекрываются только окна главного экрана. Если после OTA классы окон изменятся и лаунчер
  перестанет перекрываться или начнёт перекрывать панель — посмотрите «Диагностика → Окна стокового
  лаунчера» и поправьте `INCLUDE_WORDS`/`EXCLUDE_WORDS` в `HomeAccessibilityService`.
- **Ряд «Источники»:** на прошивках, где HDMI-входы реализованы не через TV Input Framework, ряд будет
  пустым (скрывается автоматически). Запуск входа открывает системное ТВ-приложение — на Xiaomi это может быть
  `com.google.android.tv` или вендорное; список фолбэков в `TvInputsRepository.LIVE_TV_PACKAGES`.
- **Chromecast with Google TV / Onn / прочие Google TV:** работает так же; `set-home-activity` обычно
  игнорируется — используйте службу.
- **Android TV 8–11 (Amlogic S905X и т.п.):** всегда режим «Лёгкое», без AGSL и blur под меню.
  `cmd package set-home-activity` на Android 8–9 может отсутствовать — выберите лаунчер в диалоге при Home.
- **Fire TV:** Home жёстко зашит в Amazon-лаунчер; служба работает, но Amazon периодически блокирует
  перехват в обновлениях. Отключать стоковый лаунчер Fire TV нельзя.
- **Сайдлоад-приложения без LEANBACK_LAUNCHER** показываются (категория LAUNCHER), но могут быть не
  адаптированы под пульт.
- **Удаление системных приложений** недоступно (пункт меню неактивен) — только «Скрыть».
