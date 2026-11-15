package com.example.artamagotchi

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Простое тело для физики: шар с положением, скоростью и вращением.
 * Падает под действием силы тяжести, отскакивает от стола, теряет скорость
 * от трения и толкается о персонажа.
 */
class Body(val radius: Float, private val bounce: Float = 0.65f) {
    val pos = FloatArray(3)
    val vel = FloatArray(3)
    var spinAngle = 0f          // для вида: катится — вращается
    var onFloor = false
    var age = 0f

    /** Сколько раз тело ударилось о стол (для мини-игры с мячом). */
    var floorHits = 0

    private val push = FloatArray(3)

    /**
     * Один шаг физики.
     * @param floorY  высота стола в мире
     * @param gravity ускорение свободного падения, м/с²
     * @param hero    персонаж для столкновений (или null)
     * @return true, если тело ударилось о персонажа
     */
    fun step(dt: Float, floorY: Float, gravity: Float, hero: Character3D?): Boolean {
        age += dt
        vel[1] -= gravity * dt
        // Сопротивление воздуха — совсем немного
        val drag = 1f - 0.3f * dt
        vel[0] *= drag; vel[2] *= drag
        for (i in 0..2) pos[i] += vel[i] * dt

        onFloor = false
        val bottom = floorY + radius
        if (pos[1] < bottom) {
            pos[1] = bottom
            if (vel[1] < -0.05f) {
                floorHits++
                vel[1] = -vel[1] * bounce
            } else {
                vel[1] = 0f
            }
            // Трение о стол
            val f = max(0f, 1f - 2.5f * dt)
            vel[0] *= f; vel[2] *= f
            onFloor = true
        }

        var hitHero = false
        if (hero != null && hero.collideSphere(pos, radius, push)) {
            // Отражаем скорость от поверхности тела
            val vn = vel[0] * push[0] + vel[1] * push[1] + vel[2] * push[2]
            if (vn < 0f) {
                for (i in 0..2) vel[i] -= (1f + bounce) * vn * push[i]
                hitHero = true
            }
        }

        val speed = sqrt(vel[0] * vel[0] + vel[2] * vel[2])
        spinAngle += speed / max(radius, 1e-3f) * dt * 57.3f
        return hitHero
    }

    fun speed(): Float = sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2])

    fun set(x: Float, y: Float, z: Float) {
        pos[0] = x; pos[1] = y; pos[2] = z
        vel.fill(0f)
        floorHits = 0
        age = 0f
    }
}
