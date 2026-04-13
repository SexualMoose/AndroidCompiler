package com.androidcompiler.core.data.di

import com.androidcompiler.core.data.repository.DataStoreSettingsRepository
import com.androidcompiler.core.data.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: DataStoreSettingsRepository
    ): SettingsRepository
}
