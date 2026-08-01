package com.coparently.app.presentation.pairing

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.coparently.app.presentation.theme.CoPlanlyTheme

/**
 * Full-screen QR scanner for pairing.
 *
 * Returns `RESULT_OK` with [EXTRA_CODE] set to a validated 6-character invite
 * code. Redeeming it is the caller's job — this activity only reads the camera.
 *
 * Stays an [AppCompatActivity] so per-app language selection keeps applying
 * (see the i18n rules in CLAUDE.md).
 */
class QRScannerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoPlanlyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QrScannerScreen(
                        onCodeScanned = { code ->
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        /** Result extra holding the scanned invite code. */
        const val EXTRA_CODE = "pairing_code"
    }
}
