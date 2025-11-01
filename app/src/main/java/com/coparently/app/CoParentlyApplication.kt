package com.coparently.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for CoParently.
 * Marks the application for Hilt dependency injection.
 *
 * @see HiltAndroidApp
 */
@HiltAndroidApp
class CoParentlyApplication : Application()

