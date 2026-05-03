package com.example.pisurveillance

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.pisurveillance.databinding.ActivityMainBinding
import com.example.pisurveillance.viewmodel.SurveillanceViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import timber.log.Timber

/**
 * Main activity for the surveillance app
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var viewModel: SurveillanceViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        // Initialize ViewModel
        viewModel = ViewModelProvider(this).get(SurveillanceViewModel::class.java)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_video,
                R.id.nav_settings,
                R.id.nav_status
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Set up bottom navigation
        val navView: BottomNavigationView = binding.bottomNavigation
        navView.setupWithNavController(navController)

        // Observe connection state
        viewModel.isConnected.observe(this) { isConnected ->
            binding.connectionStatus.visibility = if (isConnected == true) View.GONE else View.VISIBLE
        }

        viewModel.connectionError.observe(this) { error ->
            if (error != null) {
                binding.errorMessage.text = error
                binding.errorMessage.visibility = View.VISIBLE
            } else {
                binding.errorMessage.visibility = View.GONE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onStop() {
        super.onStop()
        // Disconnect when app is moved to background (Home button)
        // but not during screen rotation
        if (!isChangingConfigurations) {
            viewModel.disconnect()
        }
    }

    fun getViewModel(): SurveillanceViewModel = viewModel

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
    }
}
