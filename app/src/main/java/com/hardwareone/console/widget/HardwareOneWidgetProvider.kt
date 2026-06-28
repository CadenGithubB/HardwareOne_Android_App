package com.hardwareone.console.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.hardwareone.console.MainActivity
import com.hardwareone.console.R

/**
 * Home-screen / lock-screen widget: a connection dot, the device name, and a configurable set of
 * fields (battery, voltage, firmware, last-updated time).
 *
 * A widget can't hold a BLE connection itself (it's just [RemoteViews]), so it renders the LAST-KNOWN
 * snapshot the app persisted via [push]. While the app runs it pushes live updates; when the app is
 * gone the widget keeps the last snapshot — hence the "updated HH:mm" field, so a stale glance is
 * obvious. Tapping opens the app and, if currently disconnected, asks it to reconnect to the last
 * device. Which optional fields show is chosen PER WIDGET in [WidgetConfigActivity]; a short widget
 * collapses to a single line.
 */
class HardwareOneWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        // Drop each removed widget's per-instance config so prefs don't accumulate stale ids.
        prefs(context).edit().apply {
            for (id in ids) FIELDS.forEach { remove(keyField(id, it.key)) }
            apply()
        }
    }

    companion object {
        private const val PREFS = "hw1_widget"

        // A snapshot older than this is "stale": the app likely died (or the link dropped) without a
        // clean disconnect push, so we can't trust a frozen "connected" reading. The render re-runs on
        // the widget's update period, so it greys out on its own without the app — and the in-app
        // heartbeat re-stamps the time while connected so a live-but-idle link never trips this.
        private const val STALE_MS = 6 * 60 * 1000L

        /** Optional, per-widget-toggleable fields. `key` is the pref suffix; `default` its initial state. */
        data class Field(val key: String, val label: String, val default: Boolean)
        val FIELDS = listOf(
            Field("battery", "Battery", true),
            Field("updated", "Updated time", true),
            Field("voltage", "Voltage", false),
            Field("firmware", "Firmware version", false),
        )

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun keyField(id: Int, field: String) = "cfg_${id}_$field"
        fun fieldEnabled(context: Context, id: Int, f: Field) =
            prefs(context).getBoolean(keyField(id, f.key), f.default)

        /**
         * Persist the latest device snapshot and refresh every placed widget. Safe to call from any
         * thread with an application [context]. [name]/[address]/[firmware] are only overwritten when
         * non-empty, so a disconnect keeps the last device's identity (and its reconnect target).
         */
        fun push(
            context: Context,
            connected: Boolean,
            name: String?,
            address: String?,
            batteryPct: Int,
            charging: Boolean,
            hasBattery: Boolean,
            voltage: Double,
            firmware: String?,
            updatedLabel: String,
        ) {
            prefs(context).edit().apply {
                putBoolean("connected", connected)
                putInt("battery", batteryPct)
                putBoolean("charging", charging)
                putBoolean("hasBattery", hasBattery)
                putFloat("voltage", voltage.toFloat())
                putString("updated", updatedLabel)
                putLong("updatedMs", System.currentTimeMillis()) // for the staleness check in render()
                if (!name.isNullOrEmpty()) putString("name", name)
                if (!address.isNullOrEmpty()) putString("address", address)
                if (!firmware.isNullOrEmpty()) putString("firmware", firmware)
                apply()
            }
            val manager = AppWidgetManager.getInstance(context)
            for (id in manager.getAppWidgetIds(ComponentName(context, HardwareOneWidgetProvider::class.java))) {
                render(context, manager, id)
            }
        }

        fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val p = prefs(context)
            val connected = p.getBoolean("connected", false)
            val name = p.getString("name", null)?.takeIf { it.isNotEmpty() } ?: "HardwareOne"
            val battery = p.getInt("battery", -1)
            val hasBattery = p.getBoolean("hasBattery", false)
            val charging = p.getBoolean("charging", false)
            val voltage = p.getFloat("voltage", 0f)
            val firmware = p.getString("firmware", null)?.takeIf { it.isNotEmpty() }
            val updated = p.getString("updated", null)?.takeIf { it.isNotEmpty() }
            val address = p.getString("address", null)?.takeIf { it.isNotEmpty() }
            val updatedMs = p.getLong("updatedMs", 0L)
            val stale = updatedMs > 0L && System.currentTimeMillis() - updatedMs > STALE_MS
            // A frozen "connected" can't be trusted once the snapshot is stale (the app likely died
            // without a clean disconnect). For a fresh snapshot we trust the app's last push; when it's
            // stale we confirm against the OS's CURRENT GATT view — a cheap local query, no BLE I/O —
            // which reads disconnected once the app process is gone (the link dies with it).
            val reallyConnected = if (!stale) connected else (address?.let { isGattConnected(context, it) } ?: false)
            fun on(key: String) = fieldEnabled(context, id, FIELDS.first { it.key == key })

            val views = RemoteViews(context.packageName, R.layout.widget_hardwareone)
            // ImageView.setColorFilter(int) is @RemotableViewMethod — tints the dot while keeping its
            // round shape (setBackgroundColor would square it off).
            views.setInt(
                R.id.widget_dot, "setColorFilter",
                context.getColor(if (reallyConnected) R.color.widget_connected else R.color.widget_disconnected),
            )
            views.setTextViewText(R.id.widget_name, name)

            // Line-1 status word. The green dot conveys "connected", so when battery is off we blank it.
            val status = when {
                !reallyConnected -> "offline"
                on("battery") && hasBattery && battery >= 0 -> "$battery%" + if (charging) " charging" else ""
                on("battery") -> "connected"
                else -> ""
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setViewVisibility(R.id.widget_status, if (status.isEmpty()) View.GONE else View.VISIBLE)

            // Line-2 detail fields. Connected-only fields drop when offline; "updated" stays so you can
            // see how old the reading is.
            val details = buildList {
                if (reallyConnected && on("voltage") && voltage > 0f) add(String.format("%.2fV", voltage))
                if (reallyConnected && on("firmware") && firmware != null) add("fw $firmware")
                if (on("updated") && updated != null) add("updated $updated")
            }.joinToString("  ·  ")
            views.setTextViewText(R.id.widget_details, details)
            views.setViewVisibility(R.id.widget_details, if (details.isEmpty()) View.GONE else View.VISIBLE)

            // Tap: open the app; if currently disconnected and we know the last device, ask it to
            // reconnect (the app does the BLE — a widget can't, and from the lock screen this prompts
            // unlock first, which is the right security behavior). FLAG_UPDATE_CURRENT so the extra
            // tracks the current connection state; a per-id request code keeps widgets distinct.
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!reallyConnected && address != null) open.putExtra(MainActivity.EXTRA_CONNECT_ADDRESS, address)
            val pi = PendingIntent.getActivity(
                context, id, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            manager.updateAppWidget(id, views)
        }

        /**
         * The Bluetooth stack's CURRENT view of the GATT link to [address] — a cheap binder query, no
         * scan/connect/I-O. A BLE GATT connection is owned by the app process and is torn down when
         * that process dies, so this reads disconnected once the app is gone, which is exactly the
         * real-time "is it actually connected" signal. Returns null if it can't be determined (no
         * permission / no adapter / bad address) so the caller falls back to the last-known state.
         */
        private fun isGattConnected(context: Context, address: String): Boolean? {
            val needsRuntimePerm = android.os.Build.VERSION.SDK_INT >= 31
            if (needsRuntimePerm &&
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java) ?: return null
            val device = try {
                manager.adapter?.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            return try {
                manager.getConnectionState(device, android.bluetooth.BluetoothProfile.GATT) ==
                    android.bluetooth.BluetoothProfile.STATE_CONNECTED
            } catch (_: SecurityException) {
                null
            }
        }
    }
}
