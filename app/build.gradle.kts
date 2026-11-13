plugins {
    // Kotlin компилируется самим Android Gradle Plugin (AGP 9+), отдельный плагин не нужен
    id("com.android.application")
}

android {
    namespace = "com.example.artamagotchi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.artamagotchi"
        // ARCore требует минимум Android 7.0 (API 24)
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    // ARCore: трекинг камеры, поиск плоскостей, якоря.
    // Свежие версии смотрите на https://github.com/google-ar/arcore-android-sdk/releases
    // (начиная с 1.56 библиотека нацелена на API 37 — возможно, придётся поднять и compileSdk).
    implementation("com.google.ar:core:1.54.0")

    // Классические View: AppCompatActivity, запрос разрешений, отступы под системные панели
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
