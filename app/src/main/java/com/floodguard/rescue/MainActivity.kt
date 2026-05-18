package com.floodguard.rescue

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.floodguard.rescue.state.MissionState
import com.floodguard.rescue.ui.ActiveMissionFragment
import com.floodguard.rescue.ui.MainViewModel
import com.floodguard.rescue.ui.ModelDownloadActivity
import com.floodguard.rescue.ui.PostMissionFragment
import com.floodguard.rescue.ui.PreMissionFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    val viewModel: MainViewModel by viewModels()

    private val startupPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { initApp() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        val needed = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) initApp() else startupPermissions.launch(needed.toTypedArray())
    }

    private fun initApp() {
        if (!viewModel.isModelReady()) {
            startActivity(Intent(this, ModelDownloadActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        viewModel.initialize(resources.configuration.locales[0])

        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, PreMissionFragment())
            }
        }

        observeMissionState()
    }

    private fun observeMissionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.missionState.collect { state ->
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                    when (state) {
                        is MissionState.Idle -> {
                            if (currentFragment !is PreMissionFragment) {
                                supportFragmentManager.commit {
                                    setCustomAnimations(
                                        android.R.anim.fade_in,
                                        android.R.anim.fade_out
                                    )
                                    replace(R.id.fragment_container, PreMissionFragment())
                                }
                            }
                        }
                        is MissionState.Starting -> {
                            // Countdown is handled within PreMissionFragment
                        }
                        is MissionState.Active -> {
                            if (currentFragment !is ActiveMissionFragment) {
                                supportFragmentManager.commit {
                                    setCustomAnimations(
                                        android.R.anim.fade_in,
                                        android.R.anim.fade_out
                                    )
                                    replace(R.id.fragment_container, ActiveMissionFragment())
                                }
                            }
                        }
                        is MissionState.Review -> {
                            if (currentFragment !is PostMissionFragment) {
                                supportFragmentManager.commit {
                                    setCustomAnimations(
                                        android.R.anim.fade_in,
                                        android.R.anim.fade_out
                                    )
                                    replace(R.id.fragment_container, PostMissionFragment())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.missionState.value is MissionState.Active) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                viewModel.triggerVoiceCommand()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.missionState.value is MissionState.Active) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true // consume to prevent volume UI
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
