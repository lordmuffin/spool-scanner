package com.bambu.nfc.di

import android.content.Context
import androidx.room.Room
import com.bambu.nfc.BuildConfig
import com.bambu.nfc.data.labelito.LabelitoClient
import com.bambu.nfc.data.local.AppDatabase
import com.bambu.nfc.data.local.SpoolCache
import com.bambu.nfc.data.settings.SettingsStore
import com.bambu.nfc.data.spoolman.SpoolmanApiProvider
import com.bambu.nfc.data.spoolman.SpoolmanRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "bambu_nfc.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideSpoolCache(db: AppDatabase): SpoolCache = SpoolCache(db.cachedSpoolDao())

    @Provides
    @Singleton
    fun provideSettings(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    @Provides
    @Singleton
    fun provideSpoolmanRepository(okHttp: OkHttpClient, settings: SettingsStore): SpoolmanRepository =
        SpoolmanRepository(SpoolmanApiProvider(okHttp) { settings.current.spoolmanUrl })

    @Provides
    @Singleton
    fun provideLabelito(okHttp: OkHttpClient, settings: SettingsStore): LabelitoClient =
        LabelitoClient(okHttp) {
            settings.current.let { LabelitoClient.Config(it.labelitoUrl, it.labelitoToken) }
        }
}
