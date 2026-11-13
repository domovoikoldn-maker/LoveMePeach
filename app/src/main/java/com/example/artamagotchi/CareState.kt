package com.example.artamagotchi

import kotlin.math.max
import kotlin.math.min

/** Кого выбрал игрок. */
enum class Gender(val displayName: String, val drawableRes: Int, val heightMeters: Float) {
    FEMALE("Мира", R.drawable.character_female, 0.21f),
    MALE("Кай", R.drawable.character_male, 0.22f)
}

/** Стадия отношений — зависит от симпатии. */
enum class Stage(val title: String, val minAffection: Int) {
    STRANGERS("Знакомство", 0),
    FRIENDS("Друзья", 25),
    CRUSH("Симпатия", 50),
    IN_LOVE("Влюблённость", 75),
    COUPLE("Вы пара", 100);

    companion object {
        fun of(affection: Float): Stage = entries.last { affection >= it.minAffection }
    }
}

/**
 * Состояние тамагочи: сытость, настроение, симпатия (всё от 0 до 100).
 *
 * Чистый Kotlin без Android — удобно тестировать. Время передаётся снаружи
 * (миллисекунды, как System.currentTimeMillis()), поэтому показатели честно
 * падают и пока приложение закрыто.
 */
class CareState(
    var fullness: Float = 70f,
    var mood: Float = 70f,
    var affection: Float = 0f,
    var lastUpdate: Long = 0L,
    var lastGift: Long = 0L,
    var lastTalk: Long = 0L,
    var lastPet: Long = 0L,
    /** Финал уже показан — чтобы поздравление не всплывало повторно. */
    var finished: Boolean = false
) {
    /** Результат действия: что сказать игроку и считать ли действие удачным (для сердечек). */
    data class Result(val message: String, val success: Boolean)

    val stage: Stage get() = Stage.of(affection)

    /** true, если только что достигнута максимальная симпатия и финал ещё не показывали. */
    val justCompleted: Boolean get() = affection >= 100f && !finished

    /** Пересчитывает показатели с учётом прошедшего времени. */
    fun update(now: Long) {
        if (lastUpdate == 0L) {
            lastUpdate = now
            return
        }
        val hours = max(0L, now - lastUpdate) / HOUR.toFloat()
        lastUpdate = now
        fullness = clamp(fullness - FULLNESS_PER_HOUR * hours)
        mood = clamp(mood - MOOD_PER_HOUR * hours)
        // Если персонаж голоден или грустит, симпатия понемногу уходит
        if (fullness <= 0f || mood <= 0f) {
            affection = clamp(affection - AFFECTION_LOSS_PER_HOUR * hours)
        }
    }

    fun feed(now: Long, who: Gender): Result {
        val name = who.displayName
        update(now)
        if (fullness >= 90f) {
            mood = clamp(mood - 5f)
            return Result(if (who == Gender.FEMALE) "$name уже наелась и вежливо отказывается" else "$name уже наелся и вежливо отказывается", false)
        }
        fullness = clamp(fullness + 30f)
        addAffection(2f)
        return Result("$name с удовольствием перекусывает", true)
    }

    fun gift(now: Long, who: Gender): Result {
        val name = who.displayName
        update(now)
        if (now - lastGift < GIFT_COOLDOWN) {
            val minutes = (GIFT_COOLDOWN - (now - lastGift) + MINUTE - 1) / MINUTE   // с округлением вверх
            return Result("Следующий подарок — через $minutes мин.", false)
        }
        lastGift = now
        mood = clamp(mood + 25f)
        addAffection(6f)
        return Result("$name в восторге от подарка!", true)
    }

    fun talk(now: Long): Result {
        update(now)
        if (now - lastTalk < TALK_COOLDOWN) return Result("", false)
        lastTalk = now
        mood = clamp(mood + 8f)
        addAffection(3f)
        return Result("", true)
    }

    /** Погладить (тап по персонажу). */
    fun pet(now: Long): Boolean {
        update(now)
        if (now - lastPet < PET_COOLDOWN) return false
        lastPet = now
        mood = clamp(mood + 3f)
        addAffection(1f)
        return true
    }

    /** Симпатия растёт быстрее, когда персонаж сыт и в хорошем настроении. */
    private fun addAffection(base: Float) {
        val care = (fullness + mood) / 200f          // 0..1
        affection = clamp(affection + base * (0.5f + care))
    }

    private fun clamp(v: Float) = min(100f, max(0f, v))

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        const val FULLNESS_PER_HOUR = 12f      // от полной сытости до нуля — около 8 часов
        const val MOOD_PER_HOUR = 9f
        const val AFFECTION_LOSS_PER_HOUR = 3f

        const val GIFT_COOLDOWN = 20 * MINUTE
        const val TALK_COOLDOWN = MINUTE
        const val PET_COOLDOWN = 8_000L
    }
}

/** Реплики персонажа в зависимости от стадии отношений. */
object Phrases {
    private val female = mapOf(
        Stage.STRANGERS to listOf("Привет… Мы ведь только познакомились?", "Ты всегда так смотришь в камеру?", "Здесь уютный стол."),
        Stage.FRIENDS to listOf("С тобой весело!", "Расскажи, как прошёл день?", "Я так рада тебя видеть."),
        Stage.CRUSH to listOf("Я тут подумала о тебе…", "Мне нравится, когда ты рядом.", "Не уходи слишком надолго, ладно?"),
        Stage.IN_LOVE to listOf("Кажется, я влюбилась…", "Ты — лучшее, что случилось с этим столом.", "Обнимешь?"),
        Stage.COUPLE to listOf("Ты — мой человек.", "Люблю тебя!", "Давай всегда так?")
    )
    private val male = mapOf(
        Stage.STRANGERS to listOf("Привет. Я Кай.", "Ну и где мы?", "Камера у тебя неплохая."),
        Stage.FRIENDS to listOf("Рад тебя видеть!", "Как прошёл день?", "С тобой не скучно."),
        Stage.CRUSH to listOf("Я ждал, когда ты заглянешь.", "Ты сегодня особенно хорошо выглядишь.", "Мне нравится с тобой болтать."),
        Stage.IN_LOVE to listOf("Кажется, я по уши влюблён.", "Без тебя тут пусто.", "Иди сюда."),
        Stage.COUPLE to listOf("Ты — мой человек.", "Люблю тебя.", "Я никуда не уйду.")
    )

    fun random(gender: Gender, stage: Stage): String =
        (if (gender == Gender.FEMALE) female else male).getValue(stage).random()
}
