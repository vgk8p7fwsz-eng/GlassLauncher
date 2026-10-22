package dev.glasslauncher.tv.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * Ручное управление фокусом на главном экране.
 *
 * Стандартный поиск фокуса Compose на сетке с рядами разной длины ведёт себя «по геометрии» и
 * непредсказуемо прыгает. Здесь фокус адресуется ключом элемента: навигация вычисляет целевой ключ,
 * кладёт его в [pendingKey], а плитка с этим ключом сама запрашивает фокус, как только скомпонована
 * (в том числе после прокрутки LazyColumn к ещё не созданному ряду).
 */
@Stable
class HomeFocus {
    private val requesters = HashMap<String, FocusRequester>()

    /** Последний сфокусированный ключ (сохраняется, когда фокус уходит в меню/настройки - для восстановления). */
    var focusedKey by mutableStateOf<String?>(null)

    /** Ключ, у которого фокус прямо сейчас; null - фокуса на главном экране нет. */
    var focusedNow by mutableStateOf<String?>(null)
    var pendingKey by mutableStateOf<String?>(null)

    /** Колонка, запомненная при горизонтальном движении; сохраняется при переходах вверх/вниз. */
    var desiredColumn = 0

    /** Направление последнего перемещения фокуса - для parallax-блика на плитке. */
    var lastDx by mutableFloatStateOf(0f)
    var lastDy by mutableFloatStateOf(0f)

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun go(key: String, dx: Int, dy: Int) {
        lastDx = dx.toFloat()
        lastDy = dy.toFloat()
        pendingKey = key
    }
}
