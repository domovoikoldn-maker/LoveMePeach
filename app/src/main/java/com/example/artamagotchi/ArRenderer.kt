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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
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

/** Что сейчас за игра в сцене. */
enum class SceneGame { NONE, STARS, BALL, FREE_BALL, DANCE }

/** События сцены. Вызываются из GL-потока — в Activity их нужно переносить в главный поток. */
interface SceneListener {
    fun onStatus(status: ArStatus)
    fun onPlaced()
    fun onCharacterTapped()
    fun onFoodEaten(food: Food)
    fun onCabinDone(cabin: Cabin)
    fun onStarCaught()
    /** Мини-игра «Мячик»: удар по мячу (счёт) или мяч упал на стол (конец). */
    fun onBallJuggled()
    fun onBallDropped()
}

/**
 * Главный класс отрисовки и «режиссёр» сцены. GLSurfaceView вызывает его в отдельном GL-потоке.
 *
 * Каждый кадр: берём у ARCore кадр камеры и положение телефона, рисуем камеру фоном,
 * обновляем сцену (персонаж, физика, еда в руке, кабинка, мини-игры) и рисуем её.
 *
 * Методы «из любого потока» не меняют сцену сразу, а кладут команду в очередь —
 * она выполнится в GL-потоке в начале следующего кадра.
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
    private var character: Character3D? = null
    private var pendingHeight = heroHeight

    private var heroMesh: Mesh? = null
    private val foodMeshes = arrayOfNulls<Mesh>(Food.entries.size)
    private val cabinMeshes = arrayOfNulls<Mesh>(Cabin.entries.size)
    private var beddingMesh: Mesh? = null
    private var ballMesh: Mesh? = null
    private var faceTextureId = 0
    private var shadowTextureId = 0

    private val pendingTaps = ConcurrentLinkedQueue<FloatArray>()
    private val commands = ConcurrentLinkedQueue<(Character3D) -> Unit>()

    // --- Кормление с руки ---
    private var heldFood: Food? = null
    private var bitesLeft = 0
    private var biteTimer = 0f
    private val foodPos = FloatArray(3)

    /** Брошенная еда падает на стол, подпрыгивает и исчезает. */
    private class Dropped(val food: Food, val body: Body)
    private val dropped = ArrayList<Dropped>()

    // --- Кабинка ---
    private var cabin: Cabin? = null
    private var cabinTime = 0f

    // --- Игры ---
    private var game = SceneGame.NONE
    private class Star(val x: Float, val y: Float, val z: Float) { var age = 0f }
    private val stars = ArrayList<Star>()
    private var starTimer = 0f
    private var ball: Body? = null
    private var kickCooldown = 0f

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
    private val footPosition = FloatArray(3)
    private val lightDir = FloatArray(3)
    private val camPos = FloatArray(3)
    private val camForward = FloatArray(3)
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

    fun setHeroHeight(meters: Float) {
        pendingHeight = meters
        commands.offer { it.height = meters }
    }

    fun react(reaction: Reaction, stars: Boolean = false) {
        commands.offer {
            it.play(reaction)
            if (stars && it.isVisible) emitAtHead(ParticleKind.STAR, 6)
        }
    }

    /** Игрок берёт еду «в руку»: она появляется перед камерой. null — бросить еду (упадёт на стол). */
    fun holdFood(food: Food?) {
        commands.offer {
            val was = heldFood
            if (food == null && was != null && bitesLeft == 0 && it.isVisible) {
                val body = Body(foodSize(it) * 0.45f, bounce = 0.35f)
                body.set(foodPos[0], foodPos[1], foodPos[2])
                dropped.add(Dropped(was, body))
            }
            heldFood = food
            bitesLeft = 0
            it.anticipating = food != null
        }
    }

    fun setSleeping(sleeping: Boolean) { commands.offer { it.sleeping = sleeping; if (sleeping) it.sitting = false } }
    fun setSad(sad: Boolean) { commands.offer { it.sad = sad } }
    fun setSitting(sitting: Boolean) { commands.offer { it.sitting = sitting && !it.sleeping } }

    fun startCabin(type: Cabin) {
        commands.offer {
            if (cabin == null && it.isVisible) {
                cabin = type
                cabinTime = 0f
                it.sitting = false
            }
        }
    }

    /** Включает игру в сцене (или выключает — SceneGame.NONE). */
    fun setGame(newGame: SceneGame) {
        commands.offer {
            game = newGame
            it.dancing = newGame == SceneGame.STARS || newGame == SceneGame.DANCE
            it.sitting = false
            stars.clear()
            starTimer = 0f
            it.lookTarget = null
            if (newGame == SceneGame.BALL || newGame == SceneGame.FREE_BALL) spawnBall(it, high = newGame == SceneGame.BALL)
            else ball = null
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

        val mesh = Mesh.fromAsset(context, hero.meshAsset)
        heroMesh = mesh
        // Персонаж создаётся один раз; после потери GL-контекста сохраняем его состояние
        if (character == null) character = Character3D(mesh.joints!!).apply { height = pendingHeight }

        val options = BitmapFactory.Options().apply { inScaled = false }
        val faceBitmap = BitmapFactory.decodeResource(context.resources, hero.faceRes, options)
        faceTextureId = GlUtil.createTexture(faceBitmap, pixelArt = false)
        faceBitmap.recycle()

        for (food in Food.entries) foodMeshes[food.ordinal] = Shapes.food(food)
        cabinMeshes[Cabin.SHOWER.ordinal] = Shapes.cabin(shower = true)
        cabinMeshes[Cabin.TOILET.ordinal] = Shapes.cabin(shower = false)
        beddingMesh = Shapes.bedding()
        ballMesh = Shapes.ball()
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
        val ch = character ?: return

        try {
            if (viewportChanged) {
                viewportChanged = false
                arSession.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            }
            arSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = arSession.update()
            val camera = frame.camera

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
            // Направление взгляда камеры (третья строка матрицы вида со знаком минус)
            camForward[0] = -viewMatrix[2]; camForward[1] = -viewMatrix[6]; camForward[2] = -viewMatrix[10]

            while (true) { (commands.poll() ?: break).invoke(ch) }
            while (true) {
                val tap = pendingTaps.poll() ?: break
                handleTap(ch, tap[0], tap[1], frame, camera)
            }

            // --- Логика сцены ---
            val wasPlaced = ch.isPlaced
            ch.update(dt, camera.pose)
            isCharacterPlaced = ch.isPlaced
            updateFeeding(ch, dt, cameraPose)
            updateCabin(ch, dt)
            updateStars(ch, dt)
            updatePhysics(ch, dt)
            if (ch.sleeping && ch.isVisible && Random.nextFloat() < dt * 0.7f) emitAtHead(ParticleKind.ZZZ, 1)
            particles.update(dt)

            // --- Отрисовка ---
            val planes = arSession.getAllTrackables(Plane::class.java)
            val hasPlane = planes.any { PlaneRenderer.isUsable(it) }
            if (!ch.isPlaced) planeRenderer.draw(planes, viewMatrix, projectionMatrix)

            val heroShown = ch.isVisible && !ch.hidden
            drawShadows(ch, heroShown)

            // Свет: сверху и со стороны камеры — лицо персонажа всегда освещено
            val dx = camPos[0] - ch.position[0]
            val dz = camPos[2] - ch.position[2]
            val dl = max(sqrt(dx * dx + dz * dz), 1e-4f)
            normalize(lightDir, dx / dl * 0.55f + dz / dl * 0.3f, 0.75f, dz / dl * 0.55f - dx / dl * 0.3f)

            modelRenderer.begin(viewMatrix, projectionMatrix, lightDir, camPos, if (ch.sleeping) 0.6f else 1f)
            val mesh = heroMesh
            if (heroShown && mesh != null) {
                modelRenderer.draw(
                    mesh, ch.modelMatrix, ch.skeleton.matrices, outline = true,
                    faceTexture = faceTextureId, faceRect = hero.faceRect, faceCell = ch.face
                )
            }
            val bedding = beddingMesh
            if (heroShown && ch.lying && bedding != null) modelRenderer.draw(bedding, ch.modelMatrix)
            drawCabin(ch)
            drawFood(ch)
            drawBodies()
            modelRenderer.end()

            // Частицы и звёзды: с тестом глубины (прячутся за моделью), но сами глубину не пишут
            GLES20.glDepthMask(false)
            quadRenderer.begin(viewMatrix, projectionMatrix)
            for (star in stars) {
                val k = star.age / STAR_LIFE
                val pulse = 1f + 0.15f * sin(star.age * 10f)
                val size = starSize(ch) * pulse * min(1f, star.age / 0.15f)
                Particles.billboard(tempMatrix, viewMatrix, star.x, star.y, star.z, size)
                quadRenderer.draw(particles.starTexture, tempMatrix, if (k > 0.8f) (1f - k) / 0.2f else 1f)
            }
            particles.draw(quadRenderer, viewMatrix)
            quadRenderer.end()
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)

            if (!wasPlaced && ch.isPlaced) listener.onPlaced()
            reportStatus(
                when {
                    ch.isPlaced -> ArStatus.PLACED
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
    // Физика: мяч и брошенная еда
    // ------------------------------------------------------------------------

    /** Сила тяжести «по масштабу персонажа»: у маленького персонажа мир медленнее, играть удобнее. */
    private fun gravity(ch: Character3D) = 9.8f * (ch.height / 1.0f).coerceIn(0.25f, 1f)

    private fun ballRadius(ch: Character3D) = max(0.02f, ch.height * 0.09f)

    private fun spawnBall(ch: Character3D, high: Boolean) {
        val b = Body(ballRadius(ch), bounce = 0.72f)
        // Мяч появляется между персонажем и камерой
        val dx = camPos[0] - ch.position[0]
        val dz = camPos[2] - ch.position[2]
        val d = max(sqrt(dx * dx + dz * dz), 1e-4f)
        val off = ch.height * 0.45f
        b.set(ch.position[0] + dx / d * off, ch.position[1] + ch.height * (if (high) 1.3f else 0.6f), ch.position[2] + dz / d * off)
        ball = b
    }

    private fun updatePhysics(ch: Character3D, dt: Float) {
        if (!ch.isVisible) return
        val floor = ch.position[1]
        val g = gravity(ch)
        kickCooldown -= dt

        ball?.let { b ->
            // Мелкие шаги — чтобы быстрый мяч не проскакивал сквозь персонажа
            val sub = 3
            var hitHero = false
            repeat(sub) { if (b.step(dt / sub, floor, g, ch)) hitHero = true }
            ch.lookTarget = b.pos

            if (hitHero && kickCooldown <= 0f && b.speed() > 0.2f) {
                kickCooldown = 0.6f
                ch.play(Reaction.HAPPY)
            }

            // Свободная игра: если мяч подкатился к ногам — персонаж пинает его к игроку
            if (game == SceneGame.FREE_BALL && kickCooldown <= 0f && b.onFloor && !ch.isBusy()) {
                ch.getFootPosition(footPosition)
                val fx = b.pos[0] - ch.position[0]
                val fz = b.pos[2] - ch.position[2]
                if (sqrt(fx * fx + fz * fz) < ch.height * 0.35f) {
                    kickCooldown = 1.2f
                    ch.play(Reaction.KICK)
                    val tx = camPos[0] - b.pos[0]
                    val tz = camPos[2] - b.pos[2]
                    val tl = max(sqrt(tx * tx + tz * tz), 1e-4f)
                    val power = sqrt(g * ch.height) * 1.2f
                    b.vel[0] = tx / tl * power; b.vel[2] = tz / tl * power; b.vel[1] = power * 0.7f
                }
            }

            // Мини-игра «Мячик»: удар о стол — конец
            if (game == SceneGame.BALL && b.floorHits > 0) {
                game = SceneGame.NONE
                ch.lookTarget = null
                ch.sad = true
                listener.onBallDropped()
            }

            // Укатился далеко со стола — возвращаем
            val fx = b.pos[0] - ch.position[0]
            val fz = b.pos[2] - ch.position[2]
            if (sqrt(fx * fx + fz * fz) > ch.height * 2.5f + 0.3f) {
                if (game == SceneGame.BALL) {
                    game = SceneGame.NONE
                    ch.lookTarget = null
                    listener.onBallDropped()
                } else {
                    spawnBall(ch, high = true)
                }
            }
        }
        if (game != SceneGame.BALL && game != SceneGame.FREE_BALL) ball = null

        val it = dropped.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.body.step(dt, floor, g, ch)
            if (d.body.age > 2.5f) it.remove()
        }
    }

    private fun drawBodies() {
        val b = ball
        val mesh = ballMesh
        if (b != null && mesh != null) {
            Matrix.setIdentityM(tempMatrix, 0)
            Matrix.translateM(tempMatrix, 0, b.pos[0], b.pos[1], b.pos[2])
            // Катится в сторону движения
            val ax = b.vel[2]; val az = -b.vel[0]
            if (abs(ax) + abs(az) > 1e-4f) Matrix.rotateM(tempMatrix, 0, b.spinAngle, ax, 0f, az)
            val d = b.radius * 2f
            Matrix.scaleM(tempMatrix, 0, d, d, d)
            modelRenderer.draw(mesh, tempMatrix)
        }
        for (d in dropped) {
            val fm = foodMeshes[d.food.ordinal] ?: continue
            val fade = if (d.body.age > 2f) 1f - (d.body.age - 2f) / 0.5f else 1f
            val s = d.body.radius / 0.45f * max(0.01f, fade)
            Matrix.setIdentityM(tempMatrix, 0)
            Matrix.translateM(tempMatrix, 0, d.body.pos[0], d.body.pos[1] - d.body.radius, d.body.pos[2])
            Matrix.rotateM(tempMatrix, 0, d.body.spinAngle, 1f, 0f, 0.3f)
            Matrix.scaleM(tempMatrix, 0, s, s, s)
            modelRenderer.draw(fm, tempMatrix)
        }
    }

    /** Тени персонажа, мяча и упавшей еды — мягкие пятна на столе. */
    private fun drawShadows(ch: Character3D, heroShown: Boolean) {
        quadRenderer.begin(viewMatrix, projectionMatrix)
        if (heroShown) quadRenderer.draw(shadowTextureId, ch.shadowMatrix, 1f)
        ball?.let { flatShadow(it.pos, it.radius * 2.2f, ch.position[1], it.pos[1] - it.radius - ch.position[1], ch.height) }
        for (d in dropped) flatShadow(d.body.pos, d.body.radius * 2f, ch.position[1], 0f, ch.height)
        quadRenderer.end()
    }

    private fun flatShadow(p: FloatArray, size: Float, floorY: Float, heightAbove: Float, unit: Float) {
        val fade = (1f - heightAbove / max(unit, 0.05f)).coerceIn(0.2f, 1f)
        val s = size * (0.6f + 0.4f * fade)
        val m = tempMatrix
        m[0] = s; m[1] = 0f; m[2] = 0f; m[3] = 0f
        m[4] = 0f; m[5] = 0f; m[6] = -s; m[7] = 0f
        m[8] = 0f; m[9] = 1f; m[10] = 0f; m[11] = 0f
        m[12] = p[0]; m[13] = floorY + 0.0015f; m[14] = p[2] + s * 0.5f; m[15] = 1f
        quadRenderer.draw(shadowTextureId, m, fade)
    }

    // ------------------------------------------------------------------------
    // Кормление с руки
    // ------------------------------------------------------------------------

    private fun foodSize(ch: Character3D) = min(0.16f, max(0.05f, ch.height * 0.16f))

    /**
     * Еда «лежит на ладони» перед телефоном. Игрок подносит телефон к персонажу;
     * когда еда оказывается у рта — персонаж откусывает три раза и съедает её.
     */
    private fun updateFeeding(ch: Character3D, dt: Float, cameraPose: com.google.ar.core.Pose) {
        val food = heldFood ?: return
        if (!ch.isVisible || ch.hidden) return
        ch.getHeadPosition(headPosition)

        if (bitesLeft == 0) {
            val p = cameraPose.transformPoint(handOffset)
            foodPos[0] = p[0]; foodPos[1] = p[1]; foodPos[2] = p[2]
            ch.lookTarget = foodPos
            val dx = foodPos[0] - headPosition[0]
            val dy = foodPos[1] - headPosition[1]
            val dz = foodPos[2] - headPosition[2]
            val reach = max(0.10f, ch.height * 0.30f)
            if (sqrt(dx * dx + dy * dy + dz * dz) < reach) {
                bitesLeft = BITES
                biteTimer = 0f
                ch.play(Reaction.EAT)
            }
        } else {
            // Во время еды кусок держится у рта
            val k = min(1f, dt * 8f)
            for (i in 0..2) foodPos[i] += (headPosition[i] - foodPos[i]) * k
            biteTimer += dt
            if (biteTimer >= Reaction.EAT.seconds) {
                biteTimer = 0f
                bitesLeft--
                if (bitesLeft > 0) {
                    ch.play(Reaction.EAT)
                } else {
                    heldFood = null
                    ch.anticipating = false
                    ch.lookTarget = null
                    ch.play(Reaction.HAPPY)
                    emitAtHead(ParticleKind.STAR, 6)
                    listener.onFoodEaten(food)
                }
            }
        }
    }

    private fun drawFood(ch: Character3D) {
        val food = heldFood ?: return
        val mesh = foodMeshes[food.ordinal] ?: return
        val base = foodSize(ch)
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

    private fun updateCabin(ch: Character3D, dt: Float) {
        val type = cabin ?: return
        cabinTime += dt
        ch.hidden = cabinTime > CABIN_GROW && cabinTime < CABIN_SECONDS - CABIN_GROW
        if (type == Cabin.SHOWER && ch.hidden && Random.nextFloat() < dt * 6f) {
            headPosition[0] = ch.position[0]
            headPosition[1] = ch.position[1] + ch.height * 1.15f
            headPosition[2] = ch.position[2]
            particles.emit(ParticleKind.BUBBLE, headPosition, ch.height, 1)
        }
        if (cabinTime >= CABIN_SECONDS) {
            cabin = null
            ch.hidden = false
            ch.play(Reaction.HAPPY)
            emitAtHead(ParticleKind.STAR, 5)
            listener.onCabinDone(type)
        }
    }

    private fun drawCabin(ch: Character3D) {
        val type = cabin ?: return
        val mesh = cabinMeshes[type.ordinal] ?: return
        val grow = min(1f, min(cabinTime, CABIN_SECONDS - cabinTime) / CABIN_GROW)
        val s = ch.height * max(0.01f, grow)
        Matrix.setIdentityM(tempMatrix, 0)
        Matrix.translateM(tempMatrix, 0, ch.position[0], ch.position[1], ch.position[2])
        val dx = camPos[0] - ch.position[0]
        val dz = camPos[2] - ch.position[2]
        Matrix.rotateM(tempMatrix, 0, Math.toDegrees(atan2(dx, dz).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(tempMatrix, 0, s, s, s)
        modelRenderer.draw(mesh, tempMatrix)
    }

    // ------------------------------------------------------------------------
    // Мини-игра «Звездопад»
    // ------------------------------------------------------------------------

    private fun starSize(ch: Character3D) = max(0.04f, ch.height * 0.2f)

    private fun updateStars(ch: Character3D, dt: Float) {
        val iterator = stars.iterator()
        while (iterator.hasNext()) {
            val star = iterator.next()
            star.age += dt
            if (star.age > STAR_LIFE) iterator.remove()
        }
        if (game != SceneGame.STARS || !ch.isVisible) return
        starTimer -= dt
        if (starTimer <= 0f && stars.size < 4) {
            starTimer = 0.55f + Random.nextFloat() * 0.35f
            val h = ch.height
            val angle = Random.nextFloat() * 6.283f
            val radius = max(0.10f, h * (0.45f + Random.nextFloat() * 0.55f))
            stars.add(Star(ch.position[0] + cos(angle) * radius, ch.position[1] + h * (0.25f + Random.nextFloat() * 0.9f), ch.position[2] + sin(angle) * radius))
        }
    }

    // ------------------------------------------------------------------------
    // Тапы
    // ------------------------------------------------------------------------

    private fun handleTap(ch: Character3D, x: Float, y: Float, frame: Frame, camera: Camera) {
        val hasRay = computeTapRay(x, y)

        // 1. Звездопад: ловим звёзды
        if (game == SceneGame.STARS && hasRay) {
            val hit = stars.firstOrNull { Character3D.rayHitsSphere(rayOrigin, rayDirection, it.x, it.y, it.z, starSize(ch) * 0.9f) }
            if (hit != null) {
                stars.remove(hit)
                headPosition[0] = hit.x; headPosition[1] = hit.y; headPosition[2] = hit.z
                particles.emit(ParticleKind.STAR, headPosition, ch.height * 0.6f, 4)
                listener.onStarCaught()
            }
            return
        }

        // 2. Мяч: удар по мячу — подбрасываем вверх и чуть от камеры
        val b = ball
        if (b != null && hasRay && Character3D.rayHitsSphere(rayOrigin, rayDirection, b.pos[0], b.pos[1], b.pos[2], b.radius * 1.8f)) {
            val g = gravity(ch)
            val up = sqrt(2f * g * ch.height * 0.9f)          // долетит примерно до роста персонажа
            // Чуть в сторону персонажа, чтобы мяч не улетал со стола
            val tx = ch.position[0] - b.pos[0]
            val tz = ch.position[2] - b.pos[2]
            val side = 0.6f * sqrt(g * ch.height) / max(sqrt(tx * tx + tz * tz), ch.height)
            b.vel[0] = tx * side + (Random.nextFloat() - 0.5f) * 0.1f * up
            b.vel[2] = tz * side + (Random.nextFloat() - 0.5f) * 0.1f * up
            b.vel[1] = up
            headPosition[0] = b.pos[0]; headPosition[1] = b.pos[1]; headPosition[2] = b.pos[2]
            particles.emit(ParticleKind.STAR, headPosition, ch.height * 0.4f, 2)
            if (game == SceneGame.BALL) listener.onBallJuggled()
            return
        }
        if (game == SceneGame.BALL) return

        // 3. Тап по персонажу — погладить
        if (hasRay && ch.hitTest(rayOrigin, rayDirection)) {
            listener.onCharacterTapped()
            return
        }

        // 4. Тап по столу — поставить или переставить (не во время сна, еды, кабинки и игр)
        if (ch.sleeping || cabin != null || bitesLeft > 0 || game != SceneGame.NONE) return
        for (hit in frame.hitTest(x, y)) {
            val trackable = hit.trackable
            val hitPose = hit.hitPose
            if (trackable is Plane && PlaneRenderer.isUsable(trackable) &&
                trackable.isPoseInPolygon(hitPose) && camera.pose.ty() > hitPose.ty()
            ) {
                ch.placeAt(hit.createAnchor())
                break
            }
        }
    }

    private fun emitAtHead(kind: ParticleKind, count: Int) {
        val ch = character ?: return
        ch.getHeadPosition(headPosition)
        headPosition[1] += ch.height * 0.15f
        particles.emit(kind, headPosition, ch.height, count)
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
