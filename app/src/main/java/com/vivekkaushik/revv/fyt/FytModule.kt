package com.vivekkaushik.revv.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log

/**
 * One module of FYT's system service com.syu.ms, on FYT head units (the com.syu.* firmware):
 * 0 is the main module (reverse gear and the like), 2 is Bluetooth. Talks com.syu.ipc's binder
 * interfaces the way FYT's own apps do (docs/FYT_BLUETOOTH.md); there's no AIDL for them in the
 * SDK, so the parcels are written here by hand.
 *
 * [onUpdate] hears the [followed] updates, as (code, ints, strings), on a binder thread, and
 * [onLost] hears the service go away. Call the rest on the main thread.
 */
class FytModule(
    private val context: Context,
    private val module: Int,
    private val followed: IntArray,
    private val onUpdate: (code: Int, ints: IntArray?, strings: Array<String?>?) -> Unit,
    private val onLost: () -> Unit = {},
) {
    @Volatile private var remote: IBinder? = null
    private var bound = false

    /** Whether the service is bound and has handed over the module. */
    val connected: Boolean get() = remote?.isBinderAlive == true

    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != UPDATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK)
            val update = data.readInt()
            val ints = data.createIntArray()
            data.createFloatArray()
            val strings = data.createStringArray()
            onUpdate(update, ints, strings)
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val handed = try {
                remoteModule(service, module)
            } catch (e: RemoteException) {
                Log.w(TAG, "com.syu.ms refused module $module", e)
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "com.syu.ms refused module $module", e)
                null
            } ?: return
            remote = handed
            // As FYT's own apps register; the trailing 1 likely asks for the value right away.
            for (update in followed) transact(handed, REGISTER) { writeStrongBinder(callback); writeInt(update); writeInt(1) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Android binds again once com.syu.ms is back; onServiceConnected registers afresh.
            remote = null
            onLost()
        }
    }

    /** Binds com.syu.ms. False if this isn't an FYT unit or it wouldn't let Revv in. */
    fun bind(): Boolean {
        if (bound) return true
        bound = try {
            context.bindService(Intent().setComponent(TOOLKIT), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't bind com.syu.ms", e)
            false
        }
        if (!bound) runCatching { context.unbindService(connection) }
        return bound
    }

    fun unbind() {
        if (!bound) return
        remote?.let { handed -> for (update in followed) transact(handed, UNREGISTER) { writeStrongBinder(callback); writeInt(update) } }
        remote = null
        bound = false
        runCatching { context.unbindService(connection) }
    }

    /** One of the module's commands. False if the service isn't there to ask. */
    fun command(code: Int, ints: IntArray? = null, strings: Array<String>? = null): Boolean {
        val handed = remote ?: return false
        return transact(handed, CMD) {
            writeInt(code)
            writeIntArray(ints)
            writeFloatArray(null)
            writeStringArray(strings)
        }
    }

    /** A oneway call on the module, as com.syu.ipc.IRemoteModule's proxy makes it. */
    private fun transact(handed: IBinder, code: Int, write: Parcel.() -> Unit): Boolean {
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MODULE)
            data.write()
            handed.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: RemoteException) {
            false
        } finally {
            data.recycle()
        }
    }

    /** com.syu.ipc.IRemoteToolkit.getRemoteModule(code). */
    private fun remoteModule(toolkit: IBinder, code: Int): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOOLKIT_INTERFACE)
            data.writeInt(code)
            toolkit.transact(GET_REMOTE_MODULE, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        private const val TAG = "FytModule"

        const val MODULE_MAIN = 0
        const val MODULE_BT = 2

        private val TOOLKIT = ComponentName("com.syu.ms", "app.ToolkitService")
        private const val TOOLKIT_INTERFACE = "com.syu.ipc.IRemoteToolkit"
        private const val MODULE = "com.syu.ipc.IRemoteModule"
        private const val CALLBACK = "com.syu.ipc.IModuleCallback"

        // Transaction codes, in the order the interfaces declare their methods.
        private const val GET_REMOTE_MODULE = IBinder.FIRST_CALL_TRANSACTION
        private const val CMD = IBinder.FIRST_CALL_TRANSACTION
        private const val REGISTER = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val UNREGISTER = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val UPDATE = IBinder.FIRST_CALL_TRANSACTION

        /** Whether this head unit has FYT's service. */
        fun present(context: Context): Boolean = try {
            context.packageManager.resolveService(Intent().setComponent(TOOLKIT), 0) != null
        } catch (e: RuntimeException) {
            false
        }
    }
}
