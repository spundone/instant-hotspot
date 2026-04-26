package com.spandan.instanthotspot

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.spandan.instanthotspot.core.AppPrefs
import com.spandan.instanthotspot.core.OnboardingV2

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_settings)
        val bar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        setSupportActionBar(bar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        bar.setNavigationOnClickListener { finish() }
        bar.setTitle(R.string.settings_title)
        val sw = findViewById<MaterialSwitch>(R.id.switchSettingsVerbose)
        sw.isChecked = AppPrefs.isVerboseDebugEnabled(this)
        sw.setOnCheckedChangeListener { _, enabled ->
            AppPrefs.setVerboseDebugEnabled(this, enabled)
        }
        findViewById<MaterialButton>(R.id.btnSettingsRerunOnboarding).setOnClickListener {
            OnboardingV2.resetFlow(this)
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            finish()
        }
    }
}
