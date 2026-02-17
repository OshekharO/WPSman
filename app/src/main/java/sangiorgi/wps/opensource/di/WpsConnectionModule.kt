package sangiorgi.wps.opensource.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import sangiorgi.wps.lib.WpsConnectionManager
import sangiorgi.wps.lib.WpsLibConfig
import sangiorgi.wps.opensource.BuildConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WpsConnectionModule {

    @Provides
    @Singleton
    fun provideWpsConnectionManager(@ApplicationContext context: Context): WpsConnectionManager {
        return WpsConnectionManager(context, WpsLibConfig(BuildConfig.DATA_DIR))
    }
}
