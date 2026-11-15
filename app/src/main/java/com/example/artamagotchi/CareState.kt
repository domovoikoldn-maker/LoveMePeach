package com.example.artamagotchi

import kotlin.math.max
import kotlin.math.min

/** Еда из магазина. Цена — в монетах; fullness/mood — сколько прибавляет. */
enum class Food(val title: String, val price: Int, val fullness: Float, val mood: Float) {
    APPLE("Яблоко", 5, 15f, 2f),
    ONIGIRI("Онигири", 8, 25f, 3f),
    DONUT("Пончик", 10, 18f, 10f),
    BURGER("Бургер", 14, 45f, 5f),
    CAKE("Тортик", 18, 25f, 18f)
}

/** Уровень дружбы — зависит от шкалы «дружба». */
enum class Stage(val title: String, val minBond: Int) {
    STRANGERS("Знакомство", 0),
    BUDDIES("Приятели", 20),
    FRIENDS("Друзья", 45),
    CLOSE("Близкие друзья", 70),
    BEST("Лучшие друзья", 100);

    companion object {
        fun of(bond: Float): Stage = entries.last { bond >= it.minBond }
    }
}

/** Что сейчас нужнее всего персонажу. */
enum class Need { NONE, FOOD, SLEEP, WASH, TOILET }

/**
 * Состояние тамагочи. Все шкалы — от 0 до 100.
 *
 * Чистый Kotlin без Android: время передаётся снаружи (миллисекунды), поэтому
 * показатели честно меняются и пока приложение закрыто, а логику легко тестировать.
 */
class CareState {
    var fullness = 70f
    var energy = 80f
    var hygiene = 80f
    var bladder = 80f     // «туалет»: 100 — всё хорошо, 0 — срочно надо
    var mood = 70f
    var bond = 0f         // дружба
    var coins = 30
    val inventory = IntArray(Food.entries.size)
    var sleeping = false
    var lastUpdate = 0L
    var lastTalk = 0L
    var lastPet = 0L
    /** Финал уже показан — чтобы поздравление не всплывало повторно. */
    var finished = false

    val stage: Stage get() = Stage.of(bond)
    val justCompleted: Boolean get() = bond >= 100f && !finished

    /** Пересчитывает показатели с учётом прошедшего времени. */
    fun update(now: Long) {
        if (lastUpdate == 0L) { lastUpdate = now; return }
        var hours = max(0L, now - lastUpdate) / HOUR.toFloat()
        lastUpdate = now

        if (sleeping) {
            // Сколько часов сна нужно до полной энергии; остаток времени персонаж уже не спит
            val sleepHours = min(hours, (100f - energy) / ENERGY_SLEEP_PER_HOUR)
            energy = clamp(energy + ENERGY_SLEEP_PER_HOUR * sleepHours)
            fullness = clamp(fullness - FULLNESS_PER_HOUR * 0.5f * sleepHours)
            hygiene = clamp(hygiene - HYGIENE_PER_HOUR * 0.5f * sleepHours)
            bladder = clamp(bladder - BLADDER_PER_HOUR * 0.5f * sleepHours)
            hours -= sleepHours
            if (energy >= 100f) sleeping = false
        }
        if (hours <= 0f) return

        fullness = clamp(fullness - FULLNESS_PER_HOUR * hours)
        energy = clamp(energy - ENERGY_PER_HOUR * hours)
        hygiene = clamp(hygiene - HYGIENE_PER_HOUR * hours)
        bladder = clamp(bladder - BLADDER_PER_HOUR * hours)

        // Настроение падает быстрее, когда что-то из нужд запущено
        val neglected = listOf(fullness, energy, hygiene, bladder).count { it < 20f }
        mood = clamp(mood - (MOOD_PER_HOUR + 6f * neglected) * hours)
        if (neglected >= 2) bond = clamp(bond - 2f * hours)
    }

    /** Самая срочная нужда (если какая-то шкала ниже 30). */
    fun urgentNeed(): Need {
        val low = listOf(Need.TOILET to bladder, Need.FOOD to fullness, Need.SLEEP to energy, Need.WASH to hygiene)
            .filter { it.second < 30f }.minByOrNull { it.second }
        return low?.first ?: Need.NONE
    }

    fun canEat(): Boolean = fullness < 95f

    fun buy(food: Food): Boolean {
        if (coins < food.price) return false
        coins -= food.price
        inventory[food.ordinal]++
        return true
    }

    /** Персонаж съел еду (она уже должна быть в инвентаре). */
    fun eat(food: Food): Boolean {
        if (inventory[food.ordinal] <= 0 || !canEat()) return false
        inventory[food.ordinal]--
        fullness = clamp(fullness + food.fullness)
        mood = clamp(mood + food.mood)
        bladder = clamp(bladder - 8f)
        addBond(2f)
        return true
    }

    fun startSleep() { sleeping = true }
    fun wake() { sleeping = false }

    fun shower() { hygiene = 100f; mood = clamp(mood + 5f); addBond(1f) }
    fun toilet() { bladder = 100f; mood = clamp(mood + 3f) }

    /** Разговор: дружба растёт не чаще раза в 45 секунд. */
    fun talk(now: Long): Boolean {
        if (now - lastTalk < TALK_COOLDOWN) return false
        lastTalk = now
        mood = clamp(mood + 4f)
        addBond(2f)
        return true
    }

    fun pet(now: Long): Boolean {
        if (now - lastPet < PET_COOLDOWN) return false
        lastPet = now
        mood = clamp(mood + 2f)
        addBond(1f)
        return true
    }

    /** Награда за мини-игру: монеты за каждую пойманную звезду. */
    fun gameReward(stars: Int): Int {
        val earned = stars * COINS_PER_STAR
        coins += earned
        mood = clamp(mood + min(15f, stars.toFloat()))
        energy = clamp(energy - 6f)
        if (stars > 0) addBond(2f)
        return earned
    }

    /** Дружба растёт быстрее, когда о персонаже хорошо заботятся. */
    private fun addBond(base: Float) {
        val care = (fullness + energy + hygiene + bladder + mood) / 500f   // 0..1
        bond = clamp(bond + base * (0.4f + care))
    }

    private fun clamp(v: Float) = min(100f, max(0f, v))

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        const val FULLNESS_PER_HOUR = 10f
        const val ENERGY_PER_HOUR = 6f
        const val ENERGY_SLEEP_PER_HOUR = 300f   // полный сон — 20 минут
        const val HYGIENE_PER_HOUR = 5f
        const val BLADDER_PER_HOUR = 14f
        const val MOOD_PER_HOUR = 5f

        const val TALK_COOLDOWN = 45_000L
        const val PET_COOLDOWN = 8_000L
        const val COINS_PER_STAR = 2
    }
}
