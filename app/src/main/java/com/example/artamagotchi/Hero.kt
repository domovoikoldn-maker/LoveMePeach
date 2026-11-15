package com.example.artamagotchi

/**
 * Персонаж на выбор: файл 3D-модели, картинка для экрана выбора, текстура лица
 * и прямоугольник на модели, куда эта текстура ложится (центр x, центр y, ширина, высота).
 */
enum class Hero(
    val defaultName: String,
    val meshAsset: String,
    val previewRes: Int,
    val faceRes: Int,
    val faceRect: FloatArray,
    val female: Boolean,
    /** Может ли персонаж двигать руками (у парня в модели руки в карманах). */
    val hasArms: Boolean
) {
    GIRL("Мира", "girl.mesh", R.drawable.preview_girl, R.drawable.face_girl, floatArrayOf(0f, 0.871f, 0.088f, 0.044f), true, true),
    BOY("Кай", "boy.mesh", R.drawable.preview_boy, R.drawable.face_boy, floatArrayOf(0f, 0.8745f, 0.088f, 0.044f), false, false)
}

/** Всё, что игрок настроил под себя. */
data class Profile(
    val hero: Hero,
    val heroName: String,
    val playerName: String,
    val playerFemale: Boolean,
    val personality: Personality,
    val heightCm: Int
)
