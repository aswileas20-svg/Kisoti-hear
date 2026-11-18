

package com.kisoti.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screen = TextView(this).apply {
            text = "Kisoti-Hear\n\nKaribu kwenye Kisoti-Hear"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        setContentView(screen)
    }
}
