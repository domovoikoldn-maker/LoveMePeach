package com.example.artamagotchi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Виды частиц. */
enum class ParticleKind { STAR, ZZZ, BUBBLE }

/**
 * Простая система частиц: звёздочки (радость), «Z» (сон), пузыри (душ).
 * Каждая частица — маленькая картинка, всегда повёрнутая к камере.
 * Картинки рисуются прямо в коде через Canvas.
 */
class Particles {

    private class Particle(
        val kind: ParticleKind,
        var x: Float, var y: Float, var z: Float,
        val vx: Float, val vy: Float, val vz: Float,
        val lifetime: Float, val size: Float, val phase: Float
    ) {
        var age = 0f
    }

    private val particles = ArrayList<Particle>()
    private val modelMatrix = FloatArray(16)
    private val textures = IntArray(ParticleKind.entries.size)

    /** Текстура звезды — она же используется для звёзд мини-игры. */
    val starTexture: Int get() = textures[ParticleKind.STAR.ordinal]

    fun createOnGlThread() {
        for (kind in ParticleKind.entries) {
            val bitmap = when (kind) {
                ParticleKind.STAR -> starBitmap()
                ParticleKind.ZZZ -> zBitmap()
                ParticleKind.BUBBLE -> bubbleBitmap()
            }
            textures[kind.ordinal] = GlUtil.createTexture(bitmap, pixelArt = false)
            bitmap.recycle()
        }
    }

    /**
     * Выпускает [count] частиц из точки [origin].
     * [unit] — масштаб сцены (рост персонажа в метрах): от него зависят размер и скорость.
     */
    fun emit(kind: ParticleKind, origin: FloatArray, unit: Float, count: Int) {
        repeat(count) {
            if (particles.size >= MAX_PARTICLES) return
            val spread = unit * 0.15f
            particles.add(
                Particle(
                    kind,
                    origin[0] + random(-spread, spread), origin[1] + random(0f, unit * 0.06f), origin[2] + random(-spread, spread),
                    random(-0.08f, 0.08f) * unit, random(0.25f, 0.5f) * unit, random(-0.08f, 0.08f) * unit,
                    lifetime = if (kind == ParticleKind.ZZZ) random(1.8f, 2.4f) else random(0.9f, 1.5f),
                    size = random(0.07f, 0.11f) * unit,
                    phase = random(0f, 6.28f)
                )
            )
        }
    }

    fun update(dt: Float) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.age += dt
            if (p.age >= p.lifetime) { iterator.remove(); continue }
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
        }
    }

    /** Рисует все частицы. Вызывать между quad.begin() и quad.end(). */
    fun draw(quad: QuadRenderer, viewMatrix: FloatArray) {
        for (p in particles) {
            val t = p.age / p.lifetime
            val grow = min(t / 0.15f, 1f)
            val alpha = if (t < 0.6f) 1f else 1f - (t - 0.6f) / 0.4f
            val sway = sin(p.phase + p.age * 5f) * p.size * 0.3f
            billboard(modelMatrix, viewMatrix, p.x + viewMatrix[0] * sway, p.y + viewMatrix[4] * sway, p.z + viewMatrix[8] * sway, p.size * grow)
            quad.draw(textures[p.kind.ordinal], modelMatrix, alpha)
        }
    }

    private fun random(from: Float, to: Float): Float = from + (to - from) * Random.nextFloat()

    private fun starBitmap(): Bitmap {
        val s = SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 0.46f * s else 0.20f * s
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = s / 2 + r * cos(a).toFloat()
            val y = s / 2 + r * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = Color.rgb(255, 214, 64)
        canvas.drawPath(path, fill)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG)
        outline.style = Paint.Style.STROKE
        outline.strokeWidth = 0.04f * s
        outline.color = Color.rgb(196, 124, 20)
        canvas.drawPath(path, outline)
        return bitmap
    }

    private fun zBitmap(): Bitmap {
        val s = SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 0.9f * s
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(190, 215, 255)
        canvas.drawText("Z", s / 2, 0.82f * s, paint)
        return bitmap
    }

    private fun bubbleBitmap(): Bitmap {
        val s = SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = Color.argb(70, 200, 240, 255)
        canvas.drawCircle(s / 2, s / 2, 0.44f * s, fill)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = 0.05f * s
        ring.color = Color.argb(230, 235, 250, 255)
        canvas.drawCircle(s / 2, s / 2, 0.44f * s, ring)
        val shine = Paint(Paint.ANTI_ALIAS_FLAG)
        shine.color = Color.WHITE
        canvas.drawCircle(0.36f * s, 0.34f * s, 0.07f * s, shine)
        return bitmap
    }

    companion object {
        const val MAX_PARTICLES = 96
        const val SIZE = 128

        /**
         * Матрица для квадрата со стороной [size], параллельного экрану, с центром в (x, y, z).
         * QuadRenderer рисует прямоугольник «от нижней стороны», поэтому сдвигаем на полвысоты вниз.
         */
        fun billboard(m: FloatArray, view: FloatArray, x: Float, y: Float, z: Float, size: Float) {
            val rx = view[0]; val ry = view[4]; val rz = view[8]     // «вправо» камеры
            val ux = view[1]; val uy = view[5]; val uz = view[9]     // «вверх» камеры
            m[0] = rx * size; m[1] = ry * size; m[2] = rz * size; m[3] = 0f
            m[4] = ux * size; m[5] = uy * size; m[6] = uz * size; m[7] = 0f
            m[8] = view[2]; m[9] = view[6]; m[10] = view[10]; m[11] = 0f
            m[12] = x - ux * size * 0.5f; m[13] = y - uy * size * 0.5f; m[14] = z - uz * size * 0.5f; m[15] = 1f
        }
    }
}
