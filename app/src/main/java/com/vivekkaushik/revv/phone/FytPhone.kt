package com.vivekkaushik.revv.phone

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat

/** One update from FYT's Bluetooth service. [code] is one of FYT's FinalBt.U_* numbers. */
class FytUpdate(val code: Int, val ints: IntArray?, val strings: Array<String?>?) {

    val int: Int? get() = ints?.firstOrNull()
    val string: String? get() = strings?.firstOrNull()

    companion object {
        const val PHONE_ADDRESS = 6
        const val PHONE_NAME = 7

        /** The number in the call: strings[0], or strings[1] with the held call's number in strings[0]. */
        const val PHONE_NUMBER = 8
        const val PHONE_STATE = 9
        const val PHONEBOOK = 22

        /** How far reading the phone's contacts and calls (PBAP) has got. */
        const val PBAP_STATE = 29
        const val CALL_HISTORY = 35

        /** The updates Revv registers for. */
        val FOLLOWED = intArrayOf(PHONE_ADDRESS, PHONE_NAME, PHONE_NUMBER, PHONE_STATE, PHONEBOOK, PBAP_STATE, CALL_HISTORY)

        /** Not from the service: Revv lost its connection to it. */
        val LOST = FytUpdate(-1, null, null)
    }
}

/** What FYT's Bluetooth service has said about the phone, put together from its updates. */
data class FytLink(
    /** FinalBt.PHONE_STATE_*: see [connected] and [stage]. */
    val state: Int = STATE_DISCONNECTED,
    val name: String = "",
    val address: String = "",
    /** The number in the current call, if any. */
    val number: String = "",
    /** FinalBt.PBAP_STATE_*. */
    val pbap: Int = 0,
) {
    /** Whether a phone is connected for calls, in one or idle. */
    val connected: Boolean get() = state in LINKED

    /** Whether a call is under way: dialling, ringing, talking or more than one. */
    val inCall: Boolean get() = state in CALLING

    /** Whether FYT is reading the phone's contacts or call history right now. */
    val reading: Boolean get() = pbap == PBAP_LOADING_BOOK || pbap == PBAP_LOADING_CALLS

    /** How far a call Revv placed has got, judging by the phone's state. Null outside a call. */
    val stage: CallStage?
        get() = when (state) {
            STATE_DIALLING -> CallStage.Dialling
            STATE_TALKING, STATE_MULTIPARTY -> CallStage.Connected
            STATE_RINGING -> CallStage.Ringing
            else -> null
        }

    fun after(update: FytUpdate): FytLink = when (update.code) {
        FytUpdate.PHONE_STATE -> update.int?.let { copy(state = it) } ?: this
        FytUpdate.PHONE_NAME -> copy(name = update.string?.trim().orEmpty())
        FytUpdate.PHONE_ADDRESS -> copy(address = update.string?.trim().orEmpty())
        FytUpdate.PHONE_NUMBER -> copy(number = update.strings?.let { if (it.size >= 2) it[1] else it.firstOrNull() }.orEmpty())
        FytUpdate.PBAP_STATE -> update.int?.let { copy(pbap = it) } ?: this
        FytUpdate.LOST.code -> FytLink()
        else -> this
    }

    companion object {
        // FinalBt.PHONE_STATE_*, as FYT's own Bluetooth app reads them (Ipc_New.isStateConnect and the like).
        const val STATE_DISCONNECTED = 0
        const val STATE_LINKING = 1
        const val STATE_CONNECTED = 2
        const val STATE_DIALLING = 3
        const val STATE_RINGING = 4
        const val STATE_TALKING = 5
        const val STATE_PAIRING = 6
        const val STATE_MULTIPARTY = 7

        private val LINKED = setOf(STATE_CONNECTED, STATE_DIALLING, STATE_RINGING, STATE_TALKING, STATE_MULTIPARTY)
        private val CALLING = setOf(STATE_DIALLING, STATE_RINGING, STATE_TALKING, STATE_MULTIPARTY)

        private const val PBAP_LOADING_BOOK = 2
        private const val PBAP_LOADING_CALLS = 4
    }
}

/**
 * The phone's contacts and calls as FYT's Bluetooth app saves them: into Android's own Contacts
 * provider and call log, after reading them from the phone over PBAP.
 */
object FytPhoneBook {

    /** One call log row, before Revv labels it. */
    data class Row(val number: String, val type: CallType, val timeMillis: Long, val cachedName: String?)

    /** One phone number of a contact, starred if it's a favourite. */
    data class Number(val name: String, val number: String, val starred: Boolean)

    /** What Revv asks for to read them. The call log is left out of builds that don't declare it. */
    fun permissions(context: Context): Array<String> =
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)
            .filter { declared(context, it) }
            .toTypedArray()

    /** Whether Revv may read the contacts (and, where declared, the call log). */
    fun granted(context: Context): Boolean = permissions(context).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun mayReadCalls(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** Every phone number in the contacts, with its contact's name. */
    fun numbers(resolver: ContentResolver): List<Number> {
        val projection = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.STARRED)
        val cursor = resolver.query(Phone.CONTENT_URI, projection, null, null, null) ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(0)?.trim().orEmpty()
                    val number = it.getString(1)?.trim().orEmpty()
                    if (name.isNotEmpty() && number.isNotEmpty()) add(Number(name, number, it.getInt(2) != 0))
                }
            }
        }
    }

    /** The latest [limit] calls, newest first. */
    fun calls(resolver: ContentResolver, limit: Int = CALLS): List<Row> {
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString()).build()
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME)
        val cursor = resolver.query(uri, projection, null, null, "${CallLog.Calls.DATE} DESC") ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val type = callType(it.getInt(1)) ?: continue
                    add(Row(it.getString(0)?.trim().orEmpty(), type, it.getLong(2), it.getString(3)?.trim()?.takeIf(String::isNotEmpty)))
                }
            }
        }
    }

    /** Revv's kind of call for a CallLog.Calls.TYPE, or null for the ones it doesn't list. */
    fun callType(type: Int): CallType? = when (type) {
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallType.Incoming
        CallLog.Calls.OUTGOING_TYPE -> CallType.Outgoing
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> CallType.Missed
        else -> null
    }

    private fun declared(context: Context, permission: String): Boolean = try {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(permission)
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private const val CALLS = 500
}
