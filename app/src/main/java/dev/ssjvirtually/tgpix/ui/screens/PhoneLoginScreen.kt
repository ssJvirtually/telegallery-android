package dev.ssjvirtually.tgpix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ssjvirtually.tgpix.ui.theme.TelePhotosTheme
import dev.ssjvirtually.tgpix.telegram.AuthManager
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.util.Log
import org.drinkless.tdlib.TdApi

@Composable
fun PhoneLoginScreen() {
    var phone by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    val countryCodes = listOf(
        "+91" to "India (+91)",
        "+1" to "USA/Canada (+1)",
        "+44" to "UK (+44)",
        "+61" to "Australia (+61)",
        "+49" to "Germany (+49)",
        "+33" to "France (+33)",
        "+65" to "Singapore (+65)",
        "+971" to "UAE (+971)"
    )
    var selectedCode by remember { mutableStateOf("+91") }
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().background(TelePhotosTheme.Background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = TelePhotosTheme.Surface),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Telegram paper airplane icon styled with a Google Photos color ring!
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            Brush.sweepGradient(
                                colors = listOf(
                                    TelePhotosTheme.GoogleBlue,
                                    TelePhotosTheme.GoogleGreen,
                                    TelePhotosTheme.GoogleYellow,
                                    TelePhotosTheme.GoogleRed,
                                    TelePhotosTheme.GoogleBlue
                                )
                             ),
                             shape = CircleShape
                        )
                        .padding(3.dp)
                        .background(TelePhotosTheme.Surface, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send, // Elegant paper airplane
                        contentDescription = null,
                        tint = TelePhotosTheme.AccentBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row {
                    Text(
                        text = "TG",
                        color = TelePhotosTheme.AccentBlue,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Pix",
                        color = TelePhotosTheme.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter your phone number to authorize storage backup via Telegram",
                    color = TelePhotosTheme.TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.35f)
                            .clickable { expanded = true }
                    ) {
                        OutlinedTextField(
                            value = selectedCode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Code", color = TelePhotosTheme.TextSecondary) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TelePhotosTheme.AccentBlue,
                                unfocusedBorderColor = TelePhotosTheme.SurfaceVariant,
                                focusedLabelColor = TelePhotosTheme.AccentBlue,
                                unfocusedLabelColor = TelePhotosTheme.TextSecondary,
                                focusedTextColor = TelePhotosTheme.TextPrimary,
                                unfocusedTextColor = TelePhotosTheme.TextPrimary
                            ),
                            trailingIcon = {
                                IconButton(onClick = { expanded = true }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Select country code",
                                        tint = TelePhotosTheme.TextSecondary
                                    )
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(TelePhotosTheme.Surface)
                        ) {
                            countryCodes.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = TelePhotosTheme.TextPrimary) },
                                    onClick = {
                                        selectedCode = code
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number", color = TelePhotosTheme.TextSecondary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.weight(0.65f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TelePhotosTheme.AccentBlue,
                            unfocusedBorderColor = TelePhotosTheme.SurfaceVariant,
                            focusedLabelColor = TelePhotosTheme.AccentBlue,
                            unfocusedLabelColor = TelePhotosTheme.TextSecondary,
                            focusedTextColor = TelePhotosTheme.TextPrimary,
                            unfocusedTextColor = TelePhotosTheme.TextPrimary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = {
                        isLoading = true
                        val fullPhoneNumber = if (phone.trim().startsWith("+")) phone.trim() else selectedCode + phone.trim()
                        Log.i("TGPix", "Submitting phone number: $fullPhoneNumber")
                        AuthManager.sendPhone(fullPhoneNumber) { result ->
                            isLoading = false
                            if (result is TdApi.Error) {
                                Log.e("TGPix", "Failed to send phone number: [${result.code}] ${result.message}")
                                // We are already on the Main/UI thread inside the callback context of TDLib client 
                                // but we wrap in a launch or runOnUiThread if necessary, standard handler post works too.
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    Toast.makeText(context, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Log.i("TGPix", "Phone number submitted successfully, result: ${result::class.java.simpleName}")
                            }
                        }
                    },
                    enabled = phone.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    } else {
                        Text("Send OTP Code", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
