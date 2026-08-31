package app.anothermorsetrainer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/**
 * Finds and opens a Bluetooth LE MIDI key (e.g. a BLE-flashed Vail Adapter).
 *
 * Android never opens BLE-MIDI devices by itself: something must scan for the
 * BLE-MIDI service and call [MidiManager.openBluetoothDevice]. Once opened here,
 * the device shows up through the normal [MidiManager] device callbacks, so the
 * existing [MidiKeyInput]/[MidiKeyOutput] paths attach to it with no changes —
 * this class just owns the scan and keeps the opened link alive.
 *
 * The caller is responsible for holding the runtime permissions in
 * [requiredPermissions] before calling [scanAndOpen].
 */
class BleMidi(private val context: Context) {

    companion object {
        /** The Bluetooth-SIG MIDI-over-BLE service UUID every BLE-MIDI key advertises. */
        private val MIDI_SERVICE = ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        private const val SCAN_TIMEOUT_MS = 10_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val opened = mutableListOf<MidiDevice>()
    private var onStatus: ((String) -> Unit)? = null

    val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)

    /** The runtime permissions a scan needs on this OS version. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * Scan for BLE-MIDI devices for up to ten seconds and open each one found.
     * Progress lands in [onStatus] (main thread). Permissions must be granted
     * first; a missing permission or disabled Bluetooth reports and gives up.
     */
    @SuppressLint("MissingPermission")   // guarded by the caller via requiredPermissions()
    fun scanAndOpen(onStatus: (String) -> Unit) {
        this.onStatus = onStatus
        if (!isSupported) { onStatus("Bluetooth LE MIDI isn't supported on this device."); return }
        val bt = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bt?.adapter
        if (adapter == null || !adapter.isEnabled) { onStatus("Turn Bluetooth on first."); return }
        val midi = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        if (midi == null) { onStatus("MIDI unavailable."); return }
        val le = adapter.bluetoothLeScanner
        if (le == null) { onStatus("Bluetooth scanning unavailable."); return }

        stopScan()
        scanner = le
        val found = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (!found.add(device.address)) return
                val label = try { device.name } catch (_: SecurityException) { null } ?: device.address
                onStatus("Found $label — connecting…")
                midi.openBluetoothDevice(device, { midiDevice ->
                    if (midiDevice != null) {
                        opened.add(midiDevice)
                        main.post { this@BleMidi.onStatus?.invoke("Connected: $label") }
                    } else {
                        main.post { this@BleMidi.onStatus?.invoke("Couldn't open $label.") }
                    }
                }, main)
            }

            override fun onScanFailed(errorCode: Int) {
                main.post { this@BleMidi.onStatus?.invoke("Scan failed ($errorCode).") }
            }
        }
        scanCallback = cb
        onStatus("Scanning for a Bluetooth key…")
        le.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(MIDI_SERVICE).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb
        )
        main.postDelayed({
            if (scanCallback === cb) {
                stopScan()
                if (found.isEmpty()) this.onStatus?.invoke("No Bluetooth key found. Is it advertising?")
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try { scanner?.stopScan(cb) } catch (_: Exception) {}
    }

    /** Stop scanning and close the BLE links (disconnecting the key). */
    fun release() {
        stopScan()
        opened.forEach { try { it.close() } catch (_: Exception) {} }
        opened.clear()
        onStatus = null
    }
}
