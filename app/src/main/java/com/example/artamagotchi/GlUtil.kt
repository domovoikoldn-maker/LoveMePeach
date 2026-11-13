package com.example.artamagotchi

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * Небольшие помощники для OpenGL ES 2.0: шейдеры, буферы вершин, текстуры.
 *
 * Всё, что обращается к GLES20, можно вызывать только из GL-потока,
 * то есть изнутри методов GLSurfaceView.Renderer.
 */
object GlUtil {

    /** Собирает шейдерную программу из исходников вершинного и фрагментного шейдеров. */
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        // После линковки отдельные шейдеры больше не нужны
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Не удалось слинковать шейдерную программу: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            // Текст ошибки попадёт в Logcat — по нему легко найти опечатку в GLSL
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Ошибка компиляции шейдера: $log")
        }
        return shader
    }

    /**
     * Пустой «прямой» буфер на [floatCount] чисел.
     * OpenGL читает вершины из нативной памяти, поэтому обычный FloatArray ему не подходит.
     */
    fun allocateFloatBuffer(floatCount: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floatCount * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    /** Прямой буфер, сразу заполненный значениями. */
    fun floatBufferOf(vararg values: Float): FloatBuffer {
        val buffer = allocateFloatBuffer(values.size)
        buffer.put(values)
        buffer.position(0)
        return buffer
    }

    /**
     * Загружает Bitmap в обычную 2D-текстуру и возвращает её id.
     *
     * @param pixelArt false — обычная картинка: сглаживается и при увеличении, и при уменьшении
     *                 (с мипмапами, чтобы издалека не рябила);
     *                 true — пиксель-арт: при увеличении пиксели остаются чёткими квадратами.
     *
     * Важно: Android хранит Bitmap с «предумноженной» альфой (цвет уже умножен
     * на прозрачность), поэтому при отрисовке используется смешивание
     * GL_ONE / GL_ONE_MINUS_SRC_ALPHA — см. QuadRenderer.
     */
    fun createTexture(bitmap: Bitmap, pixelArt: Boolean): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        if (pixelArt) {
            // Без мипмапов: так в ES 2.0 можно брать картинку любого размера
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        } else {
            // Мипмапы — заранее уменьшенные копии текстуры. Когда персонаж далеко и занимает
            // на экране мало места, видеокарта берёт подходящую копию, и края не «шумят».
            // В OpenGL ES 2.0 мипмапы работают только у текстур со сторонами-степенями двойки,
            // поэтому картинку сначала растягиваем до таких размеров. Пропорции на экране
            // от этого не страдают: их задаёт прямоугольник спрайта, а не текстура.
            val maxSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
            val limit = if (maxSize[0] > 0) min(maxSize[0], 2048) else 2048
            val width = powerOfTwo(bitmap.width, limit)
            val height = powerOfTwo(bitmap.height, limit)
            val scaled = if (width == bitmap.width && height == bitmap.height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            }

            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scaled, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)

            if (scaled !== bitmap) scaled.recycle()
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /** Наименьшая степень двойки, не меньшая [size] (но не больше [limit]). */
    private fun powerOfTwo(size: Int, limit: Int): Int {
        var result = 1
        while (result < size && result < limit) result *= 2
        return result
    }
}
