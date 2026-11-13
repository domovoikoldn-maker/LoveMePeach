package com.example.artamagotchi

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

/**
 * Рисует найденные ARCore горизонтальные плоскости в виде сетки.
 *
 * ARCore отдаёт каждую плоскость как выпуклый многоугольник (plane.polygon):
 * список точек [x0, z0, x1, z1, ...] в собственной системе координат плоскости,
 * где Y = 0 — это сама поверхность. Мы рисуем его «веером» треугольников,
 * а линии сетки вычисляет фрагментный шейдер — отдельная текстура не нужна.
 */
class PlaneRenderer {

    private var program = 0
    private var aXZ = 0
    private var uModel = 0
    private var uViewProjection = 0
    private var uCellSize = 0
    private var uLineColor = 0
    private var uFillColor = 0

    // Буфер вершин; увеличивается, если у плоскости окажется больше точек
    private var vertexBuffer = GlUtil.allocateFloatBuffer(128)

    private val modelMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    /** Вызывать из onSurfaceCreated(). */
    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aXZ = GLES20.glGetAttribLocation(program, "a_XZ")
        uModel = GLES20.glGetUniformLocation(program, "u_Model")
        uViewProjection = GLES20.glGetUniformLocation(program, "u_ViewProjection")
        uCellSize = GLES20.glGetUniformLocation(program, "u_CellSize")
        uLineColor = GLES20.glGetUniformLocation(program, "u_LineColor")
        uFillColor = GLES20.glGetUniformLocation(program, "u_FillColor")
    }

    fun draw(planes: Collection<Plane>, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUniformMatrix4fv(uViewProjection, 1, false, viewProjectionMatrix, 0)
        GLES20.glUniform1f(uCellSize, CELL_SIZE_METERS)
        GLES20.glUniform4f(uLineColor, 1f, 1f, 1f, 0.75f)       // белые линии
        GLES20.glUniform4f(uFillColor, 0.4f, 0.8f, 1f, 0.12f)   // лёгкая голубая заливка
        GLES20.glEnableVertexAttribArray(aXZ)

        for (plane in planes) {
            if (!isUsable(plane)) continue

            val polygon = plane.polygon
            polygon.rewind()
            val floatCount = polygon.remaining()
            if (floatCount < 6) continue // нужно минимум три точки

            if (vertexBuffer.capacity() < floatCount) {
                vertexBuffer = GlUtil.allocateFloatBuffer(floatCount * 2)
            }
            vertexBuffer.clear()
            vertexBuffer.put(polygon)
            vertexBuffer.flip()

            // Матрица, переводящая точки из системы координат плоскости в мировую
            plane.centerPose.toMatrix(modelMatrix, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0)

            GLES20.glVertexAttribPointer(aXZ, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, floatCount / 2)
        }

        GLES20.glDisableVertexAttribArray(aXZ)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    companion object {
        /** Размер ячейки сетки в метрах (5 см). */
        private const val CELL_SIZE_METERS = 0.05f

        /**
         * Подходит ли плоскость для показа и для установки персонажа:
         * она сейчас отслеживается, не поглощена более крупной плоскостью
         * и это горизонтальная поверхность «лицом вверх» (стол, пол), а не потолок.
         */
        fun isUsable(plane: Plane): Boolean =
            plane.trackingState == TrackingState.TRACKING &&
                plane.subsumedBy == null &&
                plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING

        // ВАЖНО: внутри текста шейдеров — только латиница. Стандарт GLSL ES 1.00 разрешает
        // в исходнике лишь ASCII, и некоторые видеодрайверы не компилируют шейдер
        // с русскими буквами даже в комментариях. Поэтому пояснения вынесены сюда.

        // Вершинный шейдер. Точка многоугольника (x, z) лежит в плоскости Y = 0;
        // u_Model переводит её в мировые координаты. Мировые X и Z передаём дальше:
        // сетка привязана к миру и не «плывёт», когда ARCore уточняет границы плоскости.
        private val VERTEX_SHADER = """
            uniform mat4 u_Model;
            uniform mat4 u_ViewProjection;
            attribute vec2 a_XZ;
            varying vec2 v_WorldXZ;
            void main() {
                vec4 world = u_Model * vec4(a_XZ.x, 0.0, a_XZ.y, 1.0);
                v_WorldXZ = world.xz;
                gl_Position = u_ViewProjection * world;
            }
        """.trimIndent()

        // Фрагментный шейдер. cell — координаты в «ячейках»; d — расстояние до ближайшей
        // линии сетки в долях ячейки (0 — прямо на линии). Ближе 0.06 ячейки — рисуем
        // линию с мягким краем, иначе — полупрозрачную заливку.
        // highp нужен, чтобы линии не дрожали вдали от начала координат.
        private val FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform float u_CellSize;
            uniform vec4 u_LineColor;
            uniform vec4 u_FillColor;
            varying vec2 v_WorldXZ;
            void main() {
                vec2 cell = v_WorldXZ / u_CellSize;
                vec2 d = 0.5 - abs(fract(cell) - 0.5);
                float dist = min(d.x, d.y);
                float lineAmount = 1.0 - smoothstep(0.0, 0.06, dist);
                gl_FragColor = mix(u_FillColor, u_LineColor, lineAmount);
            }
        """.trimIndent()
    }
}
