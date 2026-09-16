package neth.iecal.curbox.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

object BlurFadeAnimator {
    private const val PHASE_DURATION_MS = 250L
    private const val MAX_BLUR_RADIUS = 40f

    fun fadeOut(view: View, onComplete: () -> Unit): ValueAnimator = animate(
        view = view,
        startAlpha = view.alpha,
        endAlpha = 0f,
        startBlur = 0f,
        endBlur = MAX_BLUR_RADIUS,
        onComplete = onComplete
    )

    fun fadeIn(view: View, onComplete: () -> Unit = {}): ValueAnimator = animate(
        view = view,
        startAlpha = 0f,
        endAlpha = 1f,
        startBlur = MAX_BLUR_RADIUS,
        endBlur = 0f,
        onComplete = onComplete
    )

    fun reset(view: View) {
        view.alpha = 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(null)
    }

    private fun animate(
        view: View,
        startAlpha: Float,
        endAlpha: Float,
        startBlur: Float,
        endBlur: Float,
        onComplete: () -> Unit
    ): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PHASE_DURATION_MS
        addUpdateListener { animator ->
            val progress = animator.animatedFraction
            view.alpha = lerp(startAlpha, endAlpha, progress)
            setBlur(view, lerp(startBlur, endBlur, progress))
        }
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) onComplete()
            }
        })
        start()
    }

    private fun setBlur(view: View, radius: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        view.setRenderEffect(
            if (radius > 0.1f) {
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            } else {
                null
            }
        )
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress
    }
}
