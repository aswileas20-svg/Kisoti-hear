
package com.kisoti.app

import android.app.Activity
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.widget.TextView
import android.view.Gravity
import android.widget.Toast
import android.os.Build

class MainActivity : Activity() {

    private val microphoneRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screen = TextView(this).apply {
            text = "Kisoti-Hear\n\nKaribu kwenye Kisoti-Hear"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        setContentView(screen)

        checkMicrophonePermission()
    }

    private fun checkMicrophonePermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    microphoneRequestCode
                )

            } else {
                Toast.makeText(
                    this,
                    "Microphone iko tayari",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == microphoneRequestCode) {

            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                Toast.makeText(
                    this,
                    "Ruhusa ya microphone imekubaliwa",
                    Toast.LENGTH_SHORT
                ).show()

            } else {

                Toast.makeText(
                    this,
                    "Ruhusa ya microphone imekataliwa",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
