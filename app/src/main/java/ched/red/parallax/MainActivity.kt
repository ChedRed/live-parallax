package ched.red.parallax

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("parallax")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.homepage)
        enableEdgeToEdge()
        Log.d("Wallpaper", "init! Success")

        val saveButton: ImageButton = findViewById(R.id.viewSaved)
        saveButton.setOnClickListener {
            val intent = Intent(this, WallpaperActivity::class.java)
            startActivity(intent)
        }
    }

}