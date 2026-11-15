package com.example.artamagotchi

import android.content.Context

/** Сохранение профиля и состояния персонажа между запусками (SharedPreferences). */
class CareStore(context: Context) {

    private val prefs = context.getSharedPreferences("care", Context.MODE_PRIVATE)

    /** Профиль или null, если игра ещё не начата (или её сбросили). */
    fun loadProfile(): Profile? {
        val hero = Hero.entries.firstOrNull { it.name == prefs.getString("hero", null) } ?: return null
        return Profile(
            hero = hero,
            heroName = prefs.getString("heroName", null) ?: hero.defaultName,
            playerName = prefs.getString("playerName", null) ?: "друг",
            playerFemale = prefs.getBoolean("playerFemale", false),
            personality = Personality.entries.firstOrNull { it.name == prefs.getString("personality", null) } ?: Personality.KIND,
            heightCm = prefs.getInt("heightCm", DEFAULT_HEIGHT_CM)
        )
    }

    fun saveProfile(p: Profile) {
        prefs.edit()
            .putString("hero", p.hero.name)
            .putString("heroName", p.heroName)
            .putString("playerName", p.playerName)
            .putBoolean("playerFemale", p.playerFemale)
            .putString("personality", p.personality.name)
            .putInt("heightCm", p.heightCm)
            .apply()
    }

    fun loadCare(): CareState = CareState().apply {
        fullness = prefs.getFloat("fullness", fullness)
        energy = prefs.getFloat("energy", energy)
        hygiene = prefs.getFloat("hygiene", hygiene)
        bladder = prefs.getFloat("bladder", bladder)
        mood = prefs.getFloat("mood", mood)
        bond = prefs.getFloat("bond", bond)
        coins = prefs.getInt("coins", coins)
        sleeping = prefs.getBoolean("sleeping", false)
        lastUpdate = prefs.getLong("lastUpdate", 0L)
        finished = prefs.getBoolean("finished", false)
        // Запас еды хранится строкой вида "1,0,2,0,0"
        prefs.getString("inventory", null)?.split(",")?.forEachIndexed { i, v ->
            if (i < inventory.size) inventory[i] = v.toIntOrNull() ?: 0
        }
    }

    fun saveCare(c: CareState) {
        prefs.edit()
            .putFloat("fullness", c.fullness)
            .putFloat("energy", c.energy)
            .putFloat("hygiene", c.hygiene)
            .putFloat("bladder", c.bladder)
            .putFloat("mood", c.mood)
            .putFloat("bond", c.bond)
            .putInt("coins", c.coins)
            .putBoolean("sleeping", c.sleeping)
            .putLong("lastUpdate", c.lastUpdate)
            .putBoolean("finished", c.finished)
            .putString("inventory", c.inventory.joinToString(","))
            .apply()
    }

    /** Полный сброс: следующий запуск начнётся с выбора персонажа. */
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val DEFAULT_HEIGHT_CM = 40
        const val MIN_HEIGHT_CM = 10
        const val MAX_HEIGHT_CM = 180
    }
}
