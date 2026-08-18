package com.opendroid.ai.di

import android.content.Context
import com.opendroid.ai.core.security.AndroidProviderCredentialStore
import com.opendroid.ai.core.security.AndroidSensitiveMemoryStore
import com.opendroid.ai.core.security.AndroidUserProfileStore
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.core.settings.AndroidAppSettingsStore
import com.opendroid.ai.core.settings.AppSettingsStore
import com.opendroid.ai.accessibility.AndroidCallFlowVerifier
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.AndroidMediaPlaybackVerifier
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.actions.MediaPlaybackVerifier
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideProviderCredentialStore(@ApplicationContext context: Context): ProviderCredentialStore {
        return AndroidProviderCredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideUserProfileStore(@ApplicationContext context: Context): UserProfileStore {
        return AndroidUserProfileStore(context)
    }

    @Provides
    @Singleton
    fun provideSensitiveMemoryStore(@ApplicationContext context: Context): SensitiveMemoryStore {
        return AndroidSensitiveMemoryStore(context)
    }

    @Provides
    @Singleton
    fun provideAppSettingsStore(@ApplicationContext context: Context): AppSettingsStore {
        return AndroidAppSettingsStore(context)
    }

    @Provides
    @Singleton
    fun provideCallFlowVerifier(): CallFlowVerifier = AndroidCallFlowVerifier()

    @Provides
    @Singleton
    fun provideMediaPlaybackVerifier(): MediaPlaybackVerifier = AndroidMediaPlaybackVerifier()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideActionSequenceExecutor(
        actionDispatcher: dagger.Lazy<ActionDispatcher>
    ): ActionSequenceExecutor = ActionSequenceExecutor(
        executeAction = { action, params, context ->
            actionDispatcher.get().execute(action, params, context)
        },
        hasAction = { action -> actionDispatcher.get().hasAction(action) }
    )
}
