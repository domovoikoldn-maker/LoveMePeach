package com.example.artamagotchi

import android.opengl.GLES20
import android.opengl.Matrix

/**
 * Рисует текстурированный прямоугольник в 3D-пространстве.
 * Через него выводятся и персонаж, и его тень, и сердечки.
 *
 * Сам прямоугольник всегда один и тот же («единичный»):
 *   X от -0.5 до 0.5, Y от 0 до 1, Z = 0.
 * Начало координат — середина НИЖНЕЙ стороны, то есть «ноги» спрайта.
 * Где он окажется в мире, какого будет размера и куда повёрнут — целиком
 * определяет матрица модели, которую передают в draw().
 *
 * Использование:
 *   begin(view, projection)
 *   draw(texture, modelMatrix, alpha)   // сколько угодно раз
 *   end()
 */
class QuadRenderer {

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvp = 0
    private var uTexture = 0
    private var uAlpha = 0

    // Порядок вершин — triangle strip: низ-лево, низ-право, верх-лево, верх-право
    private val positions = GlUtil.floatBufferOf(
        -0.5f, 0f,
        +0.5f, 0f,
        -0.5f, 1f,
        +0.5f, 1f
    )

    // У Bitmap первая строка — верх картинки, поэтому V = 0 соответствует верху
    private val texCoords = GlUtil.floatBufferOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    private val viewProjectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    /** Вызывать из onSurfaceCreated(). */
    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uTexture = GLES20.glGetUniformLocation(program, "u_Texture")
        uAlpha = GLES20.glGetUniformLocation(program, "u_Alpha")
    }

    /** Подготовка: общие для всех спрайтов настройки и матрицы камеры. */
    fun begin(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)

        // Прозрачность. Формула GL_ONE / GL_ONE_MINUS_SRC_ALPHA — для текстур
        // с предумноженной альфой (именно так Android загружает PNG).
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
    }

    /**
     * @param modelMatrix матрица 4x4 (по столбцам, как принято в OpenGL):
     *                    положение, поворот и размер прямоугольника в мире
     * @param alpha       общая прозрачность, 0..1
     */
    fun draw(textureId: Int, modelMatrix: FloatArray, alpha: Float) {
        // MVP = проекция * вид * модель: из локальных координат прямоугольника в экранные
        Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun end() {
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private companion object {
        // ВАЖНО: внутри текста шейдеров — только латиница (GLSL ES 1.00 разрешает лишь ASCII,
        // часть видеодрайверов спотыкается о русские буквы даже в комментариях).

        // Вершинный шейдер: умножает угол прямоугольника на матрицу MVP
        // и передаёт дальше текстурные координаты.
        val VERTEX_SHADER = """
            uniform mat4 u_Mvp;
            attribute vec2 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                v_TexCoord = a_TexCoord;
                gl_Position = u_Mvp * vec4(a_Position, 0.0, 1.0);
            }
        """.trimIndent()

        // Фрагментный шейдер: берёт цвет из текстуры. Цвет уже умножен на альфу,
        // поэтому для затухания (u_Alpha) умножаем все четыре канала сразу.
        val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform float u_Alpha;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord) * u_Alpha;
            }
        """.trimIndent()
    }
}
