package com.example.artamagotchi

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Первый экран: выбор, о ком заботиться.
 * Если персонаж уже выбран раньше — сразу открываем AR.
 */
class SelectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = CareStore(this)

        if (store.gender != null) {
            openAr()
            return
        }

        setContentView(R.layout.activity_select)
        findViewById<View>(R.id.card_female).setOnClickListener { choose(store, Gender.FEMALE) }
        findViewById<View>(R.id.card_male).setOnClickListener { choose(store, Gender.MALE) }
    }

    private fun choose(store: CareStore, gender: Gender) {
        store.reset()           // новая игра — с чистыми показателями
        store.gender = gender
        openAr()
    }

    private fun openAr() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
