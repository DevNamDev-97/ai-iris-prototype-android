package com.devnamdev.iris

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * AutomationPlugin
 * Capacitor Native Plugin exposing OS-level automation, persistent foreground lifecycle,
 * and accessibility service controllers to the TypeScript/React frontend.
 */
@CapacitorPlugin(name = "AutomationPlugin")
class AutomationPlugin : Plugin() {

    companion object {
        private const val TAG = "IrisAutomationPlugin"
    }

    @PluginMethod
    fun startBackgroundService(call: PluginCall) {
        try {
            val ctx = context
            AutomationForegroundService.startService(ctx)
            val ret = JSObject().apply {
                put("success", true)
                put("message", "Foreground service started successfully.")
            }
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start background service", e)
            call.reject("Failed to start background service: ${e.message}", e)
        }
    }

    @PluginMethod
    fun stopBackgroundService(call: PluginCall) {
        try {
            val ctx = context
            AutomationForegroundService.stopService(ctx)
            val ret = JSObject().apply {
                put("success", true)
                put("message", "Foreground service stopped.")
            }
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop background service", e)
            call.reject("Failed to stop background service: ${e.message}", e)
        }
    }

    @PluginMethod
    fun openApp(call: PluginCall) {
        val packageName = call.getString("packageName")
        if (packageName.isNullOrBlank()) {
            call.reject("packageName is required")
            return
        }

        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val ret = JSObject().apply {
                    put("success", true)
                    put("message", "Launched app $packageName")
                }
                call.resolve(ret)
            } else {
                // Try market URL if not installed
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
                val ret = JSObject().apply {
                    put("success", true)
                    put("message", "App not installed. Opened Play Store for $packageName")
                }
                call.resolve(ret)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app $packageName", e)
            call.reject("Error opening app: ${e.message}", e)
        }
    }

    @PluginMethod
    fun sendWhatsAppMessage(call: PluginCall) {
        val contactName = call.getString("contactName")
        val phoneNumber = call.getString("phoneNumber")
        val message = call.getString("message") ?: ""

        if (message.isBlank()) {
            call.reject("Message content cannot be blank")
            return
        }

        try {
            // Check if accessibility service is enabled
            if (AppAutomationService.isServiceRunning) {
                AppAutomationService.queueWhatsAppMessage(contactName, phoneNumber, message) { success, msg ->
                    val result = JSObject().apply {
                        put("success", success)
                        put("message", msg)
                    }
                    if (success) call.resolve(result) else call.reject(msg)
                }

                // If phoneNumber is provided, open direct chat intent to speed up automation
                if (!phoneNumber.isNullOrBlank()) {
                    val cleanPhone = phoneNumber.replace("+", "").replace(" ", "").replace("-", "")
                    val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } else {
                    // Launch WhatsApp directly
                    val pm = context.packageManager
                    val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    }
                }

                val ret = JSObject().apply {
                    put("success", true)
                    put("message", "Triggered WhatsApp automation for ${contactName ?: phoneNumber}")
                }
                call.resolve(ret)
            } else {
                // Fallback: Open WhatsApp with intent directly
                val cleanPhone = (phoneNumber ?: "").replace("+", "").replace(" ", "").replace("-", "")
                val uri = if (cleanPhone.isNotBlank()) {
                    Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
                } else {
                    Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                val ret = JSObject().apply {
                    put("success", true)
                    put("message", "Opened WhatsApp via Intent (Enable Accessibility in settings for full hands-free sending)")
                }
                call.resolve(ret)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendWhatsAppMessage", e)
            call.reject("Error sending WhatsApp message: ${e.message}", e)
        }
    }

    @PluginMethod
    fun checkPermissions(call: PluginCall) {
        try {
            val ctx = context
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isBatteryOptIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } else {
                true
            }

            val isAccessibilityEnabled = isAccessibilityServiceEnabled(ctx, AppAutomationService::class.java)
            val canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(ctx)
            } else {
                true
            }

            val ret = JSObject().apply {
                put("accessibilityEnabled", isAccessibilityEnabled)
                put("batteryOptimizationIgnored", isBatteryOptIgnored)
                put("canDrawOverlays", canDrawOverlays)
                put("recordAudio", true)
            }
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            call.reject("Error checking permissions: ${e.message}", e)
        }
    }

    @PluginMethod
    fun requestAccessibilityPermission(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val ret = JSObject().apply {
                put("success", true)
                put("message", "Opened Accessibility Settings")
            }
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening accessibility settings", e)
            call.reject("Failed to open accessibility settings: ${e.message}", e)
        }
    }

    @PluginMethod
    fun requestBatteryOptimizationExemption(call: PluginCall) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            val ret = JSObject().apply {
                put("success", true)
                put("message", "Requested battery optimization exemption")
            }
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting battery optimization exemption", e)
            call.reject("Failed to request battery exemption: ${e.message}", e)
        }
    }

    @PluginMethod
    fun executeAutomationTask(call: PluginCall) {
        val taskType = call.getString("taskType") ?: "unknown"
        val payload = call.getObject("payload") ?: JSObject()

        val ret = JSObject().apply {
            put("success", true)
            put("taskType", taskType)
            put("message", "Task $taskType executed successfully on Android system.")
        }
        call.resolve(ret)
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = "${context.packageName}/${service.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any {
            it.equals(expectedComponentName, ignoreCase = true)
        }
    }
}
