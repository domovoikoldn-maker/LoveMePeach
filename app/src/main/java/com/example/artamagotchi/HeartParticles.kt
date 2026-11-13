package com.example.artamagotchi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Простая система частиц: сердечки, которые вылетают над головой персонажа,
 * поднимаются вверх, покачиваются и тают.
 *
 * Каждое сердечко — маленький текстурированный прямоугольник, всегда
 * параллельный экрану. Картинка сердечка рисуется прямо в коде
 * (см. createHeartBitmap), отдельный PNG не нужен.
 */
class HeartParticles {

    private class Particle(
        var x: Float, var y: Float, var z: Float,      // положение в мире, метры
        val vx: Float, val vy: Float, val vz: Float,   // скорость, метры в секунду
        val lifetime: Float,                           // сколько живёт, секунды
        val size: Float,                               // высота сердечка, метры
        val phase: Float                               // сдвиг покачивания, чтобы сердечки двигались вразнобой
    ) {
        var age = 0f
    }

    private val particles = ArrayList<Particle>()
    private val modelMatrix = FloatArray(16)
    private var textureId = 0

    /** Вызывать из onSurfaceCreated(). */
    fun createOnGlThread() {
        val bitmap = createHeartBitmap()
        textureId = GlUtil.createTexture(bitmap, pixelArt = false)
        bitmap.recycle()
    }

    /**
     * Выпускает [count] сердечек из точки [origin] (мировые координаты).
     * [spread] — разброс стартовых точек по горизонтали, метры.
     */
    fun emit(origin: FloatArray, spread: Float, count: Int = 6) {
        repeat(count) {
            if (particles.size >= MAX_PARTICLES) return
            particles.add(
                Particle(
                    x = origin[0] + random(-spread, spread),
                    y = origin[1] + random(0f, 0.02f),
                    z = origin[2] + random(-spread, spread),
                    vx = random(-0.02f, 0.02f),
                    vy = random(0.08f, 0.16f),
                    vz = random(-0.02f, 0.02f),
                    lifetime = random(0.9f, 1.5f),
                    size = random(0.018f, 0.03f),
                    phase = random(0f, 6.28f)
                )
            )
        }
    }

    /** Двигает частицы и удаляет отжившие. [dt] — время с прошлого кадра, секунды. */
    fun update(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.age += dt
            if (p.age >= p.lifetime) {
                iterator.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.z += p.vz * dt
        }
    }

    /** Рисует все частицы. Вызывать между quad.begin() и quad.end(). */
    fun draw(quad: QuadRenderer, viewMatrix: FloatArray) {
        if (particles.isEmpty()) return

        // Оси камеры в мировых координатах (строки матрицы вида):
        // с ними прямоугольник всегда параллелен экрану
        val rx = viewMatrix[0]; val ry = viewMatrix[4]; val rz = viewMatrix[8]   // вправо
        val ux = viewMatrix[1]; val uy = viewMatrix[5]; val uz = viewMatrix[9]   // вверх
        val fx = viewMatrix[2]; val fy = viewMatrix[6]; val fz = viewMatrix[10]  // к камере

        val m = modelMatrix
        for (p in particles) {
            val t = p.age / p.lifetime                 // 0 в начале жизни, 1 в конце
            val grow = min(t / 0.15f, 1f)              // быстро вырастает из точки
            val alpha = if (t < 0.6f) 1f else 1f - (t - 0.6f) / 0.4f  // в конце плавно тает
            val h = p.size * grow
            val w = h * HEART_ASPECT

            // Покачивание из стороны в сторону
            val sway = sin(p.phase + p.age * 5f) * 0.008f
            val cx = p.x + rx * sway
            val cy = p.y + ry * sway
            val cz = p.z + rz * sway

            m[0] = rx * w; m[1] = ry * w; m[2] = rz * w; m[3] = 0f
            m[4] = ux * h; m[5] = uy * h; m[6] = uz * h; m[7] = 0f
            m[8] = fx; m[9] = fy; m[10] = fz; m[11] = 0f
            // Прямоугольник «растёт» от нижней стороны, поэтому сдвигаем его
            // на полвысоты вниз — тогда (cx, cy, cz) окажется в центре сердечка
            m[12] = cx - ux * h * 0.5f
            m[13] = cy - uy * h * 0.5f
            m[14] = cz - uz * h * 0.5f
            m[15] = 1f

            quad.draw(textureId, m, alpha)
        }
    }

    private fun random(from: Float, to: Float): Float =
        from + (to - from) * Random.nextFloat()

    /**
     * Рисует гладкое сердечко обычными средствами Android (Canvas): контур из четырёх
     * кривых Безье, заливка градиентом, тёмная обводка и блик.
     */
    private fun createHeartBitmap(): Bitmap {
        val s = HEART_TEXTURE_SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(HEART_TEXTURE_SIZE, HEART_TEXTURE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Координаты заданы в долях от размера картинки: (0, 0) — левый верхний угол
        val heart = Path()
        heart.moveTo(0.50f * s, 0.90f * s)                                                 // нижний кончик
        heart.cubicTo(0.12f * s, 0.64f * s, 0.02f * s, 0.42f * s, 0.06f * s, 0.28f * s)   // левый бок
        heart.cubicTo(0.10f * s, 0.10f * s, 0.38f * s, 0.04f * s, 0.50f * s, 0.26f * s)   // левая дуга
        heart.cubicTo(0.62f * s, 0.04f * s, 0.90f * s, 0.10f * s, 0.94f * s, 0.28f * s)   // правая дуга
        heart.cubicTo(0.98f * s, 0.42f * s, 0.88f * s, 0.64f * s, 0.50f * s, 0.90f * s)   // правый бок
        heart.close()

        // Заливка: сверху светлее, снизу насыщеннее
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.shader = LinearGradient(
            0f, 0.1f * s, 0f, 0.9f * s,
            Color.rgb(255, 128, 168), Color.rgb(255, 48, 104),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(heart, fill)

        // Обводка
        val outline = Paint(Paint.ANTI_ALIAS_FLAG)
        outline.style = Paint.Style.STROKE
        outline.strokeWidth = 0.035f * s
        outline.color = Color.rgb(196, 28, 84)
        canvas.drawPath(heart, outline)

        // Блик слева сверху
        val shine = Paint(Paint.ANTI_ALIAS_FLAG)
        shine.color = Color.argb(200, 255, 255, 255)
        canvas.drawOval(0.20f * s, 0.20f * s, 0.38f * s, 0.34f * s, shine)

        return bitmap
    }

    private companion object {
        /** Больше этого числа сердечек одновременно не бывает. */
        const val MAX_PARTICLES = 64

        /** Сторона картинки сердечка в пикселях (степень двойки — для мипмапов). */
        const val HEART_TEXTURE_SIZE = 128

        /** Ширина / высота картинки сердечка. */
        const val HEART_ASPECT = 1f
    }
}
