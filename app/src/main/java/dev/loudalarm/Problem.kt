package dev.loudalarm

import kotlin.random.Random

/** Two-digit addition: easy when awake, annoying when half asleep. */
data class Problem(val a: Int, val b: Int) {
    val answer: Int get() = a + b

    override fun toString() = "$a + $b"

    companion object {
        /** Problems the user must solve before snooze or dismiss unlocks. */
        const val REQUIRED = 3

        fun random(rng: Random = Random.Default) = Problem(rng.nextInt(12, 60), rng.nextInt(12, 40))
    }
}
