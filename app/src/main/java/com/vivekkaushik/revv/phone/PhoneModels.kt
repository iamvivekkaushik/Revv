package com.vivekkaushik.revv.phone

/** How a call in the phone's call history went. */
enum class CallType { Incoming, Outgoing, Missed }

/**
 * One call from the phone's call history. [number] is what to dial, blank when the caller withheld
 * it; [label] is the contact's name, or else the number laid out for reading. [timeMillis] is 0 when
 * the phone didn't say when.
 */
data class Call(val number: String, val label: String, val type: CallType, val timeMillis: Long)

/** Someone in the favourites grid: a favourite on the phone, or else someone called often. */
data class Favourite(val name: String, val number: String, val starred: Boolean)

/** Someone in the phone's contact list, with the number to call. */
data class Contact(val name: String, val number: String)

/** How far reading the phone's calls and contacts over Bluetooth has got. */
enum class PhoneSync {
    /** Android 12+ hasn't let Revv use Bluetooth yet. */
    NeedsPermission,

    /** Bluetooth is off, or no phone is paired. */
    NoPhone,

    /** Connecting to the phone and reading its call history. */
    Reading,

    /** The phone is asking its owner whether to share contacts and call history. */
    AwaitingApproval,

    /** The calls and favourites shown are the phone's, as of [PhoneState.syncedAt]. */
    Synced,

    /** The phone couldn't be reached, or refused; [PhoneState.problem] says which. */
    Failed,
}

/** The phone whose calls and contacts Revv shows. */
data class PhoneLink(
    val name: String,
    val address: String,
    /** Charge in percent, when the phone reports it. */
    val battery: Int?,
    /** Whether it's connected to this head unit right now, for calls and audio. */
    val connected: Boolean,
)

data class PairedPhone(val name: String, val connected: Boolean)

/** Where a call goes. */
enum class CallRoute {
    /** Revv is having the phone dial, as a hands-free device of its own. */
    Phone,

    /** The head unit's own hands-free link to the phone places it, through the unit's dialer. */
    HeadUnit,

    /** There's no phone to call on. */
    None,
}

/** What the phone screens show: the connected phone's own calls and favourites, read over Bluetooth. */
data class PhoneState(
    val sync: PhoneSync = PhoneSync.NoPhone,
    val link: PhoneLink? = null,
    val problem: String? = null,
    val syncedAt: Long? = null,
    val recents: List<Call> = emptyList(),
    val favourites: List<Favourite> = emptyList(),
    /** The phone's contacts, A to Z. */
    val contacts: List<Contact> = emptyList(),
    val pairedPhones: List<PairedPhone> = emptyList(),
)

/** How far a call placed from Revv has got. */
enum class CallStage { Dialling, Ringing, Connected, Ending }

/** A call Revv placed through the phone and is following. [answeredAt] is SystemClock.elapsedRealtime when it connected. */
data class ActiveCall(val number: String, val name: String?, val stage: CallStage, val answeredAt: Long? = null)
