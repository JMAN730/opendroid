package com.opendroid.ai.di

import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMProviderResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LLMModule {
    @Binds
    @Singleton
    abstract fun bindLLMProviderResolver(factory: LLMProviderFactory): LLMProviderResolver
}
