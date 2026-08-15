package com.blankmediator.smsfromcsv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

class MmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val row = intent.getIntExtra(EXTRA_ROW, -1)
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        val result = resultCode
        val description = when (result) {
            Activity.RESULT_OK -> "sent"
            SmsManager.MMS_ERROR_UNSPECIFIED -> "unspecified MMS error"
            SmsManager.MMS_ERROR_INVALID_APN -> "invalid APN"
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "unable to connect to MMS service"
            SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS HTTP failure"
            SmsManager.MMS_ERROR_IO_ERROR -> "MMS I/O error"
            SmsManager.MMS_ERROR_RETRY -> "carrier requested retry"
            SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS configuration error"
            else -> "MMS error $result"
        }
        Log.i(TAG, "CSV row $row ($phone): $description")
    }

    companion object {
        const val EXTRA_ROW = "row"
        const val EXTRA_PHONE = "phone"
        private const val TAG = "SMSfromCSV-MMS"
    }
}
