package com.example.artamagotchi

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 3D-модель в видеопамяти.
 *
 * Формат вершины (24 байта):
 *   позиция 3 x float | нормаль 3 x byte + метка «лицо» 1 byte | цвет 4 x ubyte |
 *   кости: индекс первой, индекс второй, вес первой (0..255), запас.
 * У каждой вершины две кости — так суставы гнутся плавно.
 */
class Mesh(vertices: ByteBuffer, indices: ByteBuffer, val indexCount: Int, val joints: FloatArray? = null) {
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
        const val STRIDE = 24

        /**
         * Читает модель персонажа из assets. Файл .mesh готовится скриптами из папки tools:
         * "MSH2", число вершин, число индексов, 12 суставов (x, y, z), вершины, индексы (uint16).
         */
        fun fromAsset(context: Context, name: String): Mesh {
            val bytes = context.assets.open(name).use { it.readBytes() }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(header.getInt() == 0x3248534D) { "Неверный формат файла модели $name" }   // "MSH2"
            val vertexCount = header.getInt()
            val indexCount = header.getInt()
            val joints = FloatArray(Skeleton.BONES * 3) { header.getFloat() }
            val start = 12 + Skeleton.BONES * 12
            val vertexBytes = vertexCount * STRIDE
            val vertices = ByteBuffer.allocateDirect(vertexBytes).order(ByteOrder.nativeOrder())
            vertices.put(bytes, start, vertexBytes).position(0)
            val indices = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder())
            indices.put(bytes, start + vertexBytes, indexCount * 2).position(0)
            return Mesh(vertices, indices, indexCount, joints)
        }
    }
}

/**
 * Скелет из 12 костей. По углам поворота каждой кости считает её матрицу
 * (с учётом родителя: повернули плечо — повернулось и предплечье).
 */
class Skeleton(private val joints: FloatArray) {
    /** Матрицы всех костей подряд — в таком виде их получает шейдер. */
    val matrices = FloatArray(BONES * 16)
    private val local = FloatArray(16)

    /**
     * @param angles по три угла (вокруг X, Y, Z, радианы) на кость
     * @param root   сдвиг всего тела (в единицах модели, рост = 1)
     */
    fun compute(angles: FloatArray, root: FloatArray) {
        for (b in 0 until BONES) {
            val rx = angles[b * 3]; val ry = angles[b * 3 + 1]; val rz = angles[b * 3 + 2]
            val cx = kotlin.math.cos(rx); val sx = kotlin.math.sin(rx)
            val cy = kotlin.math.cos(ry); val sy = kotlin.math.sin(ry)
            val cz = kotlin.math.cos(rz); val sz = kotlin.math.sin(rz)
            // R = Rz * Ry * Rx, по столбцам
            val m = local
            m[0] = cz * cy; m[1] = sz * cy; m[2] = -sy; m[3] = 0f
            m[4] = cz * sy * sx - sz * cx; m[5] = sz * sy * sx + cz * cx; m[6] = cy * sx; m[7] = 0f
            m[8] = cz * sy * cx + sz * sx; m[9] = sz * sy * cx - cz * sx; m[10] = cy * cx; m[11] = 0f
            // Поворот вокруг сустава: перенос = j - R * j
            val jx = joints[b * 3]; val jy = joints[b * 3 + 1]; val jz = joints[b * 3 + 2]
            m[12] = jx - (m[0] * jx + m[4] * jy + m[8] * jz)
            m[13] = jy - (m[1] * jx + m[5] * jy + m[9] * jz)
            m[14] = jz - (m[2] * jx + m[6] * jy + m[10] * jz)
            m[15] = 1f
            val parent = PARENT[b]
            if (parent < 0) {
                m[12] += root[0]; m[13] += root[1]; m[14] += root[2]
                System.arraycopy(m, 0, matrices, 0, 16)
            } else {
                Matrix.multiplyMM(matrices, b * 16, matrices, parent * 16, m, 0)
            }
        }
    }

    companion object {
        const val BONES = 12
        const val HIPS = 0; const val SPINE = 1; const val CHEST = 2; const val HEAD = 3
        const val ARM_L = 4; const val FOREARM_L = 5; const val ARM_R = 6; const val FOREARM_R = 7
        const val THIGH_L = 8; const val SHIN_L = 9; const val THIGH_R = 10; const val SHIN_R = 11
        val PARENT = intArrayOf(-1, 0, 1, 2, 2, 4, 2, 6, 0, 8, 0, 10)

        /** Матрицы «без позы» — для предметов без скелета. */
        val IDENTITY = FloatArray(BONES * 16).also { for (b in 0 until BONES) Matrix.setIdentityM(it, b * 16) }
    }
}

/**
 * Рисует 3D-модели в аниме-стиле: две ступени света, блик, подсветка по краю,
 * тёмный контур. Персонажа гнёт по скелету, на лицо накладывает картинку
 * с глазами и ртом (её можно менять — так получаются выражения лица).
 */
class ModelRenderer {
    private var program = 0
    private var aPosition = 0
    private var aNormal = 0
    private var aColor = 0
    private var aBones = 0
    private var uModel = 0
    private var uViewProj = 0
    private var uBones = 0
    private var uOutline = 0
    private var uLightDir = 0
    private var uCamPos = 0
    private var uFlat = 0
    private var uDim = 0
    private var uFaceRect = 0
    private var uFaceCell = 0
    private var uFaceTex = 0

    private val viewProj = FloatArray(16)

    fun createOnGlThread() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aNormal = GLES20.glGetAttribLocation(program, "a_Normal")
        aColor = GLES20.glGetAttribLocation(program, "a_Color")
        aBones = GLES20.glGetAttribLocation(program, "a_Bones")
        uModel = GLES20.glGetUniformLocation(program, "u_Model")
        uViewProj = GLES20.glGetUniformLocation(program, "u_ViewProj")
        uBones = GLES20.glGetUniformLocation(program, "u_Bones")
        uOutline = GLES20.glGetUniformLocation(program, "u_Outline")
        uLightDir = GLES20.glGetUniformLocation(program, "u_LightDir")
        uCamPos = GLES20.glGetUniformLocation(program, "u_CamPos")
        uFlat = GLES20.glGetUniformLocation(program, "u_Flat")
        uDim = GLES20.glGetUniformLocation(program, "u_Dim")
        uFaceRect = GLES20.glGetUniformLocation(program, "u_FaceRect")
        uFaceCell = GLES20.glGetUniformLocation(program, "u_FaceCell")
        uFaceTex = GLES20.glGetUniformLocation(program, "u_FaceTex")
    }

    fun begin(view: FloatArray, projection: FloatArray, lightDir: FloatArray, camPos: FloatArray, dim: Float) {
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES20.glUniform3f(uLightDir, lightDir[0], lightDir[1], lightDir[2])
        GLES20.glUniform3f(uCamPos, camPos[0], camPos[1], camPos[2])
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1i(uFaceTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * @param bones       матрицы костей (Skeleton.matrices) или Skeleton.IDENTITY для предметов
     * @param outline     рисовать тёмный контур (только для модели персонажа)
     * @param faceTexture текстура с лицами (0 — без лица)
     * @param faceRect    куда на модели ложится лицо: [центр x, центр y, ширина, высота]
     * @param faceCell    номер выражения лица в текстуре (0..5)
     */
    fun draw(
        mesh: Mesh, model: FloatArray, bones: FloatArray = Skeleton.IDENTITY, outline: Boolean = false,
        faceTexture: Int = 0, faceRect: FloatArray = NO_FACE, faceCell: Int = 0
    ) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.ibo)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glEnableVertexAttribArray(aBones)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, Mesh.STRIDE, 0)
        GLES20.glVertexAttribPointer(aNormal, 4, GLES20.GL_BYTE, true, Mesh.STRIDE, 12)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_UNSIGNED_BYTE, true, Mesh.STRIDE, 16)
        GLES20.glVertexAttribPointer(aBones, 4, GLES20.GL_UNSIGNED_BYTE, false, Mesh.STRIDE, 20)

        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(uBones, Skeleton.BONES, false, bones, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTexture)
        // Ширина 0 выключает лицо
        GLES20.glUniform4f(uFaceRect, faceRect[0], faceRect[1], if (faceTexture != 0) faceRect[2] else 0f, faceRect[3])
        GLES20.glUniform1f(uFaceCell, faceCell.toFloat())

        if (outline) {
            // Контур: рисуем только «изнанку» чуть раздутой модели тёмным цветом
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_FRONT)
            GLES20.glUniform1f(uOutline, OUTLINE_WIDTH)
            GLES20.glUniform4f(uFlat, 0.16f, 0.10f, 0.22f, 1f)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
            GLES20.glCullFace(GLES20.GL_BACK)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
        }

        GLES20.glUniform1f(uOutline, 0f)
        GLES20.glUniform4f(uFlat, 0f, 0f, 0f, 0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    fun end() {
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glDisableVertexAttribArray(aBones)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    companion object {
        val NO_FACE = FloatArray(4)

        /** Толщина контура в долях роста модели. */
        const val OUTLINE_WIDTH = 0.0022f

        // ВАЖНО: внутри текста шейдеров — только латиница (GLSL ES 1.00 разрешает лишь ASCII).
        //
        // Вершинный шейдер:
        //  1) скелет: вершина двигается двумя костями, их матрицы смешиваются по весу;
        //  2) контур: сдвиг вдоль нормали;
        //  3) лицо: по исходному положению вершины считаем координаты в картинке лица.
        private val VERTEX_SHADER = """
            uniform mat4 u_Model;
            uniform mat4 u_ViewProj;
            uniform mat4 u_Bones[12];
            uniform float u_Outline;
            uniform vec4 u_FaceRect;
            attribute vec3 a_Position;
            attribute vec4 a_Normal;
            attribute vec4 a_Color;
            attribute vec4 a_Bones;
            varying vec3 v_Color;
            varying vec3 v_Normal;
            varying vec3 v_World;
            varying vec3 v_Face;

            void main() {
                float w = a_Bones.z / 255.0;
                mat4 skin = u_Bones[int(a_Bones.x + 0.5)] * w + u_Bones[int(a_Bones.y + 0.5)] * (1.0 - w);
                vec3 n = normalize((skin * vec4(a_Normal.xyz, 0.0)).xyz + vec3(0.0, 0.00001, 0.0));
                vec4 p = skin * vec4(a_Position, 1.0);
                p.xyz = p.xyz + n * u_Outline;

                vec4 world = u_Model * p;
                v_World = world.xyz;
                v_Normal = (u_Model * vec4(n, 0.0)).xyz;
                v_Color = a_Color.rgb;

                float faceW = max(u_FaceRect.z, 0.0001);
                float faceH = max(u_FaceRect.w, 0.0001);
                v_Face = vec3(
                    (a_Position.x - u_FaceRect.x) / faceW + 0.5,
                    (u_FaceRect.y - a_Position.y) / faceH + 0.5,
                    a_Normal.w * step(0.001, u_FaceRect.z)
                );
                gl_Position = u_ViewProj * world;
            }
        """.trimIndent()

        // Фрагментный шейдер: лицо (картинка поверх кожи), затем свет в две ступени,
        // блик и подсветка по краю силуэта.
        private val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 u_LightDir;
            uniform vec3 u_CamPos;
            uniform vec4 u_Flat;
            uniform float u_Dim;
            uniform float u_FaceCell;
            uniform sampler2D u_FaceTex;
            varying vec3 v_Color;
            varying vec3 v_Normal;
            varying vec3 v_World;
            varying vec3 v_Face;

            void main() {
                vec3 base = v_Color;
                vec2 uv = clamp(v_Face.xy, 0.01, 0.99);
                float inside = step(0.5, v_Face.z)
                    * step(0.0, v_Face.x) * step(v_Face.x, 1.0)
                    * step(0.0, v_Face.y) * step(v_Face.y, 1.0);
                vec4 face = texture2D(u_FaceTex, vec2((uv.x + u_FaceCell) / 6.0, uv.y)) * inside;
                base = base * (1.0 - face.a) + face.rgb;

                vec3 n = normalize(v_Normal);
                vec3 toCam = normalize(u_CamPos - v_World);
                float lit = smoothstep(-0.04, 0.06, dot(n, u_LightDir));
                lit = mix(lit, 1.0, face.a);
                vec3 shade = mix(vec3(0.68, 0.63, 0.80), vec3(1.0, 1.0, 1.0), lit);
                float spec = smoothstep(0.94, 0.97, dot(n, normalize(u_LightDir + toCam))) * lit;
                float rim = smoothstep(0.55, 0.78, 1.0 - max(dot(n, toCam), 0.0)) * lit;
                vec3 color = base * shade + vec3(spec * 0.14 + rim * 0.10) * (1.0 - face.a);
                color = mix(color, u_Flat.rgb, u_Flat.a);
                gl_FragColor = vec4(color * u_Dim, 1.0);
            }
        """.trimIndent()
    }
}
