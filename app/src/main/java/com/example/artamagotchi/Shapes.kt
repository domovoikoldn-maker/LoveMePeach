package com.example.artamagotchi

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Собирает 3D-модель из простых фигур (шар, цилиндр, коробка, бублик).
 * Из них сделаны еда и кабинки — отдельные файлы моделей для них не нужны.
 * Цвет задаётся числом 0xRRGGBB.
 */
class MeshBuilder {
    private val vertices = ArrayList<Float>()   // x, y, z, nx, ny, nz
    private val colors = ArrayList<Int>()
    private val indices = ArrayList<Short>()

    private fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, color: Int): Int {
        val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        vertices.add(x); vertices.add(y); vertices.add(z)
        vertices.add(nx / len); vertices.add(ny / len); vertices.add(nz / len)
        colors.add(color)
        return colors.size - 1
    }

    private fun quad(a: Int, b: Int, c: Int, d: Int) {
        indices.add(a.toShort()); indices.add(b.toShort()); indices.add(c.toShort())
        indices.add(a.toShort()); indices.add(c.toShort()); indices.add(d.toShort())
    }

    /** Шар (или эллипсоид) с центром (cx, cy, cz) и радиусами по осям. */
    fun sphere(
        cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, color: Int,
        segments: Int = 18, rings: Int = 10, colorOf: ((segment: Int) -> Int)? = null
    ) {
        val base = colors.size
        for (r in 0..rings) {
            val phi = PI * r / rings
            for (s in 0..segments) {
                val theta = 2 * PI * s / segments
                val ux = (sin(phi) * cos(theta)).toFloat()
                val uy = cos(phi).toFloat()
                val uz = (sin(phi) * sin(theta)).toFloat()
                vertex(cx + ux * rx, cy + uy * ry, cz + uz * rz, ux / rx, uy / ry, uz / rz, colorOf?.invoke(s % segments) ?: color)
            }
        }
        val row = segments + 1
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = base + r * row + s
            quad(a, a + 1, a + row + 1, a + row)
        }
    }

    /** Вертикальный цилиндр: низ на высоте y0, высота h, радиусы низа и верха. */
    fun cylinder(cx: Float, y0: Float, cz: Float, rBottom: Float, rTop: Float, h: Float, color: Int, segments: Int = 20) {
        val base = colors.size
        for (s in 0..segments) {
            val theta = 2 * PI * s / segments
            val ux = cos(theta).toFloat()
            val uz = sin(theta).toFloat()
            vertex(cx + ux * rBottom, y0, cz + uz * rBottom, ux, 0.2f, uz, color)
            vertex(cx + ux * rTop, y0 + h, cz + uz * rTop, ux, 0.2f, uz, color)
        }
        for (s in 0 until segments) {
            val a = base + s * 2
            quad(a, a + 1, a + 3, a + 2)
        }
        // Крышки
        for ((y, r, ny) in listOf(Triple(y0, rBottom, -1f), Triple(y0 + h, rTop, 1f))) {
            val center = vertex(cx, y, cz, 0f, ny, 0f, color)
            val ring = colors.size
            for (s in 0..segments) {
                val theta = 2 * PI * s / segments
                vertex(cx + cos(theta).toFloat() * r, y, cz + sin(theta).toFloat() * r, 0f, ny, 0f, color)
            }
            for (s in 0 until segments) {
                indices.add(center.toShort()); indices.add((ring + s).toShort()); indices.add((ring + s + 1).toShort())
            }
        }
    }

    /** Коробка с центром (cx, cy, cz) и размерами по осям. */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, color: Int) {
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        // Для каждой из шести граней: нормаль и два направления вдоль грани
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f)
        )
        for (f in faces) {
            val ids = IntArray(4)
            var i = 0
            for ((u, v) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val x = cx + (f[0] + f[3] * u + f[6] * v) * hx
                val y = cy + (f[1] + f[4] * u + f[7] * v) * hy
                val z = cz + (f[2] + f[5] * u + f[8] * v) * hz
                ids[i++] = vertex(x, y, z, f[0], f[1], f[2], color)
            }
            quad(ids[0], ids[1], ids[2], ids[3])
        }
    }

    /** Бублик, лежащий горизонтально: R — радиус кольца, r — толщина. */
    fun torus(cx: Float, cy: Float, cz: Float, R: Float, r: Float, color: Int, squashY: Float = 1f, segments: Int = 24, sides: Int = 12) {
        val base = colors.size
        for (i in 0..segments) {
            val a = 2 * PI * i / segments
            for (j in 0..sides) {
                val b = 2 * PI * j / sides
                val nx = (cos(a) * cos(b)).toFloat()
                val ny = sin(b).toFloat()
                val nz = (sin(a) * cos(b)).toFloat()
                vertex(
                    cx + (cos(a) * (R + r * cos(b))).toFloat(), cy + ny * r * squashY, cz + (sin(a) * (R + r * cos(b))).toFloat(),
                    nx, ny, nz, color
                )
            }
        }
        val row = sides + 1
        for (i in 0 until segments) for (j in 0 until sides) {
            val a = base + i * row + j
            quad(a, a + 1, a + row + 1, a + row)
        }
    }

    /** Загружает собранную модель в видеопамять. Вызывать из GL-потока. */
    fun build(): Mesh {
        val vb = ByteBuffer.allocateDirect(colors.size * Mesh.STRIDE).order(ByteOrder.nativeOrder())
        for (i in colors.indices) {
            val o = i * 6
            vb.putFloat(vertices[o]).putFloat(vertices[o + 1]).putFloat(vertices[o + 2])
            vb.put((vertices[o + 3] * 127).toInt().toByte())
            vb.put((vertices[o + 4] * 127).toInt().toByte())
            vb.put((vertices[o + 5] * 127).toInt().toByte())
            vb.put(0.toByte())                                                     // это не лицо
            val c = colors[i]
            vb.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put(255.toByte())
            vb.put(0.toByte()).put(0.toByte()).put(255.toByte()).put(0.toByte())   // кость 0 с весом 1
        }
        vb.position(0)
        val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder())
        for (i in indices) ib.putShort(i)
        ib.position(0)
        return Mesh(vb, ib, indices.size)
    }
}

/** Готовые модели. Еда умещается в кубик со стороной 1 и стоит на y = 0. */
object Shapes {
    fun food(food: Food): Mesh = MeshBuilder().apply {
        when (food) {
            Food.APPLE -> {
                sphere(0f, 0.42f, 0f, 0.42f, 0.40f, 0.42f, 0xE5303C)
                sphere(-0.12f, 0.55f, 0.25f, 0.12f, 0.14f, 0.10f, 0xFF7A80)      // блик
                cylinder(0f, 0.76f, 0f, 0.03f, 0.03f, 0.18f, 0x6B4423, 8)       // черенок
                sphere(0.13f, 0.92f, 0f, 0.14f, 0.03f, 0.07f, 0x4CAF50, 10, 6)  // листик
            }
            Food.ONIGIRI -> {
                sphere(0f, 0.34f, 0f, 0.46f, 0.34f, 0.26f, 0xFFFFFF)
                sphere(0f, 0.58f, 0f, 0.30f, 0.30f, 0.24f, 0xFFFFFF)
                box(0f, 0.20f, 0f, 0.44f, 0.38f, 0.56f, 0x1F3B2C)               // нори
            }
            Food.DONUT -> {
                torus(0f, 0.20f, 0f, 0.30f, 0.17f, 0xE0A458)
                torus(0f, 0.27f, 0f, 0.30f, 0.15f, 0xFF7EB6, squashY = 0.75f)   // глазурь
                for (i in 0 until 8) {
                    val a = i * 0.785f
                    sphere(cos(a) * 0.30f, 0.40f, sin(a) * 0.30f, 0.035f, 0.02f, 0.035f, if (i % 2 == 0) 0xFFFFFF else 0x5FD3C8, 6, 4)
                }
            }
            Food.BURGER -> {
                cylinder(0f, 0.00f, 0f, 0.40f, 0.44f, 0.16f, 0xE0A458)          // нижняя булка
                cylinder(0f, 0.16f, 0f, 0.47f, 0.47f, 0.05f, 0x6FBF4A)          // салат
                cylinder(0f, 0.21f, 0f, 0.44f, 0.44f, 0.13f, 0x6B3A22)          // котлета
                box(0f, 0.355f, 0f, 0.80f, 0.03f, 0.80f, 0xFFC93C)              // сыр
                cylinder(0f, 0.37f, 0f, 0.45f, 0.45f, 0.05f, 0xD9412F)          // помидор
                sphere(0f, 0.42f, 0f, 0.45f, 0.30f, 0.45f, 0xE8B062)            // верхняя булка
            }
            Food.CAKE -> {
                cylinder(0f, 0.00f, 0f, 0.45f, 0.45f, 0.26f, 0xFFF1D6)          // корж
                cylinder(0f, 0.26f, 0f, 0.46f, 0.46f, 0.06f, 0xFF8FB8)          // крем
                cylinder(0f, 0.32f, 0f, 0.36f, 0.36f, 0.22f, 0xFFF1D6)
                cylinder(0f, 0.54f, 0f, 0.37f, 0.37f, 0.05f, 0xFF8FB8)
                sphere(0f, 0.68f, 0f, 0.10f, 0.10f, 0.10f, 0xD9213A)            // вишенка
                for (i in 0 until 6) {
                    val a = i * 1.047f
                    sphere(cos(a) * 0.27f, 0.61f, sin(a) * 0.27f, 0.06f, 0.05f, 0.06f, 0xFFFFFF, 8, 5)
                }
            }
        }
    }.build()

    /** Мяч: шар диаметром 1 с центром в начале координат, шесть цветных долек. */
    fun ball(): Mesh = MeshBuilder().apply {
        val palette = intArrayOf(0xFF5C9E, 0xFFFFFF, 0x5FD3C8, 0xFFD84D, 0x8466E2, 0xFFFFFF)
        sphere(0f, 0f, 0f, 0.5f, 0.5f, 0.5f, 0xFFFFFF, 24, 14) { segment -> palette[(segment * 6 / 24) % 6] }
        sphere(0f, 0.49f, 0f, 0.08f, 0.02f, 0.08f, 0x2B2342, 10, 4)
        sphere(0f, -0.49f, 0f, 0.08f, 0.02f, 0.08f, 0x2B2342, 10, 4)
    }.build()

    /**
     * Постель для сна: одеяло от плеч до ступней и подушка. Задана в координатах
     * стоящей модели (рост = 1) и рисуется с той же матрицей, что и лежащий персонаж.
     */
    fun bedding(): Mesh = MeshBuilder().apply {
        box(0f, 0.40f, 0.02f, 0.42f, 0.86f, 0.27f, 0x8FB8FF)                    // одеяло
        sphere(0f, 0.42f, 0.14f, 0.20f, 0.40f, 0.05f, 0xA9C9FF, 14, 8)          // мягкая складка сверху
        box(0f, 0.80f, 0.02f, 0.43f, 0.07f, 0.28f, 0xFFFFFF)                    // отворот
        box(0f, 0.93f, -0.13f, 0.36f, 0.22f, 0.07f, 0xFFF4E0)                   // подушка
    }.build()

    /**
     * Закрытая кабинка (душ или туалет). Размеры — в долях роста персонажа:
     * ширина и глубина 0.6, высота 1.15. Что происходит внутри, не видно.
     */
    fun cabin(shower: Boolean): Mesh = MeshBuilder().apply {
        val wall = if (shower) 0xBFE9F5 else 0xB98A5A
        val trim = if (shower) 0xFFFFFF else 0x7A5230
        box(0f, 0.56f, 0f, 0.60f, 1.12f, 0.60f, wall)
        box(0f, 1.15f, 0f, 0.68f, 0.06f, 0.68f, trim)                           // крыша
        box(0f, 0.02f, 0f, 0.66f, 0.04f, 0.66f, trim)                           // основание
        if (shower) {
            // Шторка: вертикальные полоски на передней стенке
            for (i in -2..2) box(i * 0.11f, 0.56f, 0.305f, 0.055f, 1.0f, 0.012f, 0x5FC3E0)
            sphere(0f, 1.24f, 0f, 0.07f, 0.05f, 0.07f, 0xC0C7D0, 10, 6)         // лейка
        } else {
            box(0f, 0.52f, 0.305f, 0.40f, 0.96f, 0.012f, 0x8C6238)              // дверь
            sphere(0.14f, 0.52f, 0.32f, 0.025f, 0.025f, 0.025f, 0xFFD84D, 8, 5) // ручка
            box(0f, 0.84f, 0.312f, 0.14f, 0.10f, 0.008f, 0xFFF1B8)              // окошко
        }
    }.build()
}
