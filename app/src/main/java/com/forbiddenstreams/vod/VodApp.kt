package com.forbiddenstreams.vod

import android.app.Application
import android.content.Context
import com.forbiddenstreams.vod.data.Account
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.forbiddenstreams.vod.data.LocalStore
import com.forbiddenstreams.vod.data.UpdateInfo
import com.forbiddenstreams.vod.data.UpdateManager
import com.forbiddenstreams.vod.data.XtreamClient
import com.forbiddenstreams.vod.data.XtreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class VodApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A single tiny read, done up front so every screen (and the player after
        // process death) can rely on the account being known.
        runBlocking { container.store.account.first() }?.let(container::activate)
    }
}

/** Simple manual dependency container. */
class AppContainer(context: Context) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val store = LocalStore(context)

    val updates = UpdateManager(context, http)

    /** Set by the background check on the home screen; shows a badge on the Settings button. */
    var availableUpdate by mutableStateOf<UpdateInfo?>(null)
    var checkedForUpdates = false

    /** For work that must finish even if the screen that started it closes (e.g. saving progress). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var account: Account? = null
        private set

    var repository: XtreamRepository? = null
        private set

    fun activate(account: Account) {
        this.account = account
        repository = XtreamRepository(XtreamClient(http, account))
    }

    suspend fun signOut() {
        store.clearAccount()
        account = null
        repository = null
    }
}

fun Context.appContainer(): AppContainer = (applicationContext as VodApp).container
