# Bluetooth on FYT head units

How phone Bluetooth works on FYT units (the `com.syu.*` firmware) and how Revv can use it.
Mapped on 2026-11-07 from a UIS8581A unit (Unisoc sp9863a, Android 10, `ro.fyt.platform=6316`,
`com.syu.bt` and `com.syu.ms` built 2024-03-04). The mapping comes from the unit's init scripts,
system properties and device nodes, and from disassembling `com.syu.bt`, `com.syu.ms` and
`blink5` with the SDK's `dexdump`. **Nothing here has been run from a third-party app yet**
(see [Not yet verified](#not-yet-verified)).

## Summary

- On this unit the Bluetooth radio is the SoC's own Unisoc combo chip (`/dev/ttyBT0`), the same one
  Android would use. There is no separate Bluetooth module.
- Android's Bluetooth stack doesn't drive the radio. FYT's root daemon `blink5` runs its own
  Bluetooth stack on it, with its own pairing list (`/data/blink/`). That's why pairing from Android
  Settings fails while FYT's Bluetooth app works.
- Apps reach blink5 through FYT's system service `com.syu.ms`, over a small binder API
  (`com.syu.ipc.*`). `com.syu.ms` is exported and needs no permission.
- `com.syu.bt` copies the phone's contacts into Android's Contacts provider and its calls into
  `CallLog`. Revv can read both with `READ_CONTACTS` and `READ_CALL_LOG`, with no Bluetooth work at all.

## The stack

```
 com.syu.bt (UI, system uid)        Revv / any app
          \                          /
           binder: com.syu.ms/app.ToolkitService  (IRemoteToolkit → IRemoteModule #2 "BT")
                         │
           com.syu.ms (system uid)
                         │  text commands over a pseudo-terminal
           /dev/BT_serial → /dev/pts/0   (also /dev/auto_serial, /dev/rf_serial)
                         │
           blink5 (root; init service, started by sys.fyt.bluetooth_type=8)
             HFP, A2DP sink, AVRCP, PBAP, MAP, SPP, HID, GATT, wireless CarPlay
                         │
           /dev/ttyBT0 → Unisoc Marlin3 (SC2355) Wi-Fi/BT combo chip
```

- `/vendor/etc/init/hw/init.syu.rc`: the one-shot `bttype` service probes UART `/dev/ttyS4` for
  an external RTL8761 module. Then `sys.fyt.bluetooth_type` picks the daemon: 1 `blink` (RTL8761),
  2 `brlinkd` (IVT), 3 `gocsdk` (RDA5876), 4 `gocsdk5` (CSR BC5), 5 `gocsdk2` (RF210), 6 `gocsdk7`
  (WQ3007), 7 `blink131` (QCC3031), 8 `blink5` (Unisoc's own BT). Types 1–7 are separate modules on
  `/dev/ttyS4`. Every type is driven the same way from `com.syu.ms`, so the API below should hold
  across them.
- FYT patched Android's own Bluetooth app. `AdapterApp` reads `sys.fyt.bluetooth_type`; for types 1
  and 8 it loads `libbluetooth_jni1.so`, which relays Android's Bluetooth calls to blink5 over the
  abstract sockets `@com.chengqian.localsocket` and `@com.chengqian.gattcsocket` (see
  [Android's API through the relay](#androids-api-through-the-relay)). blink5 also serves SPP data
  on `/dev/socket/goc_spp`, plus two more ptys for projection: `/dev/auto_serial` (Android Auto) and
  `/dev/rf_serial` (CarPlay).
- SELinux is **permissive** on this unit (`/sys/fs/selinux/enforce` = 0). That, plus `0666` ptys and
  sockets, is the only reason an ordinary app can reach blink5's channels directly. Don't count on
  it on other FYT units: under enforcing policy, apps can't open `devpts` or write `socket_device`.
- Other `persist.*` knobs blink5 reads: `btpair.ssp`, `btmic.gain`, `btspk.gain`, `bta2dpvol.gain`,
  `btaec.switch`, `bta2dp.startandstop`, `wificarplay.status`.

## Binder API

Bind `ComponentName("com.syu.ms", "app.ToolkitService")` (action `com.syu.ms.toolkit`). The
service has no `android:permission`, and with an intent filter and `targetSdkVersion 17` it's
exported. On Android 11+ units, Revv would also need `<queries><package android:name="com.syu.ms"/></queries>`.

```aidl
package com.syu.ipc;

interface IRemoteToolkit {
    IRemoteModule getRemoteModule(int moduleCode);                        // txn 1
}

interface IRemoteModule {
    oneway void cmd(int code, in int[] ints, in float[] flts, in String[] strs);    // txn 1
    ModuleObject get(int code, in int[] ints, in float[] flts, in String[] strs);   // txn 2
    oneway void register(IModuleCallback cb, int updateCode, int update);           // txn 3
    oneway void unregister(IModuleCallback cb, int updateCode);                     // txn 4
}

oneway interface IModuleCallback {
    void update(int updateCode, in int[] ints, in float[] flts, in String[] strs);  // txn 1
}

parcelable ModuleObject;   // written as: int[] ints, float[] flts, String[] strs
```

Module codes (`FinalRemoteToolkit`): MAIN 0, RADIO 1, **BT 2**, DVD 3, SOUND 4, IPOD 5, TV 6,
CANBUS 7, TPMS 8, DVR 9, STEER 10, CUSTOMER 11, OBD 12, TEST 13, CAN_UP 14, AMP 15.

How com.syu.bt connects: `module = toolkit.getRemoteModule(2)`, then
`for (code in 0 until 100) module.register(callback, code, 1)`. The trailing `1` most likely asks
for the current value straight away (unverified). Ints arrive in `ints[0]`; strings in `strs[0]`.

### Updates (blink5 → app), `FinalBt.U_*`

| Code | Name | Payload |
|---:|---|---|
| 9 | `U_PHONE_STATE` | `ints[0]`: 0 disconnected, 1 link, 2 connected, 3 dialling, 4 ringing, 5 talking, 6 pairing, 7 multi-party |
| 6 | `U_PHONE_MAC_ADDR` | `strs[0]` connected phone's address |
| 7 | `U_PHONE_NAME` | `strs[0]` connected phone's name |
| 8 | `U_PHONE_NUMBER` | `strs[0]` number, or `strs[0]` held number + `strs[1]` current when `strs.length ≥ 2` |
| 11 / 12 | `U_RING_TIME` / `U_TALK_TIME` | `ints[0]` |
| 18 | `U_HFP` | `ints[0]` call audio in car (1) / on phone (0) — unverified |
| 29 | `U_PBAP_STATE` | `ints[0]`: 0 disconnected, 1 connected, 2 loading book, 3 call log connected, 4 loading call log |
| 22 / 35 | `U_BOOK` / `U_RECORD` | phonebook / call-log download progress |
| 5 / 24 / 25 | `U_PAIR_LIST` / `U_SEARCH_LIST` / `U_PAIR_RESULT` | paired list, discovery results, pairing result |
| 14 / 15 / 16 / 17 | `U_LOCAL_MAC_ADDR` / `U_LOCAL_NAME` / `U_PIN_CODE` / `U_BT_VER` | head unit's own Bluetooth identity |
| 31 | `U_BT_POWER_ON` | `ints[0]` |
| 34 | `U_A2DP_SINK_STATE` | `ints[0]`: 0 disconnected, 1 connecting, 2 connected, 3 disconnecting |
| 13 | `U_BTAV_PLAY_STATE` | `ints[0]`: 0 paused, 1 playing |
| 0 / 1 / 26 / 27 | `U_BTAV_ID3_TITLE` / `ARTIST` / `ALBUM` / `GENRE` | `strs[0]` |
| 2 / 28 / 3 / 4 | `U_BTAV_TOTAL_TIME` / `PLAY_TIME` / `PLAY_TRACK` / `TOTAL_TRACK` | `ints[0]` |
| 20 / 21 / 30 / 32 / 33 / 36 / 49 | autopick, autopick remaining, AVRCP 1.4 support, mic level, ring %, firmware update progress, mic mute | `ints[0]` |

### Commands (app → blink5), as `com.syu.bt` sends them

| What | Call |
|---|---|
| Dial a number | `cmd(7 /*C_DIAL*/, null, null, ["<number>"])` |
| Answer / call key | `cmd(6 /*C_KEY*/, [0 /*KEY_DIAL*/], null, null)` |
| Hang up / reject | `cmd(6, [1 /*KEY_HANG*/], null, null)` (also `C_PICKUP` 9, `C_HANG` 10, `C_REJECT_RING` 11) |
| DTMF digit | `cmd(6, [k])`: `KEY_N0`..`KEY_N9` = 2..11, `*` 13, `#` 14, `+` 12 |
| Redial | `cmd(8)` |
| Switch call audio car ↔ phone | `cmd(14 /*C_HFP*/, [2 /*SWITCH*/])` |
| Download phonebook / call log | `cmd(26)` / `cmd(35)` |
| Query paired list | `cmd(5, [0])` |
| Connect / forget a paired device | `cmd(24, null, null, ["<mac>"])` / `cmd(40, null, null, ["<mac>"])` |
| Connect an OBD dongle | `cmd(25, null, null, ["<mac>"])` (`C_CONNECT_OBD`) |
| Discovery on / off | `cmd(20, [1])` / `cmd(20, [0])` |
| Bluetooth power | `cmd(29, [0 or 1])` |
| Auto-answer | `cmd(28, [0 or 1])` |
| Music | prev 0, next 1, play/pause 2, pause 3, stop 4, play 27; A2DP link/cut `cmd(31, [1 or 0])` |
| Set name / PIN | `cmd(19, null, null, ["name"])` / `cmd(18, null, null, ["pin"])` |

`C_LINK_CUT` is 13, but com.syu.bt's link/cut wrappers pass an int I couldn't pin down
(1 = connect the last phone, 0 = disconnect is the likely reading).

Placing a call doesn't need the binder at all. `com.syu.bt.PhoneActivity` handles
`ACTION_CALL` / `ACTION_DIAL` / `ACTION_VIEW` on `tel:` URIs.

## Contacts and call history

When a phone connects, `com.syu.bt` downloads its phonebook and call log through blink5's PBAP,
then writes them into Android's own providers (`com.syu.bt.db.Bt_Info`):

- Contacts: `ContactsContract.RawContacts` plus `Data` rows (`StructuredName`, `Phone`), written
  with `applyBatch` on `com.android.contacts`. The phone's MAC (`Bt.sPhoneAddr`) goes into the
  rows, likely as the raw contact's account, which keeps phones apart. Unverified.
- Calls: `CallLog.Calls` rows with `number`, `type` and `date`, read back as `date DESC`.

So on FYT units, Revv's Phone screen can be filled from `ContactsContract` and `CallLog`, and
`U_PHONE_*` updates give the live phone name and connection state.

## Consequences for Revv

- Elsewhere, [`PhoneMonitor`](../app/src/main/java/com/vivekkaushik/revv/phone/PhoneMonitor.kt)
  looks for phones among Android's bonded devices and reads them over PBAP itself. FYT units have
  no Android bonds, so when `com.syu.ms/app.ToolkitService` resolves, it takes the phone from FYT
  instead:
  - [`FytBluetooth`](../app/src/main/java/com/vivekkaushik/revv/phone/FytBluetooth.kt): the binder
    client. It registers for updates 6, 7, 8, 9, 22, 29 and 35, dials with `C_DIAL`, hangs up with
    `C_KEY`/`KEY_HANG`, and asks for a fresh download with 26 + 35.
  - [`FytPhone.kt`](../app/src/main/java/com/vivekkaushik/revv/phone/FytPhone.kt): `FytLink` folds
    the updates into a link and call stage. `FytPhoneBook` reads `ContactsContract` (starred numbers
    are favourites) and `CallLog`, which Revv observes to refresh.
  - The Phone screens ask for `READ_CONTACTS` + `READ_CALL_LOG` instead of `BLUETOOTH_CONNECT`.
    Their pair buttons open com.syu.bt's pairing page, not Android's settings.
  - Google Play's build drops `READ_CALL_LOG` (Play restricts it), so there it shows contacts but
    no recent calls.
- [`ExternalBtPolicy`](../androidauto/src/main/java/com/andrerinas/openheadunit/aap/ExternalBtPolicy.kt)
  already flags this unit, because blink5 creates `/dev/rf_serial`. On this unit the radio isn't
  actually external, but the conclusion still holds: the vendor stack owns Bluetooth, so Android's
  RFCOMM isn't a path to rely on.
- OBD, Android Auto and CarPlay: see the sections below.

## Android's API through the relay

`libbluetooth_jni1.so` turns Android's Bluetooth calls into text commands for blink5, separated by a
`0xFF` byte. Discovery is `SD`, bonding `CB`/`RB`, RFCOMM `RFREG`/`RFCC`/`RFTX`/`RFCD`, and GATT
registration `GREGA`, with the GATT client calls as `CQ_GATTC_*`. blink5 answers with events: `IDS`,
`IFD`, `IBSC`, `IRFIB`, `IRFRX`, `IGREGA` and so on.

`AdapterService.useExternalBt()` is `bluetooth_type != 0 && !sys.fyt.systemobd`. It's false here
(`systemobd=true`), so Android's adapter runs for real through the relay, and com.syu.ms switches it
on and off with FYT's Bluetooth.

- **RFCOMM covers only a few UUIDs.** blink5 skips unknown ones ("unkonw uuid(%s), so skip it").
  - Client connects: SPP `00001101`, and very likely nothing else.
  - Listening: only Huawei HiCar's `0000fe35`/`0000fe36`.
  - So Android Auto's RFCOMM listener (`4de17a00…`) will never accept, and an iAP2 client connect
    (`00000000-deca-fade-deca-deafdecacafe`) is very likely refused.
- **BLE** scanning and the GATT client are relayed.
- **Idle when checked:** on 2026-11-07 the relay sockets had no connected clients.

## OBD (ELM327)

**Tested 2026-11-07: the relay route works while Android's Bluetooth is linked to blink5.**
- The link is up when `/proc/net/unix` shows a connected client on `@com.chengqian.localsocket`.
  After about 14 hours it was gone:
  - Android listed only the OBD dongle, and could neither connect it nor forget it.
  - Every `BluetoothSocket.connect()` threw an unchecked `RuntimeException: Bad file descriptor` out
    of `IBluetoothSocketManager.connectSocket`. Revv now catches it.
- A full restart of the head unit brought the link back. Then:
  - Forget worked in Android's settings.
  - The dongle paired from Android's Bluetooth settings.
  - Revv connected by the encrypted SPP socket and read live data (ELM327 v2.1).
- The link dropped as the engine cranked; Android then reported Bluetooth off for a while.
- Route 2 below is untried.

1. **Revv's current code, unchanged.**
   - [`BluetoothObdTransport`](../app/src/main/java/com/vivekkaushik/revv/obd/BluetoothObdTransport.kt)
     first calls `createRfcommSocketToServiceRecord(SPP)`. That is what the systemobd relay exists
     for, so it should reach blink5.
   - Its `createRfcommSocket(1)` fallback has no UUID and likely fails.
   - BLE adapters may work too, since scan and GATT are relayed.
   - Needs Android's adapter to report enabled, and the dongle in `bondedDevices`. Pairing from Android
     goes `CB` → blink5, which has an OBD pairing path with a PIN.
   - blink5 allows one OBD connection at a time.
2. **FYT's own route, if that fails.**
   - Send `cmd(25 /*C_CONNECT_OBD*/, null, null, [mac])` to BT module 2. blink5 connects to the
     dongle and remembers it as `obdaddr` in `/data/blink/bt_conf.ini`.
   - Then connect a `LocalSocket` to `/dev/socket/goc_spp` (filesystem namespace). blink5 attaches
     the client to the SPP channel ("spp_socket connect succeed").
   - Data is probably raw bytes, so `StreamObdTransport` could wrap it. Unverified, and it relies
     on permissive SELinux.

## Wireless Android Auto

FYT's Car Link 2.0 (`com.syu.carlink`) bundles Google's Android Auto receiver. When
`sys.fyt.bluetooth_type > 0` it skips Android's RFCOMM listener and uses its `ExtBluetooth` class.

`ExtBluetooth` opens `/dev/auto_serial` (→ `/dev/pts/1`) as plain file streams and speaks a line
protocol. Lines end `\n\r`; payloads are uppercase hex.

| Direction | Line | Meaning |
|---|---|---|
| → blink5 | `AT#QI` | Report the current phone (sent 200 ms after opening) |
| → blink5 | `AT#ZA<hex>` | RFCOMM data to the phone, ≤ 50 bytes per line |
| ← blink5 | `AA` | The phone opened Android Auto's RFCOMM channel |
| ← blink5 | `JH<12 hex>` | Phone's address |
| ← blink5 | `ZB<hex>` | RFCOMM data from the phone |
| ← blink5 | `SS` | Channel closed |

- blink5 answers the phone's Android Auto SDP search itself. It only passes the channel on while
  the phone is connected for calls ("because cur hfp disconnect so skip send AA").
**In Revv (working on this unit since 2026-11-07):**
- [`FytAutoSerial`](../androidauto/src/main/java/com/andrerinas/openheadunit/connection/FytAutoSerial.kt)
  speaks this protocol.
- `NativeAaHandshakeManager` runs its handshake over an `AaBluetoothLink`, which is either an RFCOMM
  socket or this channel. It takes the FYT route whenever `/dev/auto_serial` can be opened.
  - There's nothing to listen on or poke on that route.
  - `externalBtDiagnostic()` stays quiet there.
- The car's Bluetooth address comes from `AT#VE` → `DB`.
- Revv's wireless phone picker also lists the phone FYT's Bluetooth has connected.
- Verified on the unit: blink5 sent `AA`, the WPP exchange (Types 1, 2, 3) ran over `ZB`/`AT#ZA`,
  and the phone joined Wi-Fi and started Android Auto. Car Link's wireless Android Auto was off.
- Debug builds keep the Android Auto log in `Android/data/com.vivekkaushik.revv/files/diagnostics/`,
  readable over a terminal app's SSH when there's no adb.

## Wireless CarPlay

Car Link's `libcps_8581.so` (`cp_wireless`) opens `/dev/rf_serial` (→ `/dev/pts/2`) after boot. It
builds commands as `"AT#" + code + args`.

| Direction | Line | Meaning |
|---|---|---|
| → blink5 | `AT#QI` / `AT#VE` / `AT#QV` | Current phone / local address / version |
| → blink5 | `AT#SP<phone mac>` | Open the iAP2 RFCOMM channel to the phone |
| → blink5 | `AT#SG<hex>\r\n` | iAP2 data, ≤ 100 bytes per line |
| → blink5 | `AT#SH` / `AT#CD` | Close the channel / disconnect |
| ← blink5 | `IB` / `IA` | Phone linked / unlinked |
| ← blink5 | `JH<mac>` / `SA<name>` | Phone's address / name |
| ← blink5 | `US`, `UU<uuid>`…, `UE` | Phone's services. iAP2 support shows as `…DECAFADE…` |
| ← blink5 | `SV` / `SS` | iAP2 channel up / down |
| ← blink5 | `SI<hex>` | iAP2 data from the phone |

- **For Revv's CarPlay:** a `BlockingDuplexByteStream` over this pty, in place of
  `BluetoothRfcommDuplexStream`. `DiPlayBluetooth.localAddress` should report blink5's address
  (`AT#VE` → `DB`).
- What `AT#SP`'s second argument is remains unknown (probably empty).

**Car Link conflict:** Car Link runs all the time and very likely keeps both ptys open. Two
readers on one pty split its bytes, so Revv's Android Auto or CarPlay over Bluetooth needs Car
Link's wireless mode off. How to switch it off from outside isn't known yet.

## Rear camera

FYT's reversing camera comes through a video decoder chip, not a camera sensor. This unit
registers `tp2854_v1` and `tp2850_v1` with the camera service (`vendor.cam.sensor.info`), and Camera2
offers them as `BACK 0` and `BACK 1`. There are no `/dev/video*` nodes.

**Picture size:**
- FYT's camera code (`com.lsec.core.frame.camera.MyCamera`) sets the decoder's picture size before
  every open: `sys.fyt.cvbs.width`, `height`, `trim_width`, `trim_height`, and
  `scaler_trim.w`/`h`/`x`/`y`. On platform 6316 they were 1280×720 with no crop.
- They're unset after a reboot until FYT's reverse view runs once.
- Camera2 lists sizes up to 3840×2160 that the decoder can't deliver. On 2026-11-08, opening
  `BACK 0` at 1920×1080 streamed no frames and failed after 6 s with `ERROR_CAMERA_DEVICE`.
  Opening `BACK 1` at 1280×720 delivered frames in 0.25 s.
- So on FYT units Revv's Rear Cam asks for the `sys.fyt.cvbs` size, or 1280×720 when it's unset
  ([`FytCameraDecoder`](../app/src/main/java/com/vivekkaushik/revv/ui/hmi/FytCameraDecoder.kt)).
- Revv sets those properties itself before opening, when they're unset, the same way FYT does. Since
  Revv may be hiding FYT's view, nothing else would set them after a restart.
- Both cameras report `SENSOR_ORIENTATION` 90°, so Android turns the picture before the view gets
  it. The Rear Cam's ROTATE (270° on this unit) turns it back, and the view's scaling uses the
  swapped shape for sideways sensors.
- Rear Cam has a wide view (whole picture, uncropped, full width) and a MIRROR switch. FYT sets its
  own `sys.fyt.cvbs.mirror` for its view.
- Debug builds log every camera open to `Android/data/com.vivekkaushik.revv/files/logs/camera-*.log`.

**Who shows the camera in reverse:**
- `com.syu.us` shows FYT's full-screen view when the main module's `U_BACKCAR` (12) is non-zero.
  It skips its view while `persist.syu.backui3rd` or `persist.syu.360` is true.
- FYT's Factory settings set `backui3rd` for a third-party 360° app. FYT's settings app resets it
  to `false` whenever it starts on this platform, unless `com.sjs.vrbackcarapp` is installed.
- **Revv's "Rear Cam in reverse"** (Settings › Vehicle, FYT units only, off by default):
  - [`FytReverse`](../app/src/main/java/com/vivekkaushik/revv/fyt/FytReverse.kt) follows `U_BACKCAR`
    and opens the Rear Cam on reverse, going back to the previous screen after.
  - It sets `backui3rd` with `setprop` only while Revv is on screen and clears it when Revv
    leaves, so FYT's own view keeps working under other apps.
  - The `setprop` works only because SELinux is permissive here.
- **The risk:** if Revv dies on screen without restarting, `backui3rd` stays true until FYT's
  settings app next starts.
- Whether the rear camera has power outside reverse depends on its wiring. FYT's own Right Camera
  app showed black out of reverse.

## Security notes on this firmware

- `/dev/pts/0` (`/dev/BT_serial`) is `crw-rw-rw-` and SELinux is permissive, so any app can write
  commands straight to blink5, bypassing `com.syu.ms`. The same goes for `/dev/auto_serial`,
  `/dev/rf_serial` and `/dev/socket/goc_spp`.
- `/data/blink/` is `drwxrwxrwx`, and `sc_db.db` (pairing records, likely including link keys) is
  `rw-rw-rw-`.
- `com.syu.ms` accepts binder calls from any app, and `share.ShareProvider`
  (`com.syu.ms.provider`) is exported.

## Not yet verified

- Binding `com.syu.ms` from a third-party app and receiving updates. The manifest allows it.
  Whether `com.syu.ms` checks the caller's uid internally isn't known (its code is obfuscated).
- The exact contact tagging, the `C_LINK_CUT` argument, and `U_HFP` meaning.
- Whether the `systemobd` relay lets `BluetoothSocket` reach a classic ELM327, and why it was idle.
- Whether `goc_spp` carries raw bytes, and whether `C_CONNECT_OBD` must come first.
- Whether Car Link lets go of `/dev/auto_serial` and `/dev/rf_serial`, and blink5's line lengths
  for `ZB`/`SI` data.

## Reproducing

Termux sshd on the unit (`ssh -p 8022`) runs as an ordinary app uid: no root, `dumpsys` or
`settings`. The APKs are world-readable under `/oem/app/`. `dexdump -d` from the SDK
build-tools, plus `aapt2 dump xmltree`, are enough to read the interfaces and the static-final
constant values (`com.lsec.core.util.data.FinalBt`, `FinalRemoteToolkit`).
