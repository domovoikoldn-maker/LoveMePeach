package com.example.artamagotchi

import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.example.artamagotchi.Skeleton.Companion.ARM_L
import com.example.artamagotchi.Skeleton.Companion.ARM_R
import com.example.artamagotchi.Skeleton.Companion.CHEST
import com.example.artamagotchi.Skeleton.Companion.FOREARM_L
import com.example.artamagotchi.Skeleton.Companion.FOREARM_R
import com.example.artamagotchi.Skeleton.Companion.HEAD
import com.example.artamagotchi.Skeleton.Companion.HIPS
import com.example.artamagotchi.Skeleton.Companion.SHIN_L
import com.example.artamagotchi.Skeleton.Companion.SHIN_R
import com.example.artamagotchi.Skeleton.Companion.SPINE
import com.example.artamagotchi.Skeleton.Companion.THIGH_L
import com.example.artamagotchi.Skeleton.Companion.THIGH_R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Разовые движения. Длительность — в секундах. */
enum class Reaction(val seconds: Float) {
    HAPPY(0.75f),   // прыжок от радости
    EAT(0.55f),     // наклон к еде и «ам»
    TALK(1.6f),     // говорит, покачивая головой
    REFUSE(1.0f),   // мотает головой
    WAVE(1.4f),     // машет рукой
    BOW(1.2f),      // поклон
    SQUAT(1.0f),    // присед
    SPIN(1.0f),     // кружится
    KICK(0.6f)      // пинает мяч
}

/** Выражения лица — номер картинки в текстуре лица. */
object Face {
    const val NEUTRAL = 0
    const val CLOSED = 1
    const val HAPPY = 2
    const val SAD = 3
    const val TALK = 4
    const val SURPRISED = 5
}

/**
 * 3D-персонаж со скелетом: где стоит, какого роста и в какой позе.
 *
 * Поза — это углы 12 костей плюс сдвиг всего тела. Каждый кадр персонаж собирает
 * «целевую» позу (состояние + разовое движение + дыхание и покачивание) и плавно
 * подтягивает к ней текущую — поэтому любые переходы получаются мягкими.
 */
class Character3D(joints: FloatArray) {

    /** Рост персонажа в метрах. */
    var height = 0.4f

    // Длительные состояния — их включает игра
    var sleeping = false
    var sad = false
    var dancing = false
    var sitting = false
    var anticipating = false

    /** Персонаж спрятан (в кабинке). */
    var hidden = false

    /** Куда смотреть (точка в мире) или null — смотреть в камеру. */
    var lookTarget: FloatArray? = null

    val skeleton = Skeleton(joints)

    /** Текущее выражение лица (номер картинки). */
    var face = Face.NEUTRAL
        private set

    private var anchor: Anchor? = null
    val isPlaced: Boolean get() = anchor != null
    var isVisible = false
        private set

    val modelMatrix = FloatArray(16)
    val shadowMatrix = FloatArray(16)
    val position = FloatArray(3)

    /** Персонаж уже лёг (можно рисовать одеяло). */
    val lying: Boolean get() = lie > 0.75f

    private var yaw = 0f
    private var sleepYaw = 0f
    private var sleepYawSet = false
    private var time = 0f
    private var age = 0f
    private var lie = 0f

    private var reaction: Reaction? = null
    private var reactionTime = 0f
    private var talkFace = false

    private var blinkTimer = 3f
    private var glanceTimer = 2f
    private var glanceYaw = 0f
    private var headYawNow = 0f

    // Поза: 36 углов (по 3 на кость) + 3 числа сдвига тела
    private val target = FloatArray(POSE_SIZE)
    private val current = FloatArray(POSE_SIZE)
    private val angles = FloatArray(Skeleton.BONES * 3)
    private val root = FloatArray(3)

    private val tmp4 = FloatArray(4)
    private val tmp4b = FloatArray(4)
    private val tmpMatrix = FloatArray(16)

    fun placeAt(newAnchor: Anchor) {
        anchor?.detach()
        anchor = newAnchor
        age = 0f
        isVisible = false
    }

    fun play(r: Reaction) {
        reaction = r
        reactionTime = 0f
        talkFace = r == Reaction.TALK
    }

    fun isBusy(): Boolean = reaction != null

    fun update(dt: Float, cameraPose: Pose) {
        isVisible = false
        val a = anchor ?: return
        val state = a.trackingState
        if (state == TrackingState.STOPPED) {
            a.detach()
            anchor = null
            return
        }
        if (state != TrackingState.TRACKING) return

        val p = a.pose
        position[0] = p.tx(); position[1] = p.ty(); position[2] = p.tz()
        time += dt
        age += dt

        turnToCamera(dt, cameraPose)
        lie += ((if (sleeping) 1f else 0f) - lie) * min(1f, dt * 3f)

        buildTargetPose(dt)
        // Плавное «дотягивание» позы: быстро во время движений, мягче в покое
        val rate = if (reaction != null) 16f else 7f
        val k = 1f - exp(-rate * dt)
        for (i in 0 until POSE_SIZE) current[i] += (target[i] - current[i]) * k
        System.arraycopy(current, 0, angles, 0, angles.size)
        root[0] = current[36]; root[1] = current[37]; root[2] = current[38]
        skeleton.compute(angles, root)

        updateFace(dt)
        buildModelMatrix()
        buildShadow()
        isVisible = true
    }

    // ------------------------------------------------------------------------
    // Позы
    // ------------------------------------------------------------------------

    private fun buildTargetPose(dt: Float) {
        target.fill(0f)
        val t = time

        when {
            sleeping -> {
                // Лёжа на спине: руки вдоль тела, лёгкий поворот головы, медленное дыхание
                set(HEAD, 0f, 0f, 0.25f)
                set(ARM_L, 0f, 0f, 0.12f); set(ARM_R, 0f, 0f, -0.12f)
                add(CHEST, -0.03f * sin(t * 1.2f), 0f, 0f)
            }
            sitting -> {
                // Сидит на столе, ноги вперёд, руки опираются сзади
                set(THIGH_L, -1.5f, 0f, 0.07f); set(THIGH_R, -1.5f, 0f, -0.07f)
                set(SHIN_L, 0.15f + 0.08f * sin(t * 1.3f), 0f, 0f)
                set(SHIN_R, 0.15f + 0.08f * sin(t * 1.3f + 2f), 0f, 0f)   // болтает ногами
                set(SPINE, -0.08f, 0f, 0f)
                set(ARM_L, -0.35f, 0f, 0.18f); set(ARM_R, -0.35f, 0f, -0.18f)
                set(HEAD, 0.05f, 0f, 0.06f * sin(t * 0.6f))
                target[37] = -0.47f
            }
            dancing -> danceMove(t)
            else -> {
                // Стоит: переносит вес с ноги на ногу, корпус и голова покачиваются
                val shift = sin(t * 0.8f)
                set(HIPS, 0f, 0.04f * sin(t * 0.4f), 0.035f * shift)
                set(SPINE, 0.015f * sin(t * 0.5f), 0f, -0.025f * shift)
                set(CHEST, 0f, 0f, -0.015f * shift)
                set(THIGH_L, 0f, 0f, -0.035f * shift); set(THIGH_R, 0f, 0f, -0.035f * shift)
                set(SHIN_L, 0.06f * max(0f, shift), 0f, 0f); set(SHIN_R, 0.06f * max(0f, -shift), 0f, 0f)
                set(ARM_L, 0.03f * sin(t * 0.8f + 1f), 0f, 0.06f); set(ARM_R, 0.03f * sin(t * 0.8f), 0f, -0.06f)
                set(FOREARM_L, -0.08f, 0f, 0f); set(FOREARM_R, -0.08f, 0f, 0f)
                set(HEAD, 0.03f * sin(t * 0.5f + 1f), 0f, 0.05f * sin(t * 0.7f))
                if (sad) {
                    add(SPINE, 0.18f, 0f, 0f); add(CHEST, 0.12f, 0f, 0f); add(HEAD, 0.30f, 0f, 0f)
                    add(ARM_L, 0f, 0f, -0.04f); add(ARM_R, 0f, 0f, 0.04f)
                }
                if (anticipating) {
                    // Тянется к еде
                    add(SPINE, 0.12f, 0f, 0f); add(CHEST, 0.08f, 0f, 0f); add(HEAD, -0.10f, 0f, 0f)
                    add(ARM_L, -0.9f, 0f, -0.1f); add(ARM_R, -0.9f, 0f, 0.1f)
                    add(FOREARM_L, -0.5f, 0f, 0f); add(FOREARM_R, -0.5f, 0f, 0f)
                }
            }
        }

        // Дыхание: грудь чуть поднимается
        if (!sleeping) add(CHEST, -0.015f * sin(t * 1.8f), 0f, 0f)

        // Взгляд: голова и немного грудь поворачиваются к цели (мяч, звезда) или «оглядываются»
        lookAround(dt)
        add(HEAD, 0f, headYawNow * 0.7f, 0f)
        add(CHEST, 0f, headYawNow * 0.3f, 0f)

        reaction?.let { r ->
            reactionTime += dt
            val k = reactionTime / r.seconds
            if (k >= 1f) {
                reaction = null
                talkFace = false
            } else {
                reactionPose(r, k)
            }
        }
    }

    private fun danceMove(t: Float) {
        val beat = t * 5.0f                       // примерно 120 ударов в минуту
        val s = sin(beat)
        val bounce = abs(sin(beat))
        set(HIPS, 0f, 0.25f * sin(beat * 0.5f), 0.12f * s)
        set(SPINE, 0.05f, 0f, -0.10f * s)
        set(CHEST, 0f, 0.15f * sin(beat * 0.5f), -0.06f * s)
        set(HEAD, -0.05f, 0f, 0.18f * s)
        set(ARM_L, -0.3f, 0f, 1.2f + 0.6f * s); set(FOREARM_L, -0.6f, 0f, 0.4f)
        set(ARM_R, -0.3f, 0f, -1.2f + 0.6f * s); set(FOREARM_R, -0.6f, 0f, -0.4f)
        set(THIGH_L, -0.25f * max(0f, s), 0f, 0.05f); set(SHIN_L, 0.5f * max(0f, s), 0f, 0f)
        set(THIGH_R, -0.25f * max(0f, -s), 0f, -0.05f); set(SHIN_R, 0.5f * max(0f, -s), 0f, 0f)
        target[37] = -0.025f + 0.03f * bounce
    }

    /** Разовые движения. k — от 0 до 1 за время движения. */
    private fun reactionPose(r: Reaction, k: Float) {
        val pi = PI.toFloat()
        when (r) {
            Reaction.HAPPY -> {
                // Присел — прыгнул (руки вверх) — приземлился
                val crouch = bell(k, 0f, 0.25f) + bell(k, 0.75f, 1f) * 0.6f
                val air = bell(k, 0.2f, 0.8f)
                legsBend(0.9f * crouch + 0.3f * air)
                target[37] += -0.08f * crouch + 0.22f * air
                add(ARM_L, -0.3f * air, 0f, 2.2f * air); add(ARM_R, -0.3f * air, 0f, -2.2f * air)
                add(HEAD, -0.15f * air, 0f, 0f)
            }
            Reaction.EAT -> {
                val b = sin(k * pi)
                add(SPINE, 0.15f * b, 0f, 0f); add(HEAD, 0.25f * b, 0f, 0f)
            }
            Reaction.TALK -> {
                add(HEAD, 0.06f * sin(k * pi * 6f), 0f, 0.10f * sin(k * pi * 3f))
                add(ARM_R, -0.6f * bell(k, 0.1f, 0.9f), 0f, -0.3f * bell(k, 0.1f, 0.9f))
                add(FOREARM_R, -0.9f * bell(k, 0.1f, 0.9f), 0f, 0f)
            }
            Reaction.REFUSE -> {
                add(HEAD, 0f, 0.5f * sin(k * pi * 4f) * (1f - k), 0f)
                add(ARM_L, -0.6f * bell(k, 0f, 1f), 0f, 0f); add(FOREARM_L, -1.2f * bell(k, 0f, 1f), 0f, 0f)
            }
            Reaction.WAVE -> {
                val up = bell(k, 0f, 1f)
                add(ARM_L, 0f, 0f, 2.5f * up); add(FOREARM_L, 0f, 0f, (0.5f + 0.5f * sin(k * pi * 8f)) * up)
                add(HEAD, 0f, 0f, -0.12f * up); add(SPINE, 0f, 0f, -0.05f * up)
            }
            Reaction.BOW -> {
                val b = bell(k, 0f, 1f)
                add(SPINE, 0.45f * b, 0f, 0f); add(CHEST, 0.35f * b, 0f, 0f); add(HEAD, 0.2f * b, 0f, 0f)
                add(ARM_L, -0.4f * b, 0f, 0f); add(ARM_R, -0.4f * b, 0f, 0f)
            }
            Reaction.SQUAT -> {
                val b = bell(k, 0f, 1f)
                legsBend(1.6f * b)
                target[37] += -0.2f * b
                add(SPINE, 0.35f * b, 0f, 0f)
                add(ARM_L, -1.2f * b, 0f, 0f); add(ARM_R, -1.2f * b, 0f, 0f)
            }
            Reaction.SPIN -> {
                // Поворот всем телом — через сдвиг угла «рыскания» в матрице модели
                spin = 2f * pi * smooth(k)
                add(ARM_L, 0f, 0f, 1.3f * bell(k, 0f, 1f)); add(ARM_R, 0f, 0f, -1.3f * bell(k, 0f, 1f))
            }
            Reaction.KICK -> {
                val b = bell(k, 0f, 1f)
                add(THIGH_R, -1.1f * b, 0f, 0f); add(SHIN_R, 0.4f * b * (1f - k), 0f, 0f)
                add(SPINE, -0.12f * b, 0f, 0f)
                add(ARM_L, -0.5f * b, 0f, 0.4f * b); add(ARM_R, 0.5f * b, 0f, -0.4f * b)
            }
        }
        if (r != Reaction.SPIN) spin = 0f
    }

    private var spin = 0f

    /** Сгибает обе ноги: бёдра вперёд, колени назад — тело опускается. */
    private fun legsBend(amount: Float) {
        add(THIGH_L, -amount, 0f, 0f); add(THIGH_R, -amount, 0f, 0f)
        add(SHIN_L, amount * 1.6f, 0f, 0f); add(SHIN_R, amount * 1.6f, 0f, 0f)
        add(SPINE, amount * 0.35f, 0f, 0f)
    }

    private fun lookAround(dt: Float) {
        var wanted = 0f
        val look = lookTarget
        if (look != null && !sleeping) {
            val dx = look[0] - position[0]
            val dz = look[2] - position[2]
            var diff = atan2(dx, dz) - yaw
            while (diff > PI) diff -= (2 * PI).toFloat()
            while (diff < -PI) diff += (2 * PI).toFloat()
            wanted = diff.coerceIn(-1.1f, 1.1f)
        } else if (!sleeping) {
            glanceTimer -= dt
            if (glanceTimer <= 0f) {
                glanceTimer = 2f + Random.nextFloat() * 4f
                glanceYaw = if (Random.nextFloat() < 0.5f) 0f else (Random.nextFloat() - 0.5f) * 0.9f
            }
            wanted = glanceYaw
        }
        headYawNow += (wanted - headYawNow) * min(1f, dt * 5f)
    }

    private fun updateFace(dt: Float) {
        blinkTimer -= dt
        if (blinkTimer < -0.12f) blinkTimer = 2.5f + Random.nextFloat() * 3f
        val blinking = blinkTimer < 0f
        face = when {
            sleeping -> Face.CLOSED
            reaction == Reaction.EAT -> if (reactionTime / Reaction.EAT.seconds < 0.5f) Face.TALK else Face.HAPPY
            talkFace -> if (sin(reactionTime * 18f) > 0f) Face.TALK else Face.NEUTRAL
            reaction == Reaction.HAPPY || reaction == Reaction.SPIN || dancing -> Face.HAPPY
            reaction == Reaction.REFUSE -> Face.SAD
            anticipating -> Face.SURPRISED
            blinking -> Face.CLOSED
            sad -> Face.SAD
            sitting -> Face.HAPPY
            else -> Face.NEUTRAL
        }
    }

    private fun set(bone: Int, x: Float, y: Float, z: Float) {
        target[bone * 3] = x; target[bone * 3 + 1] = y; target[bone * 3 + 2] = z
    }

    private fun add(bone: Int, x: Float, y: Float, z: Float) {
        target[bone * 3] += x; target[bone * 3 + 1] += y; target[bone * 3 + 2] += z
    }

    // ------------------------------------------------------------------------
    // Положение в мире
    // ------------------------------------------------------------------------

    private fun turnToCamera(dt: Float, cameraPose: Pose) {
        val dx = cameraPose.tx() - position[0]
        val dz = cameraPose.tz() - position[2]
        if (sleeping && !sleepYawSet) {
            // Ложится боком к камере, чтобы было видно лицо
            sleepYaw = atan2(dx, dz) + (PI / 2).toFloat()
            sleepYawSet = true
        }
        if (!sleeping) sleepYawSet = false
        if (dx * dx + dz * dz < 1e-6f) return
        var diff = (if (sleeping) sleepYaw else atan2(dx, dz)) - yaw
        while (diff > PI) diff -= (2 * PI).toFloat()
        while (diff < -PI) diff += (2 * PI).toFloat()
        // Когда смотрит на мяч, телом к камере поворачивается медленнее
        yaw += diff * min(1f, dt * if (lookTarget != null) 1.5f else 3f)
    }

    private fun buildModelMatrix() {
        val appear = easeOutBack(min(age / 0.4f, 1f))
        val s = height * appear
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, position[0], position[1] + lie * 0.11f * height, position[2])
        Matrix.rotateM(modelMatrix, 0, Math.toDegrees((yaw + spin).toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, -90f * lie, 1f, 0f, 0f)
        Matrix.scaleM(modelMatrix, 0, s, s, s)
    }

    private fun buildShadow() {
        val size = height * (0.45f + 0.5f * lie + if (sitting) 0.25f else 0f)
        val m = shadowMatrix
        val back = lie * 0.45f * height - (if (sitting) 0.12f * height else 0f)
        val cx = position[0] - sin(yaw) * back
        val cz = position[2] - cos(yaw) * back
        val lift = max(0f, current[37]) * 1.6f                 // в прыжке тень меньше
        val sz = size / (1f + lift)
        m[0] = sz; m[1] = 0f; m[2] = 0f; m[3] = 0f
        m[4] = 0f; m[5] = 0f; m[6] = -sz; m[7] = 0f
        m[8] = 0f; m[9] = 1f; m[10] = 0f; m[11] = 0f
        m[12] = cx; m[13] = position[1] + 0.001f; m[14] = cz + sz * 0.5f; m[15] = 1f
    }

    /** Точка модели (в координатах модели, рост = 1), сдвинутая костью и перенесённая в мир. */
    private fun bonePoint(bone: Int, x: Float, y: Float, z: Float, out: FloatArray) {
        tmp4[0] = x; tmp4[1] = y; tmp4[2] = z; tmp4[3] = 1f
        Matrix.multiplyMV(tmp4b, 0, skeleton.matrices, bone * 16, tmp4, 0)
        Matrix.multiplyMV(tmp4, 0, modelMatrix, 0, tmp4b, 0)
        out[0] = tmp4[0]; out[1] = tmp4[1]; out[2] = tmp4[2]
    }

    /** Рот персонажа в мире — сюда подносят еду и отсюда летят частицы. */
    fun getHeadPosition(out: FloatArray) = bonePoint(HEAD, 0f, 0.865f, 0.06f, out)

    /** Ступня (для пинка мяча). */
    fun getFootPosition(out: FloatArray) = bonePoint(SHIN_R, -0.05f, 0.03f, 0.03f, out)

    /**
     * Попал ли луч в персонажа. Проверяем несколько шаров вдоль тела —
     * так попадание работает и стоя, и сидя, и лёжа.
     */
    fun hitTest(o: FloatArray, d: FloatArray): Boolean {
        if (!isVisible || hidden) return false
        val c = FloatArray(3)
        val parts = arrayOf(
            floatArrayOf(HEAD.toFloat(), 0f, 0.90f, 0f, 0.11f),
            floatArrayOf(CHEST.toFloat(), 0f, 0.74f, 0f, 0.12f),
            floatArrayOf(HIPS.toFloat(), 0f, 0.57f, 0f, 0.12f),
            floatArrayOf(THIGH_L.toFloat(), 0.05f, 0.40f, 0f, 0.09f),
            floatArrayOf(THIGH_R.toFloat(), -0.05f, 0.40f, 0f, 0.09f),
            floatArrayOf(SHIN_L.toFloat(), 0.05f, 0.15f, 0f, 0.08f),
            floatArrayOf(SHIN_R.toFloat(), -0.05f, 0.15f, 0f, 0.08f)
        )
        for (part in parts) {
            bonePoint(part[0].toInt(), part[1], part[2], part[3], c)
            if (rayHitsSphere(o, d, c[0], c[1], c[2], part[4] * height + 0.015f)) return true
        }
        return false
    }

    /**
     * Столкновение шара (мяча) с телом: если шар внутри одной из «капсул» тела,
     * выталкивает его наружу. Возвращает нормаль толчка в [push] и true.
     */
    fun collideSphere(p: FloatArray, r: Float, push: FloatArray): Boolean {
        if (!isVisible || hidden || lie > 0.5f) return false
        val c = FloatArray(3)
        val parts = floatArrayOf(0.90f, 0.11f, 0.72f, 0.13f, 0.55f, 0.13f, 0.30f, 0.12f, 0.08f, 0.12f)
        var hit = false
        for (i in parts.indices step 2) {
            bonePoint(if (parts[i] > 0.8f) HEAD else if (parts[i] > 0.6f) CHEST else if (parts[i] > 0.4f) HIPS else SHIN_L,
                0f, parts[i], 0f, c)
            c[0] = position[0] + (c[0] - position[0]) * 0.3f      // ось тела, а не левая нога
            c[2] = position[2] + (c[2] - position[2]) * 0.3f
            val rr = parts[i + 1] * height + r
            val dx = p[0] - c[0]; val dy = p[1] - c[1]; val dz = p[2] - c[2]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < rr && dist > 1e-5f) {
                val k = (rr - dist) / dist
                p[0] += dx * k; p[1] += dy * k; p[2] += dz * k
                push[0] = dx / dist; push[1] = dy / dist; push[2] = dz / dist
                hit = true
            }
        }
        return hit
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val p = t - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }

    companion object {
        const val POSE_SIZE = Skeleton.BONES * 3 + 3

        /** Плавный «колокол»: 0 вне [a, b], 1 в середине. */
        fun bell(k: Float, a: Float, b: Float): Float {
            if (k <= a || k >= b) return 0f
            return sin((k - a) / (b - a) * PI.toFloat())
        }

        fun smooth(k: Float): Float = k * k * (3f - 2f * k)

        fun rayHitsSphere(o: FloatArray, d: FloatArray, cx: Float, cy: Float, cz: Float, r: Float): Boolean {
            val lx = cx - o[0]; val ly = cy - o[1]; val lz = cz - o[2]
            val t = lx * d[0] + ly * d[1] + lz * d[2]
            if (t < 0f) return false
            val dist2 = lx * lx + ly * ly + lz * lz - t * t
            return dist2 < r * r
        }
    }
}
