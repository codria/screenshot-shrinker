package com.codria.screenshotshrinker.util

import android.view.View
import android.widget.TextView

class TopToast(private val view: TextView) {

    private val hideRunnable = Runnable {
        view.animate().alpha(0f).setDuration(200L).withEndAction {
            view.visibility = View.GONE
        }.start()
    }

    fun show(message: String) {
        view.removeCallbacks(hideRunnable)
        view.text = message
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(180L).start()
        view.postDelayed(hideRunnable, 1500L)
    }

    fun show(resId: Int) = show(view.context.getString(resId))
}
