package com.example.artamagotchi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlin.random.Random

/**
 * AR-экран: камера с персонажем и вся игра поверх неё —
 * шкалы, кнопки ухода, магазин, кормление с руки, сон, душ, туалет, мини-игра, настройки.
 */
class MainActivity : AppCompatActivity(), SceneListener {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var store: CareStore
    private lateinit var care: CareState
    private lateinit var profile: Profile
    private val picker = PhrasePicker()

    private lateinit var statusText: TextView
    private lateinit var speechText: TextView
    private lateinit var gameHud: TextView
    private lateinit var nightOverlay: View
    private lateinit var nameText: TextView
    private lateinit var stageText: TextView
    private lateinit var coinsText: TextView
    private lateinit var feedButton: Button
    private lateinit var sleepButton: Button
    private lateinit var sitButton: Button
    private lateinit var simonPanel: android.widget.LinearLayout
    private lateinit var gameStopButton: Button
    private lateinit var bars: List<ProgressBar>

    private var session: Session? = null
    private var installRequested = false
    private var arStatus = ArStatus.NOT_TRACKING

    private var heldFood: Food? = null     // еда «в руке»
    private var cabinBusy = false          // персонаж в душе или туалете
    private var gameRunning = false
    private var gameStars = 0
    private var gameSecondsLeft = 0
    private var heightCm = CareStore.DEFAULT_HEIGHT_CM

    private var lastSpeechAt = 0L
    private var nextIdleSpeechAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideSpeech = Runnable { speechText.visibility = View.GONE }

    /** Раз в несколько секунд: пересчёт шкал, сохранение, реплики «от себя». */
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val gameTicker = object : Runnable {
        override fun run() {
            gameSecondsLeft--
            if (gameSecondsLeft <= 0) finishGame(silent = false) else {
                showGameHud()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.error_camera_permission, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = CareStore(this)
        val loaded = store.loadProfile()
        if (loaded == null) {
            startActivity(Intent(this, SelectActivity::class.java))
            finish()
            return
        }
        profile = loaded
        heightCm = profile.heightCm
        care = store.loadCare()
        care.update(System.currentTimeMillis())

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surface_view)
        statusText = findViewById(R.id.status_text)
        speechText = findViewById(R.id.speech_text)
        gameHud = findViewById(R.id.game_hud)
        nightOverlay = findViewById(R.id.night_overlay)
        nameText = findViewById(R.id.name_text)
        stageText = findViewById(R.id.stage_text)
        coinsText = findViewById(R.id.coins_text)
        feedButton = findViewById(R.id.btn_feed)
        sleepButton = findViewById(R.id.btn_sleep)
        sitButton = findViewById(R.id.btn_sit)
        simonPanel = findViewById(R.id.simon_panel)
        gameStopButton = findViewById(R.id.btn_game_stop)
        bars = listOf(R.id.bar_fullness, R.id.bar_energy, R.id.bar_hygiene, R.id.bar_bladder, R.id.bar_mood, R.id.bar_bond)
            .map { findViewById<ProgressBar>(it) }

        renderer = ArRenderer(this, profile.hero, heightCm / 100f, this)
        renderer.setSleeping(care.sleeping)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        feedButton.setOnClickListener { onFeedClicked() }
        sleepButton.setOnClickListener { onSleepClicked() }
        findViewById<Button>(R.id.btn_shower).setOnClickListener { startCabin(Cabin.SHOWER) }
        findViewById<Button>(R.id.btn_toilet).setOnClickListener { startCabin(Cabin.TOILET) }
        findViewById<Button>(R.id.btn_talk).setOnClickListener { onTalkClicked() }
        findViewById<Button>(R.id.btn_game).setOnClickListener { showGames() }
        sitButton.setOnClickListener { if (sitting || ready()) setSitting(!sitting) }
        gameStopButton.setOnClickListener { finishGame(silent = false) }
        buildSimonPanel()
        findViewById<Button>(R.id.btn_shop).setOnClickListener { showShop() }
        findViewById<View>(R.id.btn_settings).setOnClickListener { showSettings() }
        // «Скрытый» вход в настройки характера: долгое нажатие на имя
        nameText.setOnLongClickListener { showSettings(); true }

        setupTouch()
        setupInsets()
        refreshUi()
        scheduleIdleSpeech()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        handler.post(ticker)

        if (session == null && !tryCreateSession()) return
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            statusText.setText(R.string.error_camera_busy)
            session?.close()
            session = null
            renderer.session = null
            return
        }
        renderer.session = session
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        if (gameRunning) finishGame(silent = true)
        if (::care.isInitialized) store.saveCare(care)
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::renderer.isInitialized) renderer.session = null
        session?.close()
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------
    // События сцены (приходят из GL-потока)
    // ------------------------------------------------------------------------

    override fun onStatus(status: ArStatus) = runOnUiThread {
        arStatus = status
        updateHint()
    }

    override fun onPlaced() = runOnUiThread {
        if (!care.sleeping) say(Situation.GREET)
    }

    override fun onCharacterTapped() = runOnUiThread {
        if (care.sleeping) {
            toast("Тсс… спит. Разбудить можно кнопкой «Разбудить».")
            return@runOnUiThread
        }
        val counted = care.pet(System.currentTimeMillis())
        renderer.react(Reaction.HAPPY, stars = true)
        // Реплика — только когда поглаживание «засчиталось», иначе персонаж болтал бы на каждый тап
        if (counted) say(Situation.PET)
        afterChange()
    }

    override fun onFoodEaten(food: Food) = runOnUiThread {
        care.eat(food)
        heldFood = null
        feedButton.setText(R.string.btn_feed)
        updateHint()
        say(Situation.FED)
        afterChange()
    }

    override fun onCabinDone(cabin: Cabin) = runOnUiThread {
        cabinBusy = false
        if (cabin == Cabin.SHOWER) care.shower() else care.toilet()
        afterChange()
    }

    override fun onStarCaught() = runOnUiThread {
        if (gameRunning) {
            gameStars++
            showGameHud()
        }
    }

    // ------------------------------------------------------------------------
    // Кнопки ухода
    // ------------------------------------------------------------------------

    /** Можно ли сейчас что-то делать с персонажем; если нет — объясняем почему. */
    private fun ready(): Boolean {
        when {
            !renderer.isCharacterPlaced -> toast(getString(R.string.need_place))
            care.sleeping -> toast("Тсс… спит.")
            cabinBusy -> toast("Подожди, сейчас выйдет.")
            gameRunning -> toast("Сначала доиграй!")
            else -> return true
        }
        return false
    }

    private fun onFeedClicked() {
        if (heldFood != null) {           // вторая кнопка — убрать еду из руки
            releaseFood()
            return
        }
        if (!ready()) return
        val owned = Food.entries.filter { care.inventory[it.ordinal] > 0 }
        if (owned.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("Еды нет. Загляни в магазин — монеты дают за мини-игру.")
                .setPositiveButton("В магазин") { _, _ -> showShop() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val labels = owned.map { "${it.title}  ×${care.inventory[it.ordinal]}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Чем угостить?")
            .setItems(labels) { _, which -> holdFood(owned[which]) }
            .show()
    }

    /** Берём еду «в руку»: она появляется перед камерой, её нужно поднести ко рту персонажа. */
    private fun holdFood(food: Food) {
        if (!care.canEat()) {
            renderer.react(Reaction.REFUSE)
            say(Situation.REFUSE)
            return
        }
        setSitting(false)
        heldFood = food
        renderer.holdFood(food)
        feedButton.setText(R.string.btn_feed_cancel)
        updateHint()
    }

    private fun releaseFood() {
        heldFood = null
        renderer.holdFood(null)
        feedButton.setText(R.string.btn_feed)
        updateHint()
    }

    private fun onSleepClicked() {
        if (care.sleeping) {
            care.wake()
            onWoke()
            return
        }
        if (!ready()) return
        if (care.energy > 90f) {
            toast("${profile.heroName} не хочет спать — сил и так хватает.")
            return
        }
        if (heldFood != null) releaseFood()
        setSitting(false)
        care.startSleep()
        applySleepUi()
        afterChange()
    }

    private fun onWoke() {
        applySleepUi()
        say(Situation.WAKE)
        afterChange()
    }

    private fun applySleepUi() {
        renderer.setSleeping(care.sleeping)
        nightOverlay.visibility = if (care.sleeping) View.VISIBLE else View.GONE
        sleepButton.setText(if (care.sleeping) R.string.btn_wake else R.string.btn_sleep)
    }

    private fun startCabin(type: Cabin) {
        if (!ready()) return
        if (type == Cabin.TOILET && care.bladder > 90f) {
            toast("Пока не нужно.")
            return
        }
        if (heldFood != null) releaseFood()
        setSitting(false)
        cabinBusy = true
        renderer.startCabin(type)
    }

    private fun onTalkClicked() {
        if (!ready()) return
        val now = System.currentTimeMillis()
        if (now - lastSpeechAt < MIN_SPEECH_GAP_MS) return     // не тараторим
        val counted = care.talk(now)
        renderer.react(Reaction.TALK, stars = counted)
        say(Situation.TALK)
        afterChange()
    }

    // ------------------------------------------------------------------------
    // Магазин
    // ------------------------------------------------------------------------

    private fun showShop() {
        val foods = Food.entries
        val labels = foods.map { "${it.title} — ${it.price} монет  (сытость +${it.fullness.toInt()}, настроение +${it.mood.toInt()})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Магазин · у тебя ${care.coins} монет")
            .setItems(labels) { _, which ->
                val food = foods[which]
                if (care.buy(food)) {
                    toast("Куплено: ${food.title}")
                    afterChange()
                    showShop()            // оставляем магазин открытым для следующей покупки
                } else {
                    toast("Не хватает монет. Сыграй в мини-игру!")
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ------------------------------------------------------------------------
    // Мини-игры
    // ------------------------------------------------------------------------

    private enum class MiniGame(val title: String) {
        STARS("Звездопад — лови звёзды"),
        BALL("Мячик — не дай упасть"),
        SIMON("Повторюшка — запомни движения"),
        DANCE("Танцы — просто потанцевать"),
        FREE_BALL("Поиграть с мячом")
    }

    private var currentGame: MiniGame? = null

    private fun showGames() {
        if (!ready()) return
        if (care.energy < 10f) {
            toast("${profile.heroName} без сил. Сначала сон.")
            return
        }
        val list = MiniGame.entries
        AlertDialog.Builder(this)
            .setTitle("Во что играем?")
            .setItems(list.map { it.title }.toTypedArray()) { _, which -> startGame(list[which]) }
            .show()
    }

    private fun startGame(g: MiniGame) {
        if (heldFood != null) releaseFood()
        setSitting(false)
        currentGame = g
        gameRunning = true
        gameStars = 0
        gameHud.visibility = View.VISIBLE
        when (g) {
            MiniGame.STARS -> {
                gameSecondsLeft = GAME_SECONDS
                renderer.setGame(SceneGame.STARS)
                handler.postDelayed(gameTicker, 1000)
            }
            MiniGame.DANCE -> {
                gameSecondsLeft = 20
                renderer.setGame(SceneGame.DANCE)
                handler.postDelayed(gameTicker, 1000)
            }
            MiniGame.BALL -> renderer.setGame(SceneGame.BALL)
            MiniGame.FREE_BALL -> {
                renderer.setGame(SceneGame.FREE_BALL)
                gameStopButton.visibility = View.VISIBLE
            }
            MiniGame.SIMON -> {
                simonSequence.clear()
                simonNextRound()
            }
        }
        showGameHud()
    }

    private fun showGameHud() {
        gameHud.text = when (currentGame) {
            MiniGame.STARS -> "Лови звёзды!  ★ $gameStars   ·   $gameSecondsLeft с"
            MiniGame.DANCE -> "Танцуем!  $gameSecondsLeft с"
            MiniGame.BALL -> "Подбивай мяч тапом!  Ударов: $gameStars"
            MiniGame.FREE_BALL -> "Тапай по мячу. ${profile.heroName} отбивает ногой"
            MiniGame.SIMON -> if (simonShowing) "Смотри и запоминай…  Раунд ${simonSequence.size - 1}"
                else "Повтори!  ${simonInput} из ${simonSequence.size}"
            null -> ""
        }
    }

    override fun onBallJuggled() = runOnUiThread {
        if (currentGame == MiniGame.BALL) {
            gameStars++
            showGameHud()
        }
    }

    override fun onBallDropped() = runOnUiThread {
        if (currentGame == MiniGame.BALL) finishGame(silent = false)
    }

    // --- «Повторюшка»: персонаж показывает последовательность движений, игрок повторяет кнопками ---

    private val simonMoves: List<Pair<String, Reaction>> by lazy {
        val all = mutableListOf("Прыжок" to Reaction.HAPPY, "Присед" to Reaction.SQUAT, "Поклон" to Reaction.BOW, "Кружок" to Reaction.SPIN)
        if (profile.hero.hasArms) all.add(0, "Помахать" to Reaction.WAVE)
        all
    }
    private val simonSequence = ArrayList<Int>()
    private var simonInput = 0
    private var simonShowing = false

    private fun simonNextRound() {
        // В первом раунде два движения, дальше на одно больше каждый раунд
        if (simonSequence.isEmpty()) simonSequence.add(Random.nextInt(simonMoves.size))
        simonSequence.add(Random.nextInt(simonMoves.size))
        simonInput = 0
        simonShowing = true
        simonPanel.visibility = View.GONE
        showGameHud()
        simonSequence.forEachIndexed { i, move ->
            handler.postDelayed({ if (currentGame == MiniGame.SIMON) renderer.react(simonMoves[move].second) }, 900L + i * 1500L)
        }
        handler.postDelayed({
            if (currentGame == MiniGame.SIMON) {
                simonShowing = false
                simonPanel.visibility = View.VISIBLE
                showGameHud()
            }
        }, 900L + simonSequence.size * 1500L)
    }

    private fun onSimonPressed(move: Int) {
        if (currentGame != MiniGame.SIMON || simonShowing) return
        renderer.react(simonMoves[move].second)
        if (simonSequence[simonInput] != move) {
            gameStars = simonSequence.size - 2          // столько раундов пройдено
            finishGame(silent = false)
            return
        }
        simonInput++
        showGameHud()
        if (simonInput == simonSequence.size) {
            renderer.react(Reaction.HAPPY, stars = true)
            simonPanel.visibility = View.GONE
            handler.postDelayed({ if (currentGame == MiniGame.SIMON) simonNextRound() }, 1200L)
        }
    }

    private fun buildSimonPanel() {
        simonPanel.removeAllViews()
        simonMoves.forEachIndexed { i, (title, _) ->
            val b = Button(this)
            b.text = title
            b.isAllCaps = false
            b.textSize = 12f
            b.minWidth = 0
            b.setPadding(4, 0, 4, 0)
            b.layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            b.setOnClickListener { onSimonPressed(i) }
            simonPanel.addView(b)
        }
    }

    private fun finishGame(silent: Boolean) {
        val g = currentGame ?: return
        handler.removeCallbacks(gameTicker)
        currentGame = null
        gameRunning = false
        gameHud.visibility = View.GONE
        simonPanel.visibility = View.GONE
        gameStopButton.visibility = View.GONE
        renderer.setGame(SceneGame.NONE)
        if (g == MiniGame.FREE_BALL) {
            care.gameReward(0)
            afterChange()
            return
        }
        val score = if (g == MiniGame.DANCE) 0 else gameStars
        val earned = care.gameReward(score)
        if (g == MiniGame.DANCE) care.mood = (care.mood + 10f).coerceAtMost(100f)
        if (!silent) {
            val what = when (g) {
                MiniGame.STARS -> "Поймано звёзд: $score"
                MiniGame.BALL -> "Ударов по мячу: $score"
                MiniGame.SIMON -> "Пройдено раундов: $score"
                else -> "Потанцевали!"
            }
            toast(if (earned > 0) "$what · +$earned монет" else what)
            renderer.setSad(false)
            if (score > 0 || g == MiniGame.DANCE) {
                renderer.react(Reaction.HAPPY, stars = true)
                say(Situation.WIN)
            } else {
                renderer.react(Reaction.REFUSE)
            }
        }
        afterChange()
    }

    // --- Сидеть / стоять ---

    private var sitting = false

    private fun setSitting(value: Boolean) {
        sitting = value
        renderer.setSitting(value)
        sitButton.setText(if (value) R.string.btn_stand else R.string.btn_sit)
    }

    // ------------------------------------------------------------------------
    // Настройки: имя, обращение, характер, рост, сброс
    // ------------------------------------------------------------------------

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val form = ProfileForm(view.findViewById(R.id.profile_form))
        form.fill(profile)
        val sizeLabel = view.findViewById<TextView>(R.id.size_label)
        val sizeBar = view.findViewById<SeekBar>(R.id.size_bar)
        val before = heightCm
        var chosen = heightCm
        sizeBar.max = CareStore.MAX_HEIGHT_CM - CareStore.MIN_HEIGHT_CM
        sizeBar.progress = heightCm - CareStore.MIN_HEIGHT_CM
        sizeLabel.text = getString(R.string.size_label, heightCm)
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                chosen = progress + CareStore.MIN_HEIGHT_CM
                sizeLabel.text = getString(R.string.size_label, chosen)
                renderer.setHeroHeight(chosen / 100f)     // рост меняется сразу — видно на столе
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                heightCm = chosen
                profile = form.read(profile.hero, heightCm)
                store.saveProfile(profile)
                refreshUi()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> renderer.setHeroHeight(before / 100f) }
            .setNeutralButton("Сбросить игру") { _, _ -> askReset() }
            .setOnCancelListener { renderer.setHeroHeight(before / 100f) }
            .show()
    }

    private fun setHeight(cm: Int, save: Boolean) {
        heightCm = cm.coerceIn(CareStore.MIN_HEIGHT_CM, CareStore.MAX_HEIGHT_CM)
        renderer.setHeroHeight(heightCm / 100f)
        if (save) {
            profile = profile.copy(heightCm = heightCm)
            store.saveProfile(profile)
        }
    }

    private fun askReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ -> resetGame() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetGame() {
        store.reset()
        startActivity(
            Intent(this, SelectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ------------------------------------------------------------------------
    // Шкалы, реплики, таймер
    // ------------------------------------------------------------------------

    private fun tick() {
        val now = System.currentTimeMillis()
        val wasSleeping = care.sleeping
        care.update(now)
        if (wasSleeping && !care.sleeping) onWoke()       // выспался и проснулся сам
        refreshUi()
        store.saveCare(care)

        val need = care.urgentNeed()
        renderer.setSad(!care.sleeping && (need != Need.NONE || care.mood < 25f))

        // Реплики «от себя»: не чаще раза в 35–75 секунд и только когда персонаж свободен
        val free = renderer.isCharacterPlaced && !care.sleeping && !cabinBusy && !gameRunning && heldFood == null
        // Иногда сам присаживается отдохнуть и потом встаёт
        if (free && !sitting && care.energy < 70f && Random.nextFloat() < 0.04f) setSitting(true)
        else if (sitting && Random.nextFloat() < 0.05f) setSitting(false)

        if (free && now >= nextIdleSpeechAt) {
            val situation = when (need) {
                Need.FOOD -> Situation.HUNGRY
                Need.SLEEP -> Situation.SLEEPY
                Need.WASH -> Situation.DIRTY
                Need.TOILET -> Situation.TOILET
                Need.NONE -> if (Random.nextFloat() < 0.6f) Situation.TALK else null
            }
            if (situation != null) {
                renderer.react(Reaction.TALK)
                say(situation)
            } else {
                scheduleIdleSpeech()
            }
        }
    }

    private fun afterChange() {
        store.saveCare(care)
        refreshUi()
        if (care.justCompleted) showFinale()
    }

    private fun refreshUi() {
        nameText.text = profile.heroName
        stageText.text = care.stage.title
        coinsText.text = getString(R.string.coins, care.coins)
        val values = listOf(care.fullness, care.energy, care.hygiene, care.bladder, care.mood, care.bond)
        for (i in bars.indices) bars[i].progress = values[i].toInt()
        nightOverlay.visibility = if (care.sleeping) View.VISIBLE else View.GONE
        sleepButton.setText(if (care.sleeping) R.string.btn_wake else R.string.btn_sleep)
    }

    /** Персонаж говорит реплику по ситуации (без повторов подряд — см. PhrasePicker). */
    private fun say(situation: Situation) {
        val text = picker.next(profile.personality, situation, profile.playerName, profile.hero.female, profile.playerFemale)
        speechText.text = text
        speechText.visibility = View.VISIBLE
        handler.removeCallbacks(hideSpeech)
        handler.postDelayed(hideSpeech, SPEECH_MS)
        lastSpeechAt = System.currentTimeMillis()
        scheduleIdleSpeech()
    }

    private fun scheduleIdleSpeech() {
        nextIdleSpeechAt = System.currentTimeMillis() + Random.nextLong(35_000L, 75_000L)
    }

    private fun showFinale() {
        care.finished = true
        store.saveCare(care)
        AlertDialog.Builder(this)
            .setTitle(R.string.finale_title)
            .setMessage(getString(R.string.finale_message, profile.heroName))
            .setPositiveButton(R.string.finale_stay, null)
            .setNegativeButton(R.string.finale_restart) { _, _ -> resetGame() }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun updateHint() {
        if (heldFood != null && arStatus == ArStatus.PLACED) {
            statusText.setText(R.string.hint_hand_feed)
            return
        }
        statusText.setText(
            when (arStatus) {
                ArStatus.NOT_TRACKING -> R.string.status_not_tracking
                ArStatus.SEARCHING_PLANE -> R.string.status_searching_plane
                ArStatus.READY_TO_PLACE -> R.string.status_ready_to_place
                ArStatus.PLACED -> R.string.status_placed
            }
        )
    }

    // ------------------------------------------------------------------------
    // AR-обвязка
    // ------------------------------------------------------------------------

    private fun tryCreateSession(): Boolean {
        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(this, !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return false
            }
            val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            if (permission != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                return false
            }
            val newSession = Session(this)
            val config = Config(newSession)
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL)
            config.setFocusMode(Config.FocusMode.AUTO)
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
            newSession.configure(config)
            session = newSession
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать сессию ARCore", e)
            statusText.setText(
                when (e) {
                    is UnavailableArcoreNotInstalledException,
                    is UnavailableUserDeclinedInstallationException -> R.string.error_arcore_not_installed
                    is UnavailableApkTooOldException -> R.string.error_arcore_too_old
                    is UnavailableSdkTooOldException -> R.string.error_app_too_old
                    is UnavailableDeviceNotCompatibleException -> R.string.error_device_not_supported
                    else -> R.string.error_session_failed
                }
            )
            return false
        }
    }

    /** Тап — действие в сцене; щипок двумя пальцами — меняет рост персонажа. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                renderer.queueTap(e.x, e.y)
                return true
            }
        })
        var pinchHeight = heightCm.toFloat()
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchHeight = heightCm.toFloat()
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchHeight *= detector.scaleFactor
                setHeight(pinchHeight.toInt(), save = false)
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                setHeight(heightCm, save = true)
                toast(getString(R.string.size_label, heightCm))
            }
        })
        surfaceView.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            if (!pinch.isInProgress) taps.onTouchEvent(event)
            true
        }
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val margin = resources.getDimensionPixelSize(R.dimen.status_margin)
        val panel = findViewById<View>(R.id.care_panel)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            statusText.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = margin + safe.top }
            panel.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = margin + safe.bottom }
            insets
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val TICK_MS = 3_000L
        const val SPEECH_MS = 4_500L
        const val MIN_SPEECH_GAP_MS = 2_500L
        const val GAME_SECONDS = 25
    }
}
