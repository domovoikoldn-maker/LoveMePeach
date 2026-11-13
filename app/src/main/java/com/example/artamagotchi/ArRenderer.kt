package com.example.artamagotchi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Display
import android.view.Surface
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.SessionPausedException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Что сейчас происходит — по этому состоянию Activity показывает подсказку. */
enum class ArStatus {
    /** ARCore ещё не понял, где находится телефон (или потерял ориентацию). */
    NOT_TRACKING,

    /** Трекинг работает, но ни одной горизонтальной поверхности пока не найдено. */
    SEARCHING_PLANE,

    /** Поверхность найдена — можно ставить персонажа. */
    READY_TO_PLACE,

    /** Персонаж стоит на столе. */
    PLACED
}

/**
 * Главный класс отрисовки. GLSurfaceView вызывает его методы в отдельном GL-потоке.
 *
 * Каждый кадр (onDrawFrame):
 *   1. берём у ARCore свежий кадр: картинку камеры и положение телефона в комнате;
 *   2. рисуем картинку камеры как фон;
 *   3. обрабатываем тапы (погладить персонажа / поставить его на стол);
 *   4. рисуем сетку найденных плоскостей;
 *   5. рисуем тень, персонажа и сердечки.
 *
 * @param characterDrawable картинка персонажа (PNG из res/drawable-nodpi)
 * @param characterHeight   рост персонажа на столе, метры
 * @param onStatusChanged   вызывается из GL-потока, когда меняется состояние
 * @param onCharacterTapped вызывается из GL-потока, когда тапнули по персонажу
 */
class ArRenderer(
    private val context: Context,
    private val characterDrawable: Int,
    characterHeight: Float,
    private val onStatusChanged: (ArStatus) -> Unit,
    private val onCharacterTapped: () -> Unit
) : GLSurfaceView.Renderer {

    /**
     * Сессия ARCore. Activity записывает её сюда после запуска и обнуляет при закрытии.
     * @Volatile — потому что пишет главный поток, а читает GL-поток.
     */
    @Volatile
    var session: Session? = null

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val quadRenderer = QuadRenderer()
    private val character = SpriteCharacter().apply { heightMeters = characterHeight }
    private val hearts = HeartParticles()
    private var shadowTextureId = 0

    // Тапы приходят из главного потока, а обрабатывать их можно только в GL-потоке,
    // когда на руках есть текущий кадр ARCore. Поэтому складываем их в потокобезопасную очередь.
    private val pendingTaps = ConcurrentLinkedQueue<FloatArray>()

    /** Сколько «реакций» (пульсация + сердечки) запросили кнопки ухода. */
    private val pendingReactions = AtomicInteger(0)

    /** Стоит ли персонаж на столе (читается из главного потока). */
    @Volatile
    var isCharacterPlaced = false
        private set

    private val viewMatrix = FloatArray(16)            // мир -> камера
    private val projectionMatrix = FloatArray(16)      // камера -> экран (перспектива)
    private val viewProjectionMatrix = FloatArray(16)
    private val inverseViewProjection = FloatArray(16)

    // Временные массивы — заведены один раз, чтобы не создавать мусор каждый кадр
    private val ndcPoint = FloatArray(4)
    private val nearPoint = FloatArray(4)
    private val farPoint = FloatArray(4)
    private val rayOrigin = FloatArray(3)
    private val rayDirection = FloatArray(3)
    private val headPosition = FloatArray(3)

    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile
    private var viewportChanged = false

    private var lastFrameNanos = 0L
    private var lastStatus: ArStatus? = null

    /** Просит персонажа обрадоваться: пульсация и сердечки. Можно вызывать из любого потока. */
    fun queueReaction() {
        pendingReactions.incrementAndGet()
    }

    /** Запоминает тап (координаты в пикселях экрана). Можно вызывать из любого потока. */
    fun queueTap(x: Float, y: Float) {
        pendingTaps.offer(floatArrayOf(x, y))
    }

    // ------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ------------------------------------------------------------------------

    /**
     * GL-контекст создан (в том числе пересоздан после потери).
     * Здесь создаётся всё, что живёт в видеопамяти: шейдеры и текстуры.
     */
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // В сцене только плоские полупрозрачные картинки, и рисуем мы их в правильном
        // порядке (сначала дальнее), поэтому тест глубины не нужен.
        // Появятся настоящие 3D-модели — его придётся включить.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        quadRenderer.createOnGlThread()
        hearts.createOnGlThread()
        loadCharacterTexture()
        shadowTextureId = createShadowTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Сессии ещё нет (например, не дали разрешение на камеру) — экран просто чёрный
        val arSession = session ?: return

        try {
            // ARCore должен знать размер и поворот экрана, чтобы правильно
            // обрезать картинку камеры и посчитать матрицу проекции
            if (viewportChanged) {
                viewportChanged = false
                arSession.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            }

            // Сообщаем, в какую текстуру писать кадр камеры, и получаем новый кадр.
            // update() ждёт следующего кадра камеры, поэтому отрисовка идёт с её частотой (обычно 30 к/с).
            arSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = arSession.update()
            val camera = frame.camera

            // 1. Фон — картинка с камеры
            backgroundRenderer.draw(frame)

            val dt = frameDeltaSeconds()

            // Пока ARCore не понимает, где телефон, матрицы камеры недостоверны —
            // AR-объекты в такие кадры не рисуем
            if (camera.trackingState != TrackingState.TRACKING) {
                pendingTaps.clear()
                reportStatus(ArStatus.NOT_TRACKING)
                return
            }

            // Матрицы камеры. 0.05 и 50 — ближняя и дальняя границы видимости в метрах.
            camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE, FAR_PLANE)
            camera.getViewMatrix(viewMatrix, 0)

            // 2. Тапы
            while (true) {
                val tap = pendingTaps.poll() ?: break
                handleTap(tap[0], tap[1], frame, camera)
            }

            // 3. Логика: положение и анимация персонажа, движение сердечек
            character.update(dt, camera.pose, viewMatrix)
            isCharacterPlaced = character.isPlaced
            if (pendingReactions.getAndSet(0) > 0 && character.isVisible) celebrate()
            hearts.update(dt)

            // 4. Сетка на найденных плоскостях
            val planes = arSession.getAllTrackables(Plane::class.java)
            val hasPlane = planes.any { PlaneRenderer.isUsable(it) }
            val showGrid = !(HIDE_GRID_WHEN_PLACED && character.isPlaced)
            if (showGrid) {
                planeRenderer.draw(planes, viewMatrix, projectionMatrix)
            }

            // 5. Спрайты: сначала тень на столе, потом персонаж, поверх — сердечки
            quadRenderer.begin(viewMatrix, projectionMatrix)
            if (character.isVisible) {
                quadRenderer.draw(shadowTextureId, character.shadowMatrix, 1f)
                quadRenderer.draw(character.textureId, character.modelMatrix, 1f)
            }
            hearts.draw(quadRenderer, viewMatrix)
            quadRenderer.end()

            reportStatus(
                when {
                    character.isPlaced -> ArStatus.PLACED
                    hasPlane -> ArStatus.READY_TO_PLACE
                    else -> ArStatus.SEARCHING_PLANE
                }
            )
        } catch (e: SessionPausedException) {
            // Сессию поставили на паузу между кадрами — этот кадр просто пропускаем
        } catch (t: Throwable) {
            // Не даём одной ошибке в кадре уронить приложение; причина будет в Logcat
            Log.e(TAG, "Ошибка при отрисовке кадра", t)
        }
    }

    // ------------------------------------------------------------------------
    // Тапы
    // ------------------------------------------------------------------------

    private fun handleTap(x: Float, y: Float, frame: Frame, camera: Camera) {
        // 1. Попали по персонажу? Тогда гладим: пульсация + сердечки.
        if (character.isVisible && computeTapRay(x, y) && character.hitTest(rayOrigin, rayDirection)) {
            celebrate()
            onCharacterTapped()
            return
        }

        // 2. Иначе ищем, в какую точку стола попал тап.
        // hitTest пускает луч из точки экрана и возвращает пересечения с тем,
        // что ARCore знает о комнате, от ближних к дальним.
        for (hit in frame.hitTest(x, y)) {
            val trackable = hit.trackable
            val hitPose = hit.hitPose
            if (trackable is Plane &&
                PlaneRenderer.isUsable(trackable) &&
                trackable.isPoseInPolygon(hitPose) &&   // точка внутри найденной части стола
                camera.pose.ty() > hitPose.ty()         // камера выше стола, а не смотрит на него снизу
            ) {
                // Якорь «прибивает» точку к реальной поверхности
                character.placeAt(hit.createAnchor())
                break
            }
        }
    }

    /** Пульсация и сердечки над головой. */
    private fun celebrate() {
        character.pet()
        character.getHeadPosition(headPosition)
        hearts.emit(headPosition, spread = character.widthMeters * 0.3f)
    }

    /**
     * Превращает точку экрана в луч в мировых координатах.
     * Результат — в rayOrigin (начало) и rayDirection (единичное направление).
     *
     * Для этого точку экрана переводим в нормализованные координаты OpenGL (от -1 до 1)
     * и обратной матрицей (проекция * вид) «разворачиваем» обратно в мир: один раз
     * на ближней границе видимости, второй — на дальней. Через две точки проходит луч.
     */
    private fun computeTapRay(x: Float, y: Float): Boolean {
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        if (!Matrix.invertM(inverseViewProjection, 0, viewProjectionMatrix, 0)) return false

        ndcPoint[0] = 2f * x / viewportWidth - 1f
        ndcPoint[1] = 1f - 2f * y / viewportHeight // на экране Y растёт вниз, в OpenGL — вверх
        ndcPoint[3] = 1f

        ndcPoint[2] = -1f // ближняя граница
        Matrix.multiplyMV(nearPoint, 0, inverseViewProjection, 0, ndcPoint, 0)
        ndcPoint[2] = 1f  // дальняя граница
        Matrix.multiplyMV(farPoint, 0, inverseViewProjection, 0, ndcPoint, 0)

        if (abs(nearPoint[3]) < 1e-8f || abs(farPoint[3]) < 1e-8f) return false

        for (i in 0..2) {
            // Перспективное деление: из однородных координат в обычные
            nearPoint[i] /= nearPoint[3]
            farPoint[i] /= farPoint[3]
        }
        val dx = farPoint[0] - nearPoint[0]
        val dy = farPoint[1] - nearPoint[1]
        val dz = farPoint[2] - nearPoint[2]
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1e-6f) return false

        rayOrigin[0] = nearPoint[0]
        rayOrigin[1] = nearPoint[1]
        rayOrigin[2] = nearPoint[2]
        rayDirection[0] = dx / length
        rayDirection[1] = dy / length
        rayDirection[2] = dz / length
        return true
    }

    // ------------------------------------------------------------------------
    // Текстуры
    // ------------------------------------------------------------------------

    /** Загружает PNG персонажа из res/drawable-nodpi. */
    private fun loadCharacterTexture() {
        // inScaled = false: берём картинку пиксель в пиксель, без подгонки под плотность экрана
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeResource(context.resources, characterDrawable, options)
            ?: throw IllegalStateException("Не удалось прочитать картинку персонажа")

        character.aspect = bitmap.width.toFloat() / bitmap.height
        character.textureId = GlUtil.createTexture(bitmap, PIXEL_ART)
        bitmap.recycle()
    }

    /** Мягкое круглое пятно: чёрное в центре, прозрачное к краям. */
    private fun createShadowTexture(): Int {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val half = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            half, half, half,
            intArrayOf(Color.argb(140, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            null,
            Shader.TileMode.CLAMP
        )
        Canvas(bitmap).drawCircle(half, half, half, paint)

        val textureId = GlUtil.createTexture(bitmap, pixelArt = false)
        bitmap.recycle()
        return textureId
    }

    // ------------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------------

    /** Время с прошлого кадра в секундах — чтобы анимации шли с одной скоростью на любом телефоне. */
    private fun frameDeltaSeconds(): Float {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        // После паузы разница может быть огромной — ограничиваем, чтобы анимации не «прыгали»
        return min(dt, 0.1f)
    }

    /** Текущий поворот экрана (Surface.ROTATION_0 / 90 / 180 / 270). */
    private fun displayRotation(): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    private fun reportStatus(status: ArStatus) {
        if (status != lastStatus) {
            lastStatus = status
            onStatusChanged(status)
        }
    }

    private companion object {
        const val TAG = "ArRenderer"

        // ---- Настройки, которые захочется менять в первую очередь ----

        /** false — обычная сглаженная картинка; true — пиксель-арт с чёткими пикселями при увеличении. */
        val PIXEL_ART = false

        /** Прятать сетку после того, как персонаж поставлен. Тапы по столу при этом продолжают работать. */
        val HIDE_GRID_WHEN_PLACED = true

        const val NEAR_PLANE = 0.05f
        const val FAR_PLANE = 50f
    }
}
