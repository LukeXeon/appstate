package open.source.appstate

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.*
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import androidx.core.app.BundleCompat
import androidx.core.content.ContentProviderCompat
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object AppStateObserver {

    private const val KEY_PROCESS_NAME = "process_name"
    private const val KEY_EVENT = "event"
    private const val KEY_BINDER = "binder"
    private const val VALUE_ON_STARTED = 1
    private const val VALUE_ON_STOPPED = 2
    private const val MSG_ON_CHANGED = 1

    @Deprecated("Internal use", level = DeprecationLevel.HIDDEN)
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            process.onReceiveEvent(context, intent)
        }
    }

    @Deprecated("Internal use", level = DeprecationLevel.HIDDEN)
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    class Startup : ContentProvider() {
        override fun onCreate(): Boolean {
            val application = ContentProviderCompat
                .requireContext(this)
                .applicationContext as Application
            AppStateObserver.application = application
            application.registerActivityLifecycleCallbacks(
                object : ActivityLifecycleCallbacks {

                    override fun onActivityStarted(activity: Activity) {
                        process.notifyMainProcess(
                            activity,
                            VALUE_ON_STARTED
                        )
                    }

                    override fun onActivityStopped(activity: Activity) {
                        process.notifyMainProcess(
                            activity,
                            VALUE_ON_STOPPED
                        )
                    }

                    override fun onActivityCreated(
                        activity: Activity,
                        savedInstanceState: Bundle?
                    ) {

                    }

                    override fun onActivityResumed(activity: Activity) {

                    }

                    override fun onActivityPaused(activity: Activity) {

                    }

                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

                    }

                    override fun onActivityDestroyed(activity: Activity) {

                    }

                }
            )
            return true
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            return null
        }

        override fun getType(uri: Uri): String? {
            return null
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            return null
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            return 0
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            return 0
        }

    }

    private class Counter(val name: String) {
        var value: Int = 0
    }

    interface OnChangeListener {
        @MainThread
        fun onChanged(isBackground: Boolean)
    }

    private abstract class AnyProcess {
        private val state = AtomicBoolean(false)
        private val observers = CopyOnWriteArraySet<OnChangeListener>()
        private val stub = object : IAppStateObserver.Stub() {
            override fun onChanged(isBackground: Boolean) {
                handler.obtainMessage(MSG_ON_CHANGED)
                    .apply {
                        arg1 = if (isBackground) 1 else 0
                    }
                    .sendToTarget()
            }
        }
        private val handler = Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                MSG_ON_CHANGED -> {
                    onReceiveChanged(msg.arg1 == 0)
                    true
                }
                else -> false
            }
        }

        fun addObserver(observer: OnChangeListener) {
            observers.add(observer)
        }

        fun removeObserver(observer: OnChangeListener) {
            observers.remove(observer)
        }

        protected open fun onReceiveChanged(isBackground: Boolean) {
            observers.forEach {
                it.onChanged(isBackground)
            }
        }

        open fun onReceiveEvent(context: Context, intent: Intent) {
            if (isDebugMode) {
                throw IllegalStateException("Called only on the main process")
            }
        }

        @Volatile
        var isBackground: Boolean = false
            protected set

        fun notifyMainProcess(context: Context, event: Int) {
            context.runCatching {
                sendBroadcast(Intent(notifyAction).apply {
                    setPackage(context.packageName)
                    setClass(context, AppStateObserver::class.java)
                    putExtra(KEY_EVENT, event)
                    putExtra(KEY_PROCESS_NAME, processName)
                    if (state.compareAndSet(false, true)) {
                        // 用于监控进程死没死
                        putExtra(KEY_BINDER, Bundle().apply {
                            BundleCompat.putBinder(this, KEY_BINDER, stub.asBinder())
                        })
                    }
                })
            }
        }

    }

    private class ChildProcess : AnyProcess() {

        override fun onReceiveChanged(isBackground: Boolean) {
            this.isBackground = isBackground
            super.onReceiveChanged(isBackground)
        }

    }

    private class MainProcess : AnyProcess() {

        private val callbacks = RemoteCallbackList<IAppStateObserver>()

        override fun onReceiveEvent(context: Context, intent: Intent) {
            val name = intent.getStringExtra(KEY_PROCESS_NAME)
            if (name.isNullOrEmpty()) {
                return
            }
            val binder = intent.getBundleExtra(KEY_BINDER)
                ?.let { BundleCompat.getBinder(it, KEY_BINDER) }
            if (binder != null) {
                callbacks.register(
                    IAppStateObserver.Stub.asInterface(binder),
                    Counter(name)
                )
            }
            try {
                var count = 0
                val size = callbacks.beginBroadcast()
                for (i in 0 until size) {
                    val counter = callbacks.getBroadcastCookie(i) as Counter
                    if (counter.name == name) {
                        when (intent.getIntExtra(KEY_EVENT, 0)) {
                            VALUE_ON_STARTED -> {
                                ++counter.value
                            }
                            VALUE_ON_STOPPED -> {
                                --counter.value
                            }
                            else -> return
                        }
                        count += counter.value
                    }
                }
                for (i in 0 until size) {
                    val callback = callbacks.getBroadcastItem(i)
                    if (count == 0) {
                        callback.runCatching { onChanged(true) }
                    } else if (isBackground) {
                        callback.runCatching { onChanged(false) }
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }

    }

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private val activityThread by lazy {
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            activityThreadClass.getDeclaredMethod("currentActivityThread")
                .apply {
                    isAccessible = true
                }.invoke(null)
        }.getOrNull()
    }

    @Volatile
    private var application: Application? = null
        get() {
            if (field == null) {
                field = runCatching {
                    activityThread!!.javaClass
                        .getDeclaredMethod("currentApplication")
                        .apply {
                            isAccessible = true
                        }.invoke(null) as Application
                }.getOrNull()
            }
            return field
        }

    private val processName by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            var name = runCatching {
                activityThread!!.javaClass
                    .getDeclaredMethod("currentProcessName")
                    .apply {
                        isAccessible = true
                    }.invoke(activityThread)
                    ?.toString()
            }.getOrNull()
            if (name.isNullOrEmpty()) {
                val pid = Process.myPid()
                File("/proc/$pid/cmdline")
                    .runCatching {
                        reader().buffered().use {
                            var pName = it.readLine()
                            if (!pName.isNullOrEmpty()) {
                                pName = pName.trim()
                                if (!pName.isNullOrEmpty()) {
                                    name = pName
                                }
                            }
                        }
                    }
                if (name.isNullOrEmpty()) {
                    runCatching {
                        val am = application!!.getSystemService(Context.ACTIVITY_SERVICE)
                                as ActivityManager
                        name = am.runningAppProcesses.find {
                            it.pid == pid
                        }?.processName
                    }
                }
            }
            name ?: ""
        }
    }

    private val notifyAction: String by lazy {
        application!!.packageName + ".APP_STATE_NOTIFY_ACTIVITY_EVENT"
    }

    private val isMainProcess: Boolean by lazy {
        processName.contains(":")
    }

    private val process by lazy {
        if (isMainProcess) {
            MainProcess()
        } else {
            ChildProcess()
        }
    }

    private val isDebugMode: Boolean by lazy {
        application!!.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    @JvmStatic
    fun addObserver(observer: OnChangeListener) {
        process.addObserver(observer)
    }

    @JvmStatic
    fun removeObserver(observer: OnChangeListener) {
        process.removeObserver(observer)
    }

    @JvmStatic
    val isBackground: Boolean
        get() {
            return process.isBackground
        }

}
