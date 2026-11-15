package com.example.artamagotchi

import android.content.Context
import android.graphics.Bitmap
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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Что сейчас происходит — по этому состоянию Activity показывает подсказку. */
enum class ArStatus { NOT_TRACKING, SEARCHING_PLANE, READY_TO_PLACE, PLACED }

/** Вид кабинки. */
enum class Cabin { SHOWER, TOILET }

/** События сцены. Вызываются из GL-потока — в Activity их нужно переносить в главный поток. */
interface SceneListener {
    fun onStatus(status: ArStatus)
    fun onPlaced()
    fun onCharacterTapped()
    fun onFoodEaten(food: Food)
    fun onCabinDone(cabin: Cabin)
    fun onStarCaught()
}

/**
 * Главный класс отрисовки и «режиссёр» сцены. GLSurfaceView вызывает его в отдельном GL-потоке.
 *
 * Каждый кадр: берём у ARCore кадр камеры и положение телефона, рисуем камеру фоном,
 * обновляем сцену (персонаж, еда в руке, кабинка, мини-игра) и рисуем её.
 *
 * Методы с пометкой «из любого потока» не меняют сцену сразу, а кладут команду
 * в очередь — она выполнится в GL-потоке в начале следующего кадра.
 */
class ArRenderer(
    private val context: Context,
    private val hero: Hero,
    heroHeight: Float,
    private val listener: SceneListener
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    /** Стоит ли персонаж на столе (читается из главного потока). */
    @Volatile
    var isCharacterPlaced = false
        private set

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val quadRenderer = QuadRenderer()
    private val modelRenderer = ModelRenderer()
    private val particles = Particles()
    private val character = Character3D().apply { height = heroHeight; neckY = hero.neckY }

    private var heroMesh: Mesh? = null
    private val foodMeshes = arrayOfNulls<Mesh>(Food.entries.size)
    private val cabinMeshes = arrayOfNulls<Mesh>(Cabin.entries.size)
    private var beddingMesh: Mesh? = null
    private var shadowTextureId = 0

    private val pendingTaps = ConcurrentLinkedQueue<FloatArray>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    // --- Кормление с руки ---
    private var heldFood: Food? = null      // еда, которую игрок «держит» перед камерой
    private var bitesLeft = 0               // > 0 — персонаж уже ест
    private var biteTimer = 0f
    private val foodPos = FloatArray(3)

    // --- Кабинка ---
    private var cabin: Cabin? = null
    private var cabinTime = 0f

    // --- Мини-игра ---
    private class Star(val x: Float, val y: Float, val z: Float) { var age = 0f }
    private var gameActive = false
    private val stars = ArrayList<Star>()
    private var starTimer = 0f

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)
    private val inverseViewProjection = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    private val ndcPoint = FloatArray(4)
    private val nearPoint = FloatArray(4)
    private val farPoint = FloatArray(4)
    private val rayOrigin = FloatArray(3)
    private val rayDirection = FloatArray(3)
    private val headPosition = FloatArray(3)
    private val lightDir = FloatArray(3)
    private val camPos = FloatArray(3)
    private val handOffset = floatArrayOf(0f, -0.07f, -0.32f)   // «рука»: чуть ниже центра экрана, 32 см от телефона

    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile
    private var viewportChanged = false
    private var lastFrameNanos = 0L
    private var lastStatus: ArStatus? = null
    private var time = 0f

    // ------------------------------------------------------------------------
    // Команды — можно вызывать из любого потока
    // ------------------------------------------------------------------------

    fun queueTap(x: Float, y: Float) { pendingTaps.offer(floatArrayOf(x, y)) }

    fun setHeroHeight(meters: Float) { commands.offer { character.height = meters } }

    fun react(reaction: Reaction, stars: Boolean = false) {
        commands.offer {
            character.play(reaction)
            if (stars && character.isVisible) emitAtHead(ParticleKind.STAR, 6)
        }
    }

    /** Игрок берёт еду «в руку»: она появляется перед камерой. null — убрать еду. */
    fun holdFood(food: Food?) {
        commands.offer {
            heldFood = food
            bitesLeft = 0
            character.anticipating = food != null
        }
    }

    fun setSleeping(sleeping: Boolean) { commands.offer { character.sleeping = sleeping } }
    fun setSad(sad: Boolean) { commands.offer { character.sad = sad } }

    fun startCabin(type: Cabin) {
        commands.offer {
            if (cabin == null && character.isVisible) {
                cabin = type
                cabinTime = 0f
            }
        }
    }

    fun setGame(active: Boolean) {
        commands.offer {
            gameActive = active
            character.dancing = active
            stars.clear()
            starTimer = 0f
        }
    }

    // ------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        quadRenderer.createOnGlThread()
        modelRenderer.createOnGlThread()
        particles.createOnGlThread()
        shadowTextureId = createShadowTexture()

        heroMesh = Mesh.fromAsset(context, hero.meshAsset)
        for (food in Food.entries) foodMeshes[food.ordinal] = Shapes.food(food)
        cabinMeshes[Cabin.SHOWER.ordinal] = Shapes.cabin(shower = true)
        cabinMeshes[Cabin.TOILET.ordinal] = Shapes.cabin(shower = false)
        beddingMesh = Shapes.bedding()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val arSession = session ?: return

        try {
            if (viewportChanged) {
                viewportChanged = false
                arSession.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            }
            arSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = arSession.update()
            val camera = frame.camera

            // Фон — картинка с камеры (без теста глубины, чтобы не мешала 3D-моделям)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            backgroundRenderer.draw(frame)

            val dt = frameDeltaSeconds()
            time += dt

            if (camera.trackingState != TrackingState.TRACKING) {
                pendingTaps.clear()
                reportStatus(ArStatus.NOT_TRACKING)
                return
            }

            camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE, FAR_PLANE)
            camera.getViewMatrix(viewMatrix, 0)
            val cameraPose = camera.displayOrientedPose
            camPos[0] = cameraPose.tx(); camPos[1] = cameraPose.ty(); camPos[2] = cameraPose.tz()

            while (true) { (commands.poll() ?: break).invoke() }
            while (true) {
                val tap = pendingTaps.poll() ?: break
                handleTap(tap[0], tap[1], frame, camera)
            }

            // --- Логика сцены ---
            val wasPlaced = character.isPlaced
            character.update(dt, camera.pose)
            isCharacterPlaced = character.isPlaced
            updateFeeding(dt, cameraPose)
            updateCabin(dt)
            updateGame(dt)
            if (character.sleeping && character.isVisible && Random.nextFloat() < dt * 0.7f) emitAtHead(ParticleKind.ZZZ, 1)
            particles.update(dt)

            // --- Отрисовка ---
            val planes = arSession.getAllTrackables(Plane::class.java)
            val hasPlane = planes.any { PlaneRenderer.isUsable(it) }
            if (!character.isPlaced) planeRenderer.draw(planes, viewMatrix, projectionMatrix)

            val heroShown = character.isVisible && !character.hidden
            if (heroShown) {
                quadRenderer.begin(viewMatrix, projectionMatrix)
                quadRenderer.draw(shadowTextureId, character.shadowMatrix, 1f)
                quadRenderer.end()
            }

            // Свет: сверху и со стороны камеры — так лицо персонажа всегда освещено
            val dx = camPos[0] - character.position[0]
            val dz = camPos[2] - character.position[2]
            val dl = max(sqrt(dx * dx + dz * dz), 1e-4f)
            normalize(lightDir, dx / dl * 0.55f + dz / dl * 0.3f, 0.75f, dz / dl * 0.55f - dx / dl * 0.3f)

            modelRenderer.begin(viewMatrix, projectionMatrix, lightDir, camPos, if (character.sleeping) 0.6f else 1f)
            val mesh = heroMesh
            if (heroShown && mesh != null) {
                modelRenderer.draw(mesh, character.modelMatrix, character.pose, character.sway, character.breath, character.neckY, outline = true)
            }
            // Спящий укрыт одеялом
            val bedding = beddingMesh
            if (heroShown && character.lying && bedding != null) modelRenderer.draw(bedding, character.modelMatrix)
            drawCabin()
            drawFood()
            modelRenderer.end()

            // Частицы и звёзды: с тестом глубины (прячутся за моделью), но сами глубину не пишут
            GLES20.glDepthMask(false)
            quadRenderer.begin(viewMatrix, projectionMatrix)
            for (star in stars) {
                val k = star.age / STAR_LIFE
                val pulse = 1f + 0.15f * sin(star.age * 10f)
                val size = starSize() * pulse * min(1f, star.age / 0.15f)
                Particles.billboard(tempMatrix, viewMatrix, star.x, star.y, star.z, size)
                quadRenderer.draw(particles.starTexture, tempMatrix, if (k > 0.8f) (1f - k) / 0.2f else 1f)
            }
            particles.draw(quadRenderer, viewMatrix)
            quadRenderer.end()
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)

            if (!wasPlaced && character.isPlaced) listener.onPlaced()
            reportStatus(
                when {
                    character.isPlaced -> ArStatus.PLACED
                    hasPlane -> ArStatus.READY_TO_PLACE
                    else -> ArStatus.SEARCHING_PLANE
                }
            )
        } catch (e: SessionPausedException) {
            // Сессию поставили на паузу между кадрами — этот кадр пропускаем
        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка при отрисовке кадра", t)
        }
    }

    // ------------------------------------------------------------------------
    // Кормление с руки
    // ------------------------------------------------------------------------

    /**
     * Еда «лежит на ладони» перед телефоном. Игрок подносит телефон (руку) к персонажу;
     * когда еда оказывается у рта — персонаж откусывает три раза и съедает её.
     */
    private fun updateFeeding(dt: Float, cameraPose: com.google.ar.core.Pose) {
        val food = heldFood ?: return
        if (!character.isVisible || character.hidden) return
        character.getHeadPosition(headPosition)

        if (bitesLeft == 0) {
            val p = cameraPose.transformPoint(handOffset)
            foodPos[0] = p[0]; foodPos[1] = p[1]; foodPos[2] = p[2]
            val dx = foodPos[0] - headPosition[0]
            val dy = foodPos[1] - headPosition[1]
            val dz = foodPos[2] - headPosition[2]
            val reach = max(0.10f, character.height * 0.30f)
            if (sqrt(dx * dx + dy * dy + dz * dz) < reach) {
                bitesLeft = BITES
                biteTimer = 0f
                character.play(Reaction.EAT)
            }
        } else {
            // Во время еды кусок «прилипает» ко рту
            val k = min(1f, dt * 8f)
            for (i in 0..2) foodPos[i] += (headPosition[i] - foodPos[i]) * k
            biteTimer += dt
            if (biteTimer >= Reaction.EAT.seconds) {
                biteTimer = 0f
                bitesLeft--
                if (bitesLeft > 0) {
                    character.play(Reaction.EAT)
                } else {
                    heldFood = null
                    character.anticipating = false
                    character.play(Reaction.HAPPY)
                    emitAtHead(ParticleKind.STAR, 6)
                    listener.onFoodEaten(food)
                }
            }
        }
    }

    private fun drawFood() {
        val food = heldFood ?: return
        val mesh = foodMeshes[food.ordinal] ?: return
        // Размер еды зависит от роста персонажа; с каждым укусом она уменьшается
        val base = min(0.16f, max(0.05f, character.height * 0.16f))
        val size = base * if (bitesLeft > 0) (0.35f + 0.65f * bitesLeft / BITES) else 1f
        Matrix.setIdentityM(tempMatrix, 0)
        Matrix.translateM(tempMatrix, 0, foodPos[0], foodPos[1] - size * 0.4f, foodPos[2])
        Matrix.rotateM(tempMatrix, 0, time * 40f, 0f, 1f, 0f)
        Matrix.rotateM(tempMatrix, 0, 18f, 1f, 0f, 0f)
        Matrix.scaleM(tempMatrix, 0, size, size, size)
        modelRenderer.draw(mesh, tempMatrix)
    }

    // ------------------------------------------------------------------------
    // Кабинка (душ и туалет)
    // ------------------------------------------------------------------------

    /** Кабинка вырастает вокруг персонажа, он скрыт внутри; потом кабинка исчезает. */
    private fun updateCabin(dt: Float) {
        val type = cabin ?: return
        cabinTime += dt
        character.hidden = cabinTime > CABIN_GROW && cabinTime < CABIN_SECONDS - CABIN_GROW
        if (type == Cabin.SHOWER && character.hidden && Random.nextFloat() < dt * 6f) {
            headPosition[0] = character.position[0]
            headPosition[1] = character.position[1] + character.height * 1.15f
            headPosition[2] = character.position[2]
            particles.emit(ParticleKind.BUBBLE, headPosition, character.height, 1)
        }
        if (cabinTime >= CABIN_SECONDS) {
            cabin = null
            character.hidden = false
            character.play(Reaction.HAPPY)
            emitAtHead(ParticleKind.STAR, 5)
            listener.onCabinDone(type)
        }
    }

    private fun drawCabin() {
        val type = cabin ?: return
        val mesh = cabinMeshes[type.ordinal] ?: return
        val grow = min(1f, min(cabinTime, CABIN_SECONDS - cabinTime) / CABIN_GROW)
        val s = character.height * max(0.01f, grow)
        Matrix.setIdentityM(tempMatrix, 0)
        Matrix.translateM(tempMatrix, 0, character.position[0], character.position[1], character.position[2])
        // Дверью/шторкой к камере
        val dx = camPos[0] - character.position[0]
        val dz = camPos[2] - character.position[2]
        Matrix.rotateM(tempMatrix, 0, Math.toDegrees(kotlin.math.atan2(dx, dz).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(tempMatrix, 0, s, s, s)
        modelRenderer.draw(mesh, tempMatrix)
    }

    // ------------------------------------------------------------------------
    // Мини-игра «Поймай звёзды»
    // ------------------------------------------------------------------------

    private fun starSize() = max(0.04f, character.height * 0.2f)

    private fun updateGame(dt: Float) {
        if (!gameActive || !character.isVisible) return
        val iterator = stars.iterator()
        while (iterator.hasNext()) {
            val star = iterator.next()
            star.age += dt
            if (star.age > STAR_LIFE) iterator.remove()
        }
        starTimer -= dt
        if (starTimer <= 0f && stars.size < 4) {
            starTimer = 0.55f + Random.nextFloat() * 0.35f
            // Звезда появляется вокруг персонажа на случайной высоте
            val h = character.height
            val angle = Random.nextFloat() * 6.283f
            val radius = max(0.10f, h * (0.45f + Random.nextFloat() * 0.55f))
            stars.add(
                Star(
                    character.position[0] + cos(angle) * radius,
                    character.position[1] + h * (0.25f + Random.nextFloat() * 0.9f),
                    character.position[2] + sin(angle) * radius
                )
            )
        }
    }

    // ------------------------------------------------------------------------
    // Тапы
    // ------------------------------------------------------------------------

    private fun handleTap(x: Float, y: Float, frame: Frame, camera: Camera) {
        val hasRay = computeTapRay(x, y)

        // 1. Мини-игра: ловим звёзды (зона попадания чуть больше самой звезды)
        if (gameActive && hasRay) {
            val hit = stars.firstOrNull { Character3D.rayHitsSphere(rayOrigin, rayDirection, it.x, it.y, it.z, starSize() * 0.9f) }
            if (hit != null) {
                stars.remove(hit)
                headPosition[0] = hit.x; headPosition[1] = hit.y; headPosition[2] = hit.z
                particles.emit(ParticleKind.STAR, headPosition, character.height * 0.6f, 4)
                listener.onStarCaught()
            }
            return
        }

        // 2. Тап по персонажу — погладить
        if (hasRay && character.hitTest(rayOrigin, rayDirection)) {
            listener.onCharacterTapped()
            return
        }

        // 3. Тап по столу — поставить или переставить (не во время сна, еды и кабинки)
        if (character.sleeping || cabin != null || bitesLeft > 0) return
        for (hit in frame.hitTest(x, y)) {
            val trackable = hit.trackable
            val hitPose = hit.hitPose
            if (trackable is Plane && PlaneRenderer.isUsable(trackable) &&
                trackable.isPoseInPolygon(hitPose) && camera.pose.ty() > hitPose.ty()
            ) {
                character.placeAt(hit.createAnchor())
                break
            }
        }
    }

    private fun emitAtHead(kind: ParticleKind, count: Int) {
        character.getHeadPosition(headPosition)
        headPosition[1] += character.height * 0.15f
        particles.emit(kind, headPosition, character.height, count)
    }

    private fun normalize(out: FloatArray, x: Float, y: Float, z: Float) {
        val len = max(sqrt(x * x + y * y + z * z), 1e-6f)
        out[0] = x / len; out[1] = y / len; out[2] = z / len
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
            listener.onStatus(status)
        }
    }

    private companion object {
        const val TAG = "ArRenderer"
        const val NEAR_PLANE = 0.05f
        const val FAR_PLANE = 50f

        const val BITES = 3                 // за сколько укусов съедается еда
        const val CABIN_SECONDS = 6f        // сколько длится душ/туалет
        const val CABIN_GROW = 0.4f         // время появления и исчезновения кабинки
        const val STAR_LIFE = 1.7f          // сколько секунд звезда ждёт тапа
    }
}
