// Корневой файл сборки: здесь только объявляются версии плагинов.
//
// Начиная с AGP 9.0 поддержка Kotlin встроена в Android Gradle Plugin,
// поэтому отдельный плагин org.jetbrains.kotlin.android НЕ нужен
// (если его добавить, сборка упадёт с ошибкой).
plugins {
    id("com.android.application") version "9.0.1" apply false
}
