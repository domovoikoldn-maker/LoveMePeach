package com.example.artamagotchi

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/**
 * Рисует картинку с камеры на весь экран.
 *
 * ARCore сам пишет кадр камеры в специальную «внешнюю» текстуру
 * (GL_TEXTURE_EXTERNAL_OES). Наша задача — создать эту текстуру, сообщить её id
 * сессии (session.setCameraTextureName) и каждый кадр натянуть её на
 * полноэкранный прямоугольник.
 */
class BackgroundRenderer {

    /** id текстуры камеры. Его нужно передать в Session.setCameraTextureName(). */
    var textureId = -1
        private set

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0

    // Четыре угла экрана в нормализованных координатах OpenGL (от -1 до 1), порядок — triangle strip
    private val quadCoords = GlUtil.floatBufferOf(
        -1f, -1f,
        +1f, -1f,
        -1f, +1f,
        +1f, +1f
    )

    // Текстурные координаты для тех же углов. Их считает ARCore: они зависят
    // от поворота экрана и от соотношения сторон камеры и экрана.
    private val quadTexCoords = GlUtil.allocateFloatBuffer(8)
    private var texCoordsReady = false

    /** Вызывать из onSurfaceCreated(). */
    fun createOnGlThread() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]

        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "u_Texture")

        // GL-контекст создан заново — координаты пересчитаем на первом же кадре
        texCoordsReady = false
    }

    /** Рисует текущий кадр камеры. Вызывать первым, до любых AR-объектов. */
    fun draw(frame: Frame) {
        // Если экран повернули или изменился размер окна, просим ARCore
        // пересчитать, какой кусок кадра камеры показывать
        if (frame.hasDisplayGeometryChanged() || !texCoordsReady) {
            quadCoords.position(0)
            quadTexCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords
            )
            texCoordsReady = true
        }

        // Пока камера не выдала ни одного кадра, рисовать нечего
        if (frame.timestamp == 0L) return

        quadCoords.position(0)
        quadTexCoords.position(0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private companion object {
        // ВАЖНО: внутри текста шейдеров — только латиница (GLSL ES 1.00 разрешает лишь ASCII).

        // Вершинный шейдер: углы экрана уже заданы в готовых координатах, просто передаём их дальше
        val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        // samplerExternalOES — особый тип текстуры для кадров камеры и видео
        val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """.trimIndent()
    }
}
