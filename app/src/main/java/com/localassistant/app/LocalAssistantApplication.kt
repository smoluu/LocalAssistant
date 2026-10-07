package com.localassistant.app

import android.app.Application

/**
 * Application class for Local Assistant.
 * 
 * Provides application-level singleton access and initializes core components.
 */
class LocalAssistantApplication : Application() {

    companion object {
        @Volatile
        private var INSTANCE: LocalAssistantApplication? = null
        
        /**
         * Get the application instance from anywhere in the app.
         * Use with caution - prefer dependency injection or passing context explicitly.
         */
        fun getInstance(): LocalAssistantApplication {
            return INSTANCE ?: throw IllegalStateException(
                "LocalAssistantApplication not initialized. Call getApplicationContext() first."
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(this) {
            if (INSTANCE == null) {
                INSTANCE = this
            }
        }
    }
}
