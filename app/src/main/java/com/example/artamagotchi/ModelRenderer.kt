package com.example.artamagotchi

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 3D-модель в видеопамяти: буфер вершин и буфер индексов.
 *
 * Формат вершины (20 байт): позиция 3 x float, нормаль 3 x byte (+1 байт выравнивания),
 * цвет 4 x ubyte. В альфа-канале цвета хранится «вес головы»: насколько вершина
 * поворачивается вместе с головой (1 — голова и волосы, 0 — тело).
 */
class Mesh(vertices: ByteBuffer, indices: ByteBuffer, val indexCount: Int) {
    val vbo: Int
    val ibo: Int

    init {
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.remaining(), vertices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.remaining(), indices, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    companion object {
        const val STRIDE = 20

        /**
         * Читает модель из assets. Файл .mesh готовится заранее из OBJ:
         * заголовок "MSH1", число вершин, число индексов, вершины, индексы (uint16).
         */
        fun fromAsset(context: Context, name: String): Mesh {
            val bytes = context.assets.open(name).use { it.readBytes() }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = header.getInt()
            check(magic == 0x3148534D) { "Неверный формат файла модели $name" }   // "MSH1"
            val vertexCount = header.getInt()
            val indexCount = header.getInt()
            val vertexBytes = vertexCount * STRIDE
            val vertices = ByteBuffer.allocateDirect(vertexBytes).order(ByteOrder.nativeOrder())
            vertices.put(bytes, 12, vertexBytes).position(0)
            val indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder())
            indices.put(bytes, 12 + vertexBytes, indexCount * 2).position(0)
            return Mesh(vertices, indices, indexCount)
        }
    }
}

/**
 * Рисует 3D-модели в аниме-стиле (toon shading):
 *  - свет делится на две ступени — «свет» и «тень», без плавного перехода;
 *  - по краю силуэта добавляется лёгкая подсветка;
 *  - вокруг модели рисуется тёмный контур (модель рисуется второй раз, чуть «раздутой»
 *    и вывернутой наизнанку).
 *
 * Анимации делает вершинный шейдер: у модели нет скелета, поэтому он сам
 * поворачивает голову, наклоняет корпус, покачивает и «дышит».
 */
class ModelRenderer {
    private var program = 0
    private var aPosition = 0
    private var aNormal = 0
    private var aColor = 0
    private var uModel = 0
    private var uViewProj = 0
    private var uPose = 0
    private var uPose2 = 0
    private var uLightDir = 0
    private var uCamPos = 0
    private var uFlat = 0
    private var uDim = 0

    private val viewProj = FloatArray(16)

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aNormal = GLES20.glGetAttribLocation(program, "a_Normal")
        aColor = GLES20.glGetAttribLocation(program, "a_Color")
        uModel = GLES20.glGetUniformLocation(program, "u_Model")
        uViewProj = GLES20.glGetUniformLocation(program, "u_ViewProj")
        uPose = GLES20.glGetUniformLocation(program, "u_Pose")
        uPose2 = GLES20.glGetUniformLocation(program, "u_Pose2")
        uLightDir = GLES20.glGetUniformLocation(program, "u_LightDir")
        uCamPos = GLES20.glGetUniformLocation(program, "u_CamPos")
        uFlat = GLES20.glGetUniformLocation(program, "u_Flat")
        uDim = GLES20.glGetUniformLocation(program, "u_Dim")
    }

    /** Общие для кадра настройки. [lightDir] — единичный вектор К источнику света. */
    fun begin(view: FloatArray, projection: FloatArray, lightDir: FloatArray, camPos: FloatArray, dim: Float) {
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES20.glUniform3f(uLightDir, lightDir[0], lightDir[1], lightDir[2])
        GLES20.glUniform3f(uCamPos, camPos[0], camPos[1], camPos[2])
        GLES20.glUniform1f(uDim, dim)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * @param pose    [наклон головы вперёд, поворот головы, наклон головы вбок, наклон корпуса] в радианах
     * @param sway    покачивание из стороны в сторону
     * @param breath  «дыхание» (расширение груди)
     * @param neckY   высота шеи в координатах модели (рост модели = 1)
     * @param outline рисовать ли тёмный контур (только для замкнутых моделей с правильным обходом)
     */
    fun draw(
        mesh: Mesh, model: FloatArray,
        pose: FloatArray = NO_POSE, sway: Float = 0f, breath: Float = 0f, neckY: Float = 0.83f,
        outline: Boolean = false
    ) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE, 0)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_BYTE, true, Mesh.STRIDE, 12)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_UNSIGNED_BYTE, true, Mesh.STRIDE, 16)

        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4f(uPose, pose[0], pose[1], pose[2], pose[3])

        if (outline) {
            // Контур: рисуем только «изнанку» чуть раздутой модели тёмным цветом
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_FRONT)
            GLES20.glUniform4f(uPose2, sway, breath, OUTLINE_WIDTH, neckY)
            GLES20.glUniform4f(uFlat, 0.16f, 0.10f, 0.22f, 1f)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
            GLES20.glCullFace(GLES20.GL_BACK)
        } else {
            GLES20.glDisable(GLES20.GL_CULL_FACE)
        }

        GLES20.glUniform4f(uPose2, sway, breath, 0f, neckY)
        GLES20.glUniform4f(uFlat, 0f, 0f, 0f, 0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    fun end() {
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    companion object {
        val NO_POSE = FloatArray(4)

        /** Толщина контура в долях роста модели. */
        const val OUTLINE_WIDTH = 0.0022f

        // ВАЖНО: внутри текста шейдеров — только латиница (GLSL ES 1.00 разрешает лишь ASCII).
        //
        // Вершинный шейдер. Порядок деформаций:
        //  1) голова: поворот вокруг шеи (сила — вес из альфа-канала цвета);
        //  2) корпус: наклон вперёд выше пояса;
        //  3) покачивание: сдвиг вбок, растущий с высотой;
        //  4) дыхание: грудь чуть расширяется;
        //  5) контур: сдвиг вдоль нормали.
        private val VERTEX_SHADER = """
            uniform mat4 u_Model;
            uniform mat4 u_ViewProj;
            uniform vec4 u_Pose;
            uniform vec4 u_Pose2;
            attribute vec3 a_Position;
            attribute vec3 a_Normal;
            attribute vec4 a_Color;
            varying vec3 v_Color;
            varying vec3 v_Normal;
            varying vec3 v_World;

            vec3 rotX(vec3 p, float a) {
                float c = cos(a);
                float s = sin(a);
                return vec3(p.x, c * p.y - s * p.z, s * p.y + c * p.z);
            }
            vec3 rotY(vec3 p, float a) {
                float c = cos(a);
                float s = sin(a);
                return vec3(c * p.x + s * p.z, p.y, c * p.z - s * p.x);
            }
            vec3 rotZ(vec3 p, float a) {
                float c = cos(a);
                float s = sin(a);
                return vec3(c * p.x - s * p.y, s * p.x + c * p.y, p.z);
            }

            void main() {
                vec3 p = a_Position;
                vec3 n = a_Normal;
                float w = a_Color.a;

                vec3 neck = vec3(0.0, u_Pose2.w, 0.0);
                p = p - neck;
                p = rotY(p, u_Pose.y * w);
                n = rotY(n, u_Pose.y * w);
                p = rotX(p, u_Pose.x * w);
                n = rotX(n, u_Pose.x * w);
                p = rotZ(p, u_Pose.z * w);
                n = rotZ(n, u_Pose.z * w);
                p = p + neck;

                float lean = smoothstep(0.50, 0.66, p.y) * u_Pose.w;
                vec3 hip = vec3(0.0, 0.55, 0.0);
                p = rotX(p - hip, lean) + hip;
                n = rotX(n, lean);

                p.x = p.x + u_Pose2.x * p.y * p.y;

                float chest = (p.y - 0.72) / 0.07;
                p.z = p.z * (1.0 + u_Pose2.y * exp(-chest * chest));

                p = p + n * u_Pose2.z;

                vec4 world = u_Model * vec4(p, 1.0);
                v_World = world.xyz;
                v_Normal = (u_Model * vec4(n, 0.0)).xyz;
                v_Color = a_Color.rgb;
                gl_Position = u_ViewProj * world;
            }
        """.trimIndent()

        // Фрагментный шейдер: двухступенчатый свет (lit = 0 или 1 с узким переходом),
        // тень слегка фиолетовая, по краю силуэта — подсветка (rim).
        private val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 u_LightDir;
            uniform vec3 u_CamPos;
            uniform vec4 u_Flat;
            uniform float u_Dim;
            varying vec3 v_Color;
            varying vec3 v_Normal;
            varying vec3 v_World;

            void main() {
                vec3 n = normalize(v_Normal);
                float lit = smoothstep(-0.04, 0.06, dot(n, u_LightDir));
                vec3 shade = mix(vec3(0.66, 0.62, 0.78), vec3(1.0, 1.0, 1.0), lit);
                vec3 toCam = normalize(u_CamPos - v_World);
                float rim = smoothstep(0.55, 0.78, 1.0 - max(dot(n, toCam), 0.0)) * lit;
                vec3 color = v_Color * shade + vec3(rim * 0.12);
                color = mix(color, u_Flat.rgb, u_Flat.a);
                gl_FragColor = vec4(color * u_Dim, 1.0);
            }
        """.trimIndent()
    }
}
