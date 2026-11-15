package com.example.artamagotchi

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Первый экран: кто будет жить на столе, как его зовут, какой у него характер
 * и как он обращается к игроку. Если игра уже начата — сразу открываем AR.
 */
class SelectActivity : AppCompatActivity() {

    private var hero = Hero.GIRL
    private lateinit var form: ProfileForm
    private lateinit var cardGirl: View
    private lateinit var cardBoy: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = CareStore(this)
        if (store.loadProfile() != null) {
            openAr()
            return
        }

        setContentView(R.layout.activity_select)
        form = ProfileForm(findViewById(R.id.profile_form))
        cardGirl = findViewById(R.id.card_female)
        cardBoy = findViewById(R.id.card_male)
        cardGirl.setOnClickListener { select(Hero.GIRL) }
        cardBoy.setOnClickListener { select(Hero.BOY) }
        select(Hero.GIRL)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            store.reset()   // новая игра — с чистыми показателями
            store.saveProfile(form.read(hero, CareStore.DEFAULT_HEIGHT_CM))
            openAr()
        }
    }

    private fun select(h: Hero) {
        // Если имя не трогали (пусто или стоит имя другого персонажа) — подставляем имя по умолчанию
        val current = form.heroName.text.toString().trim()
        if (current.isEmpty() || Hero.entries.any { it.defaultName == current }) form.heroName.setText(h.defaultName)
        hero = h
        cardGirl.isSelected = h == Hero.GIRL
        cardBoy.isSelected = h == Hero.BOY
    }

    private fun openAr() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
