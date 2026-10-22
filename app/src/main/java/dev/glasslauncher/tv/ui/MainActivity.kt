package dev.glasslauncher.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import dev.glasslauncher.tv.service.KeyEventBus

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Back на главном экране ничего не делает - лаунчер не закрывается. В подменю - шаг назад.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = vm.onBack()
        })

        setContent { LauncherRoot(vm) }
        // Тёмный windowBackground нужен только для холодного старта; дальше экран целиком рисует Compose,
        // и фон окна был бы лишней полноэкранной заливкой на каждом кадре.
        window.setBackgroundDrawable(null)
    }

    /**
     * singleTask: повторный Home (системный CATEGORY_HOME или от нашей AccessibilityService)
     * приходит сюда, а не создаёт новый экземпляр.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val isHome = intent.hasCategory(Intent.CATEGORY_HOME) || intent.getBooleanExtra(EXTRA_HOME_KEY, false)
        if (isHome) vm.onHomePressed()
    }

    override fun onResume() {
        super.onResume()
        // Прошивка могла выключить службу/включить launcherx, пока мы были в фоне.
        vm.refreshSystemStatus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Фолбэк «обучения» кнопки, если служба не активна: ловим то, что дошло до Activity.
        if (KeyEventBus.learning.value && code !in KeyEventBus.NON_BINDABLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                KeyEventBus.learning.value = false
                KeyEventBus.learned.tryEmit(code)
            }
            return true
        }
        // MEDIA_*, громкость, SETTINGS (панель Google TV), ассистент UI не обрабатывает (ни один
        // onKeyEvent их не поглощает), поэтому они проходят в систему как обычно.
        // MENU и OK обрабатывают плитки, D-pad - сетка главного экрана.
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_HOME_KEY = "dev.glasslauncher.tv.extra.HOME_KEY"
    }
}
