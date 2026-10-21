/**
 * Companion：点击短信通知后弹出的简易回复界面。
 */
package com.dualphone.sms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dualphone.common.Msg
import com.dualphone.service.DualPhoneService
import com.dualphone.ui.theme.DualPhoneTheme

class SmsReplyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val to = intent.getStringExtra(EXTRA_TO) ?: ""
        val preview = intent.getStringExtra(EXTRA_BODY) ?: ""
        setContent {
            DualPhoneTheme {
                var text by remember { mutableStateOf("") }
                Column(Modifier.padding(16.dp)) {
                    Text("回复 $to")
                    Text(preview, modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("短信内容") },
                    )
                    Button(
                        onClick = {
                            if (text.isNotBlank() && to.isNotBlank()) {
                                DualPhoneService.enqueue(
                                    this@SmsReplyActivity,
                                    Msg.SmsOut(to = to, body = text),
                                )
                                finish()
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("发送") }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TO = "to"
        const val EXTRA_BODY = "body"
    }
}
