/**
 * Companion 来电全屏界面：显示号码，接听 / 挂断指令发回 Host。
 */
package com.dualphone.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualphone.common.Msg
import com.dualphone.service.DualPhoneService
import com.dualphone.ui.theme.DualPhoneTheme

class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: "未知"
        val name = intent.getStringExtra(EXTRA_NAME)
        setContent {
            DualPhoneTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("来电", style = MaterialTheme.typography.titleMedium)
                    Text(name ?: number, fontSize = 32.sp, modifier = Modifier.padding(top = 16.dp))
                    if (name != null) {
                        Text(number, modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(
                        Modifier.padding(top = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Button(
                            onClick = {
                                DualPhoneService.enqueue(this@IncomingCallActivity, Msg.CallHangup())
                                finish()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        ) { Text("挂断") }
                        Button(
                            onClick = {
                                DualPhoneService.enqueue(this@IncomingCallActivity, Msg.CallAnswer())
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
                        ) { Text("接听") }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
    }
}
