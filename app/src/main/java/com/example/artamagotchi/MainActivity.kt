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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
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

/**
 * AR-экран: камера с персонажем и панель ухода.
 *
 * Здесь «обвязка» AR (разрешения, жизненный цикл сессии, тапы) и игровая логика
 * поверх неё: кнопки «Покормить / Подарок / Поговорить», шкалы, сохранение, сброс.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var speechText: TextView
    private lateinit var renderer: ArRenderer

    private lateinit var store: CareStore
    private lateinit var care: CareState
    private lateinit var gender: Gender

    private lateinit var nameText: TextView
    private lateinit var stageText: TextView
    private lateinit var fullnessBar: ProgressBar
    private lateinit var moodBar: ProgressBar
    private lateinit var affectionBar: ProgressBar

    private var session: Session? = null
    private var installRequested = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideSpeech = Runnable { speechText.visibility = View.GONE }

    /** Раз в несколько секунд пересчитываем показатели, чтобы шкалы «жили». */
    private val ticker = object : Runnable {
        override fun run() {
            care.update(System.currentTimeMillis())
            refreshCareUi()
            handler.postDelayed(this, TICK_MS)
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
        val chosen = store.gender
        if (chosen == null) {
            // Персонаж не выбран (например, после сброса) — назад к выбору
            startActivity(Intent(this, SelectActivity::class.java))
            finish()
            return
        }
        gender = chosen
        care = store.load()

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surface_view)
        statusText = findViewById(R.id.status_text)
        speechText = findViewById(R.id.speech_text)
        nameText = findViewById(R.id.name_text)
        stageText = findViewById(R.id.stage_text)
        fullnessBar = findViewById(R.id.fullness_bar)
        moodBar = findViewById(R.id.mood_bar)
        affectionBar = findViewById(R.id.affection_bar)

        renderer = ArRenderer(
            context = this,
            characterDrawable = gender.drawableRes,
            characterHeight = gender.heightMeters,
            onStatusChanged = { status -> runOnUiThread { showStatus(status) } },
            onCharacterTapped = { runOnUiThread { onPetted() } }
        )

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        findViewById<Button>(R.id.btn_feed).setOnClickListener { doAction { care.feed(it, gender) } }
        findViewById<Button>(R.id.btn_gift).setOnClickListener { doAction { care.gift(it, gender) } }
        findViewById<Button>(R.id.btn_talk).setOnClickListener { doTalk() }
        findViewById<Button>(R.id.btn_reset).setOnClickListener { askReset() }

        nameText.text = gender.displayName
        setupTapDetector()
        setupInsets()
        care.update(System.currentTimeMillis())
        refreshCareUi()
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
        if (::care.isInitialized) store.save(care)
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
    // Уход
    // ------------------------------------------------------------------------

    /** Общая обёртка для кнопок: персонаж должен стоять на столе. */
    private fun doAction(action: (Long) -> CareState.Result) {
        if (!renderer.isCharacterPlaced) {
            say(getString(R.string.need_place))
            return
        }
        val result = action(System.currentTimeMillis())
        if (result.success) renderer.queueReaction()
        say(result.message)
        afterChange()
    }

    private fun doTalk() {
        if (!renderer.isCharacterPlaced) {
            say(getString(R.string.need_place))
            return
        }
        val result = care.talk(System.currentTimeMillis())
        if (result.success) renderer.queueReaction()
        // Реплику говорим всегда — просто симпатия от болтовни растёт не чаще раза в минуту
        say(Phrases.random(gender, care.stage))
        afterChange()
    }

    private fun onPetted() {
        care.pet(System.currentTimeMillis())
        afterChange()
    }

    private fun afterChange() {
        store.save(care)
        refreshCareUi()
        if (care.justCompleted) showFinale()
    }

    private fun refreshCareUi() {
        fullnessBar.progress = care.fullness.toInt()
        moodBar.progress = care.mood.toInt()
        affectionBar.progress = care.affection.toInt()
        stageText.text = care.stage.title
    }

    /** Реплика в «облачке» над панелью, пропадает через несколько секунд. */
    private fun say(text: String) {
        if (text.isEmpty()) return
        speechText.text = text
        speechText.visibility = View.VISIBLE
        handler.removeCallbacks(hideSpeech)
        handler.postDelayed(hideSpeech, SPEECH_MS)
    }

    /** Симпатия на максимуме: поздравляем и предлагаем начать с другим персонажем. */
    private fun showFinale() {
        care.finished = true
        store.save(care)
        AlertDialog.Builder(this)
            .setTitle(R.string.finale_title)
            .setMessage(getString(R.string.finale_message, gender.displayName))
            .setPositiveButton(R.string.finale_stay, null)
            .setNegativeButton(R.string.finale_restart) { _, _ -> resetGame() }
            .show()
    }

    private fun askReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ -> resetGame() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Стираем всё и возвращаемся к выбору персонажа. */
    private fun resetGame() {
        store.reset()
        startActivity(
            Intent(this, SelectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapDetector() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                renderer.queueTap(e.x, e.y)
                return true
            }
        })
        surfaceView.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    /** Подсказку сдвигаем ниже строки состояния, панель ухода — выше кнопок навигации. */
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

    private fun showStatus(status: ArStatus) {
        statusText.setText(
            when (status) {
                ArStatus.NOT_TRACKING -> R.string.status_not_tracking
                ArStatus.SEARCHING_PLANE -> R.string.status_searching_plane
                ArStatus.READY_TO_PLACE -> R.string.status_ready_to_place
                ArStatus.PLACED -> R.string.status_placed
            }
        )
    }

    private companion object {
        const val TAG = "MainActivity"
        const val TICK_MS = 5_000L
        const val SPEECH_MS = 4_000L
    }
}
