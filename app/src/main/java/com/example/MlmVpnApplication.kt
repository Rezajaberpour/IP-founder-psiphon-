package com.example

import android.app.Application
import com.example.data.local.MlmDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.MlmRepository

class MlmVpnApplication : Application() {
    val database: MlmDatabase by lazy { MlmDatabase.getInstance(this) }
    val preferencesRepository: UserPreferencesRepository by lazy { UserPreferencesRepository(this) }
    val repository: MlmRepository by lazy {
        MlmRepository(
            database = database,
            preferences = preferencesRepository
        )
    }
}
