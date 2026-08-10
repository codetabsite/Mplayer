package com.tdev.mplayr.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.tdev.mplayr.R

class ThemeActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private var selectedTheme = ThemeManager.themes[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyCurrentTheme()
        setContentView(R.layout.activity_theme)

        container = findViewById(R.id.themeContainer)
        selectedTheme = ThemeManager.getSaved(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        buildThemeCards()
    }

    private fun applyCurrentTheme() {
        val t = ThemeManager.getSaved(this)
        window.decorView.setBackgroundColor(t.bg)
        window.statusBarColor = t.bg
    }

    private fun buildThemeCards() {
        container.removeAllViews()
        ThemeManager.themes.forEach { theme ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(20, 16, 20, 16)
                setBackgroundColor(theme.surface)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 8)
                layoutParams = lp
            }

            // Renk önizleme kutusu
            val preview = View(this).apply {
                setBackgroundColor(theme.accent)
                val dp32 = (32 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(dp32, dp32).also {
                    it.setMargins(0, 0, 16, 0)
                }
            }

            // İsim + seçili işareti
            val tvName = TextView(this).apply {
                text = theme.label
                setTextColor(theme.textPrimary)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvCheck = TextView(this).apply {
                text = if (theme.id == selectedTheme.id) "[seçili]" else ""
                setTextColor(theme.accent)
                textSize = 18f
            }

            card.addView(preview)
            card.addView(tvName)
            card.addView(tvCheck)

            card.setOnClickListener {
                selectedTheme = theme
                ThemeManager.apply(this, theme)
                // Tüm Activity'leri yeniden başlat — stack'te olana gerek yok
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                 android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finishAffinity()
            }

            container.addView(card)
        }
    }
}
