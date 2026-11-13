package com.example.artamagotchi

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Как спрайт поворачивается к камере. */
enum class BillboardMode {
    /**
     * Спрайт стоит вертикально, как картонная фигурка, и вращается только вокруг
     * вертикальной оси. Если смотреть сверху, он сплющивается — зато действительно
     * «стоит на столе».
     */
    UPRIGHT,

    /**
     * Спрайт всегда строго параллелен экрану. Не искажается при взгляде сверху,
     * но при этом как бы «отклоняется» назад.
     */
    FACE_CAMERA
}

/**
 * Персонаж: 2D-спрайт, привязанный к точке на столе.
 *
 * Класс ничего не рисует сам. Он хранит состояние (где стоит, какая сейчас анимация)
 * и каждый кадр пересчитывает матрицу модели для QuadRenderer.
 *
 * Положение в мире даёт якорь ARCore (Anchor). Якорь — это точка, которую ARCore
 * удерживает на реальной поверхности и подправляет по мере уточнения карты комнаты.
 * Поэтому спрайт не «уезжает», когда вы двигаете телефон.
 */
class SpriteCharacter {

    // ---- Настройки ----

    /** Высота персонажа на столе, в метрах. */
    var heightMeters = 0.18f

    var billboardMode = BillboardMode.UPRIGHT

    /** Ширина картинки / высота. Заполняется при загрузке PNG. */
    var aspect = 1f

    /** id OpenGL-текстуры со спрайтом. */
    var textureId = 0

    // ---- Состояние ----

    private var anchor: Anchor? = null

    /** Персонаж уже поставлен на стол? */
    val isPlaced: Boolean
        get() = anchor != null

    /** Можно ли рисовать персонажа в этом кадре (якорь отслеживается). */
    var isVisible = false
        private set

    val widthMeters: Float
        get() = heightMeters * aspect

    /** Матрица модели спрайта — отдаётся в QuadRenderer.draw(). */
    val modelMatrix = FloatArray(16)

    /** Матрица модели для пятна тени под ногами. */
    val shadowMatrix = FloatArray(16)

    // Положение ног и три оси спрайта в мировых координатах
    private val position = FloatArray(3)
    private val right = floatArrayOf(1f, 0f, 0f)    // вправо по картинке
    private val up = floatArrayOf(0f, 1f, 0f)       // вверх по картинке
    private val forward = floatArrayOf(0f, 0f, 1f)  // «лицом» к камере

    // Текущий масштаб по ширине и высоте — им управляют анимации
    private var scaleX = 1f
    private var scaleY = 1f

    private var age = 0f          // секунд с момента появления
    private var pulseTime = -1f   // секунд с начала пульсации; меньше нуля — пульсации нет

    /** Ставит персонажа в новую точку (старый якорь освобождается). */
    fun placeAt(newAnchor: Anchor) {
        anchor?.detach()
        anchor = newAnchor
        age = 0f
        pulseTime = -1f
        isVisible = false
    }

    /** «Погладить»: запускает пульсацию. */
    fun pet() {
        pulseTime = 0f
    }

    /**
     * Вызывается каждый кадр: обновляет положение, поворот к камере и анимации.
     *
     * @param dt         время с прошлого кадра, секунды
     * @param cameraPose положение камеры в мире
     * @param viewMatrix матрица вида камеры (нужна для режима FACE_CAMERA)
     */
    fun update(dt: Float, cameraPose: Pose, viewMatrix: FloatArray) {
        isVisible = false
        val currentAnchor = anchor ?: return

        val state = currentAnchor.trackingState
        if (state == TrackingState.STOPPED) {
            // ARCore окончательно потерял эту точку — персонажа придётся ставить заново
            currentAnchor.detach()
            anchor = null
            return
        }
        if (state != TrackingState.TRACKING) return // временно потеряли — просто не рисуем

        // 1. Положение: точка на столе, где стоят «ноги»
        val pose = currentAnchor.pose
        position[0] = pose.tx()
        position[1] = pose.ty()
        position[2] = pose.tz()

        // 2. Поворот к камере (билборд)
        updateOrientation(cameraPose, viewMatrix)

        // 3. Анимации
        age += dt
        updateAnimation(dt)

        // 4. Матрицы для отрисовки
        buildModelMatrix()
        buildShadowMatrix()
        isVisible = true
    }

    private fun updateOrientation(cameraPose: Pose, viewMatrix: FloatArray) {
        when (billboardMode) {
            BillboardMode.UPRIGHT -> {
                // Направление от персонажа к камере, но только по горизонтали.
                // В ARCore ось Y мира всегда направлена вверх (против силы тяжести).
                val dx = cameraPose.tx() - position[0]
                val dz = cameraPose.tz() - position[2]
                val length = sqrt(dx * dx + dz * dz)
                // Если камера ровно над персонажем, направление не определено —
                // оставляем то, что было в прошлом кадре
                if (length > 1e-4f) {
                    forward[0] = dx / length
                    forward[1] = 0f
                    forward[2] = dz / length
                    // right = up x forward (векторное произведение), при up = (0, 1, 0)
                    right[0] = forward[2]
                    right[1] = 0f
                    right[2] = -forward[0]
                }
                up[0] = 0f
                up[1] = 1f
                up[2] = 0f
            }

            BillboardMode.FACE_CAMERA -> {
                // Строки матрицы вида — это оси самой камеры в мировых координатах.
                // Берём их как оси спрайта, и он становится параллелен экрану.
                right[0] = viewMatrix[0]; right[1] = viewMatrix[4]; right[2] = viewMatrix[8]
                up[0] = viewMatrix[1]; up[1] = viewMatrix[5]; up[2] = viewMatrix[9]
                forward[0] = viewMatrix[2]; forward[1] = viewMatrix[6]; forward[2] = viewMatrix[10]
            }
        }
    }

    private fun updateAnimation(dt: Float) {
        // Появление: «выпрыгивает» из нуля с небольшим перелётом
        val appear = easeOutBack(min(age / APPEAR_SECONDS, 1f))

        // Дыхание: едва заметное покачивание высоты, чтобы персонаж казался живым
        val breath = 1f + 0.012f * sin(age * 2.2f)

        // Пульсация после тапа: затухающие колебания
        var pulse = 0f
        if (pulseTime >= 0f) {
            pulseTime += dt
            if (pulseTime > PULSE_SECONDS) {
                pulseTime = -1f
            } else {
                pulse = PULSE_AMPLITUDE * exp(-PULSE_DAMPING * pulseTime) * sin(PULSE_SPEED * pulseTime)
            }
        }

        // Вытягиваемся вверх — становимся чуть уже, и наоборот («желе»).
        // Нужен обычный равномерный масштаб — замените на: scaleX = appear * (1f + pulse)
        scaleY = appear * breath * (1f + pulse)
        scaleX = appear * (1f - 0.5f * pulse)
    }

    /**
     * Матрица 4x4 по столбцам:
     *   столбец 0 — ось X прямоугольника (вправо), умноженная на ширину,
     *   столбец 1 — ось Y (вверх), умноженная на высоту,
     *   столбец 2 — ось Z (к камере),
     *   столбец 3 — положение ног в мире.
     */
    private fun buildModelMatrix() {
        val w = widthMeters * scaleX
        val h = heightMeters * scaleY
        val m = modelMatrix
        m[0] = right[0] * w; m[1] = right[1] * w; m[2] = right[2] * w; m[3] = 0f
        m[4] = up[0] * h; m[5] = up[1] * h; m[6] = up[2] * h; m[7] = 0f
        m[8] = forward[0]; m[9] = forward[1]; m[10] = forward[2]; m[11] = 0f
        m[12] = position[0]; m[13] = position[1]; m[14] = position[2]; m[15] = 1f
    }

    /** Тень — тот же прямоугольник, но положенный плашмя на стол и отцентрованный под ногами. */
    private fun buildShadowMatrix() {
        val size = widthMeters * 0.9f * scaleX
        val m = shadowMatrix
        // Ось X прямоугольника — вдоль мировой X
        m[0] = size; m[1] = 0f; m[2] = 0f; m[3] = 0f
        // Ось Y прямоугольника — вдоль мировой -Z, то есть он лежит горизонтально
        m[4] = 0f; m[5] = 0f; m[6] = -size; m[7] = 0f
        m[8] = 0f; m[9] = 1f; m[10] = 0f; m[11] = 0f
        // Прямоугольник «растёт» от нижней стороны, поэтому сдвигаем его на полразмера назад;
        // плюс миллиметр над столом
        m[12] = position[0]
        m[13] = position[1] + 0.001f
        m[14] = position[2] + size * 0.5f
        m[15] = 1f
    }

    /**
     * Попал ли луч (из точки тапа вглубь сцены) в прямоугольник спрайта.
     *
     * Ищем точку пересечения луча с плоскостью спрайта и проверяем,
     * лежит ли она внутри прямоугольника.
     * Идея на будущее: читать альфу картинки в точке попадания,
     * чтобы тапы по прозрачным местам не считались.
     */
    fun hitTest(rayOrigin: FloatArray, rayDirection: FloatArray): Boolean {
        if (!isVisible) return false
        val w = widthMeters * scaleX
        val h = heightMeters * scaleY
        if (w < 1e-5f || h < 1e-5f) return false

        // Плоскость спрайта проходит через position, её нормаль — forward
        val denominator = dot(rayDirection, forward)
        if (abs(denominator) < 1e-5f) return false // луч идёт вдоль плоскости

        val t = ((position[0] - rayOrigin[0]) * forward[0] +
            (position[1] - rayOrigin[1]) * forward[1] +
            (position[2] - rayOrigin[2]) * forward[2]) / denominator
        if (t < 0f) return false // плоскость позади камеры

        // Точка пересечения относительно ног персонажа
        val hx = rayOrigin[0] + rayDirection[0] * t - position[0]
        val hy = rayOrigin[1] + rayDirection[1] * t - position[1]
        val hz = rayOrigin[2] + rayDirection[2] * t - position[2]

        // Переводим в координаты единичного прямоугольника: X от -0.5 до 0.5, Y от 0 до 1
        val localX = (hx * right[0] + hy * right[1] + hz * right[2]) / w
        val localY = (hx * up[0] + hy * up[1] + hz * up[2]) / h
        return localX >= -0.5f && localX <= 0.5f && localY >= 0f && localY <= 1f
    }

    /** Записывает в [out] точку над головой персонажа (оттуда вылетают сердечки). */
    fun getHeadPosition(out: FloatArray) {
        val h = heightMeters * scaleY
        out[0] = position[0] + up[0] * h
        out[1] = position[1] + up[1] * h
        out[2] = position[2] + up[2] * h
    }

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Плавное появление с небольшим «перелётом» за 1 и возвратом. t от 0 до 1. */
    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val p = t - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }

    private companion object {
        const val APPEAR_SECONDS = 0.35f

        const val PULSE_SECONDS = 0.8f     // сколько длится пульсация
        const val PULSE_AMPLITUDE = 0.18f  // сила: 0.18 = до 18% от размера
        const val PULSE_DAMPING = 5f       // как быстро затухает
        const val PULSE_SPEED = 19f        // частота колебаний, радиан в секунду (около 3 в секунду)
    }
}
