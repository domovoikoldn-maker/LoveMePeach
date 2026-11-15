package com.example.artamagotchi

/** Персонаж на выбор: файл 3D-модели, картинка для экрана выбора, имя по умолчанию. */
enum class Hero(val defaultName: String, val meshAsset: String, val previewRes: Int, val neckY: Float, val female: Boolean) {
    GIRL("Мира", "girl.mesh", R.drawable.preview_girl, 0.83f, true),
    BOY("Кай", "boy.mesh", R.drawable.preview_boy, 0.84f, false)
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
