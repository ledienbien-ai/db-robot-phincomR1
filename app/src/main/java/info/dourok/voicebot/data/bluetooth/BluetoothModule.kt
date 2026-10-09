package info.dourok.voicebot.data.bluetooth

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import info.dourok.voicebot.domain.bluetooth.BtController
import javax.inject.Singleton

/** Binds the Bluetooth-audio-out port (domain) to its device implementation (data). */
@Module
@InstallIn(SingletonComponent::class)
object BluetoothModule {

    @Provides
    @Singleton
    fun provideBtController(@ApplicationContext context: Context): BtController =
        AndroidBtController(context)
}
