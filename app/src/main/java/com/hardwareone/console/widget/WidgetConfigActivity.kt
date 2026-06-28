package com.hardwareone.console.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hardwareone.console.ui.theme.HardwareOneTheme

/**
 * Per-widget configuration: choose which optional fields a placed widget shows. Launched by the
 * system when the widget is added (and from the host's "reconfigure" action). The status dot and
 * device name are always shown; everything else in [HardwareOneWidgetProvider.FIELDS] is opt-in.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the user backs out, the widget must NOT be placed.
        setResult(Activity.RESULT_CANCELED)

        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            HardwareOneTheme(darkTheme = isSystemInDarkTheme()) {
                ConfigScreen(widgetId) { finishWithResult(widgetId) }
            }
        }
    }

    private fun finishWithResult(widgetId: Int) {
        HardwareOneWidgetProvider.render(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@Composable
private fun ConfigScreen(widgetId: Int, onSave: () -> Unit) {
    val context = LocalContext.current
    val checked = remember {
        mutableStateMapOf<String, Boolean>().apply {
            HardwareOneWidgetProvider.FIELDS.forEach {
                put(it.key, HardwareOneWidgetProvider.fieldEnabled(context, widgetId, it))
            }
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Widget fields", style = MaterialTheme.typography.headlineSmall)
            Text(
                "The status dot and device name are always shown. Pick what else to display — " +
                    "keep it to non-sensitive info if you'll use it on the lock screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            HardwareOneWidgetProvider.FIELDS.forEach { field ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(field.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = checked[field.key] == true,
                        onCheckedChange = { checked[field.key] = it },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    HardwareOneWidgetProvider.prefs(context).edit().apply {
                        HardwareOneWidgetProvider.FIELDS.forEach {
                            putBoolean(HardwareOneWidgetProvider.keyField(widgetId, it.key), checked[it.key] == true)
                        }
                        apply()
                    }
                    onSave()
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Add widget")
            }
        }
    }
}
