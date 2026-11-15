package com.example.artamagotchi

import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner

/**
 * Поля «имя персонажа / как к тебе обращаться / род / характер».
 * Один и тот же кусок разметки (view_profile.xml) стоит и на экране выбора, и в настройках.
 */
class ProfileForm(root: View) {
    val heroName: EditText = root.findViewById(R.id.edit_hero_name)
    private val playerName: EditText = root.findViewById(R.id.edit_player_name)
    private val playerGender: Spinner = root.findViewById(R.id.spinner_player_gender)
    private val personality: Spinner = root.findViewById(R.id.spinner_personality)

    init {
        val context = root.context
        playerGender.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            listOf("в мужском роде", "в женском роде")
        )
        personality.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            Personality.entries.map { it.title }
        )
    }

    fun fill(p: Profile) {
        heroName.setText(p.heroName)
        playerName.setText(p.playerName)
        playerGender.setSelection(if (p.playerFemale) 1 else 0)
        personality.setSelection(p.personality.ordinal)
    }

    /** Собирает профиль из полей; пустые имена заменяются значениями по умолчанию. */
    fun read(hero: Hero, heightCm: Int) = Profile(
        hero = hero,
        heroName = heroName.text.toString().trim().ifEmpty { hero.defaultName },
        playerName = playerName.text.toString().trim().ifEmpty { "друг" },
        playerFemale = playerGender.selectedItemPosition == 1,
        personality = Personality.entries[personality.selectedItemPosition.coerceIn(0, Personality.entries.size - 1)],
        heightCm = heightCm
    )
}
