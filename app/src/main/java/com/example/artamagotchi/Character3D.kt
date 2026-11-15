package com.example.artamagotchi

import android.opengl.Matrix
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Разовые анимации-реакции. */
enum class Reaction(val seconds: Float) {
    HAPPY(0.9f),    // подпрыгивает
    EAT(0.55f),     // кивает — откусывает
    TALK(1.6f),     // покачивает головой
    REFUSE(1.0f)    // мотает головой
}

/**
 * 3D-персонаж на столе: где стоит, какого роста, куда повёрнут и в какой позе.
 *
 * Сам ничего не рисует — каждый кадр считает матрицу модели и параметры позы,
 * а рисует их ModelRenderer. Положение в мире даёт якорь ARCore.
 */
class Character3D {

    /** Рост персонажа в метрах. */
    var height = 0.4f

    /** Высота шеи в координатах модели. */
    var neckY = 0.83f

    // Длительные состояния — их включает игра
    var sleeping = false
    var sad = false
    var dancing = false
    var anticipating = false

    /** Персонаж спрятан (в кабинке). */
    var hidden = false

    private var anchor: Anchor? = null
    val isPlaced: Boolean get() = anchor != null
    var isVisible = false
        private set

    val modelMatrix = FloatArray(16)
    val shadowMatrix = FloatArray(16)

    /** Поза для шейдера: [наклон головы, поворот головы, наклон головы вбок, наклон корпуса]. */
    val pose = FloatArray(4)
    var sway = 0f
        private set
    var breath = 0f
        private set

    val position = FloatArray(3)
    private var yaw = 0f              // поворот вокруг вертикали, радианы
    private var time = 0f
    private var age = 0f
    private var lie = 0f              // 0 — стоит, 1 — лежит
    private var sleepYaw = 0f
    private var sleepYawSet = false

    /** Персонаж уже лёг (можно рисовать одеяло). */
    val lying: Boolean get() = lie > 0.75f
    private var reaction: Reaction? = null
    private var reactionTime = 0f

    fun placeAt(newAnchor: Anchor) {
        anchor?.detach()
        anchor = newAnchor
        age = 0f
        isVisible = false
    }

    fun play(r: Reaction) {
        reaction = r
        reactionTime = 0f
    }

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

        // Поворачиваемся лицом к камере (только вокруг вертикали и плавно). Спящий не вертится.
        // Ложась спать, один раз разворачиваемся боком к камере и дальше не вертимся.
        val dx = cameraPose.tx() - position[0]
        val dz = cameraPose.tz() - position[2]
        if (sleeping && !sleepYawSet) {
            sleepYaw = atan2(dx, dz) + (PI / 2).toFloat()
            sleepYawSet = true
        }
        if (!sleeping) sleepYawSet = false
        run {
            if (dx * dx + dz * dz > 1e-6f) {
                var diff = (if (sleeping) sleepYaw else atan2(dx, dz)) - yaw
                while (diff > PI) diff -= (2 * PI).toFloat()
                while (diff < -PI) diff += (2 * PI).toFloat()
                yaw += diff * min(1f, dt * 4f)
            }
        }

        // Плавно ложимся и встаём
        val lieTarget = if (sleeping) 1f else 0f
        lie += (lieTarget - lie) * min(1f, dt * 4f)

        // --- Поза ---
        var headPitch = 0.03f * sin(time * 0.5f + 1f)
        var headYaw = 0f
        var headRoll = 0.04f * sin(time * 0.7f)
        var lean = 0f
        var hop = 0f
        var squash = 0f
        sway = 0.015f * sin(time * 0.9f)
        breath = 0.025f * sin(time * 1.8f)

        if (sleeping) {
            headPitch = 0f; headRoll = 0.08f; sway = 0f
            breath = 0.04f * sin(time * 1.0f)
        } else if (dancing) {
            sway = 0.07f * sin(time * 6f)
            headRoll = 0.14f * sin(time * 6f)
            hop = abs(sin(time * 6f)) * 0.05f
        } else if (sad) {
            lean = 0.16f
            headPitch = 0.28f
            sway = 0.008f * sin(time * 0.5f)
        }
        if (anticipating && !sleeping) {
            lean += 0.14f
            headPitch += 0.06f
        }

        reaction?.let { r ->
            reactionTime += dt
            val k = reactionTime / r.seconds
            if (k >= 1f) {
                reaction = null
            } else when (r) {
                Reaction.HAPPY -> {
                    hop = abs(sin(k * PI.toFloat() * 2f)) * 0.09f * (1f - k * 0.5f)
                    squash = 0.06f * sin(k * PI.toFloat() * 4f)
                    headPitch -= 0.12f
                }
                Reaction.EAT -> {
                    headPitch += 0.32f * sin(k * PI.toFloat())
                    lean += 0.10f
                }
                Reaction.TALK -> {
                    headRoll = 0.10f * sin(k * PI.toFloat() * 4f)
                    headPitch += 0.06f * sin(k * PI.toFloat() * 6f)
                }
                Reaction.REFUSE -> headYaw = 0.45f * sin(k * PI.toFloat() * 4f) * (1f - k)
            }
        }
        pose[0] = headPitch; pose[1] = headYaw; pose[2] = headRoll; pose[3] = lean

        // --- Матрица модели: перенос * поворот к камере * «лечь» * масштаб ---
        val appear = easeOutBack(min(age / 0.4f, 1f))
        val s = height * appear
        Matrix.setIdentityM(modelMatrix, 0)
        // Лёжа модель опирается спиной: приподнимаем на толщину тела
        Matrix.translateM(modelMatrix, 0, position[0], position[1] + hop * height + lie * 0.11f * height, position[2])
        Matrix.rotateM(modelMatrix, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, -90f * lie, 1f, 0f, 0f)
        Matrix.scaleM(modelMatrix, 0, s * (1f - squash * 0.5f), s * (1f + squash), s * (1f - squash * 0.5f))

        buildShadow()
        isVisible = true
    }

    /** Тень: квадрат, лежащий на столе под персонажем. */
    private fun buildShadow() {
        val size = height * (0.45f + 0.5f * lie)
        val m = shadowMatrix
        // Лёжа тело уходит «назад» от ног — сдвигаем тень к середине тела
        val back = lie * 0.45f * height
        val cx = position[0] - sin(yaw) * back
        val cz = position[2] - cos(yaw) * back
        m[0] = size; m[1] = 0f; m[2] = 0f; m[3] = 0f
        m[4] = 0f; m[5] = 0f; m[6] = -size; m[7] = 0f
        m[8] = 0f; m[9] = 1f; m[10] = 0f; m[11] = 0f
        m[12] = cx; m[13] = position[1] + 0.001f; m[14] = cz + size * 0.5f; m[15] = 1f
    }

    /** Точка рта/головы в мире — сюда подносят еду и отсюда летят частицы. */
    fun getHeadPosition(out: FloatArray) {
        val fx = sin(yaw); val fz = cos(yaw)      // направление «вперёд» персонажа
        if (lie > 0.5f) {
            out[0] = position[0] - fx * 0.9f * height
            out[1] = position[1] + 0.25f * height
            out[2] = position[2] - fz * 0.9f * height
        } else {
            out[0] = position[0] + fx * 0.07f * height
            out[1] = position[1] + 0.88f * height
            out[2] = position[2] + fz * 0.07f * height
        }
    }

    /** Попал ли луч в персонажа (проверка по вертикальному цилиндру или, если лежит, по шару). */
    fun hitTest(o: FloatArray, d: FloatArray): Boolean {
        if (!isVisible || hidden) return false
        if (lie > 0.5f) {
            val cx = position[0] - sin(yaw) * 0.45f * height
            val cz = position[2] - cos(yaw) * 0.45f * height
            return rayHitsSphere(o, d, cx, position[1] + 0.1f * height, cz, 0.5f * height)
        }
        // Ближайшая к оси персонажа точка луча (в проекции на горизонталь)
        val dd = d[0] * d[0] + d[2] * d[2]
        if (dd < 1e-8f) return false
        val t = ((position[0] - o[0]) * d[0] + (position[2] - o[2]) * d[2]) / dd
        if (t < 0f) return false
        val px = o[0] + d[0] * t - position[0]
        val pz = o[2] + d[2] * t - position[2]
        val py = o[1] + d[1] * t - position[1]
        val radius = 0.2f * height + 0.02f
        return sqrt(px * px + pz * pz) < radius && py > -0.03f && py < height * 1.05f
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val p = t - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }

    companion object {
        /** Пересекает ли луч (начало o, единичное направление d) шар. */
        fun rayHitsSphere(o: FloatArray, d: FloatArray, cx: Float, cy: Float, cz: Float, r: Float): Boolean {
            val lx = cx - o[0]; val ly = cy - o[1]; val lz = cz - o[2]
            val t = lx * d[0] + ly * d[1] + lz * d[2]
            if (t < 0f) return false
            val dist2 = lx * lx + ly * ly + lz * lz - t * t
            return dist2 < r * r
        }
    }
}
