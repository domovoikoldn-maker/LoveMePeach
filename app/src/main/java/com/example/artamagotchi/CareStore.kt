package com.example.artamagotchi

import android.content.Context

/** Сохранение выбора персонажа и его состояния между запусками (SharedPreferences). */
class CareStore(context: Context) {

    private val prefs = context.getSharedPreferences("care", Context.MODE_PRIVATE)

    /** Выбранный персонаж или null, если игра ещё не начата (или её сбросили). */
    var gender: Gender?
        get() = prefs.getString(KEY_GENDER, null)?.let { name -> Gender.entries.firstOrNull { it.name == name } }
        set(value) {
            prefs.edit().putString(KEY_GENDER, value?.name).apply()
        }

    fun load(): CareState = CareState(
        fullness = prefs.getFloat("fullness", 70f),
        mood = prefs.getFloat("mood", 70f),
        affection = prefs.getFloat("affection", 0f),
        lastUpdate = prefs.getLong("lastUpdate", 0L),
        lastGift = prefs.getLong("lastGift", 0L),
        lastTalk = prefs.getLong("lastTalk", 0L),
        lastPet = prefs.getLong("lastPet", 0L),
        finished = prefs.getBoolean("finished", false)
    )

    fun save(state: CareState) {
        prefs.edit()
            .putFloat("fullness", state.fullness)
            .putFloat("mood", state.mood)
            .putFloat("affection", state.affection)
            .putLong("lastUpdate", state.lastUpdate)
            .putLong("lastGift", state.lastGift)
            .putLong("lastTalk", state.lastTalk)
            .putLong("lastPet", state.lastPet)
            .putBoolean("finished", state.finished)
            .apply()
    }

    /** Полный сброс: следующий запуск начнётся с выбора персонажа. */
    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_GENDER = "gender"
    }
}
