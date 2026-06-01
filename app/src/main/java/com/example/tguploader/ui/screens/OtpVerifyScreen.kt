package com.example.tguploader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
import com.example.tguploader.ui.theme.TelePhotosTheme
import com.example.tguploader.telegram.AuthManager

@Composable
fun OtpVerifyScreen() {
    var otp by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

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
                // Key / lock verification icon styled with a Google Photos color ring!
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
                        imageVector = Icons.Default.Lock, // Elegant key lock
                        contentDescription = null,
                        tint = TelePhotosTheme.AccentBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Verify Code",
                    color = TelePhotosTheme.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "An authorization code was sent to your official Telegram client.",
                    color = TelePhotosTheme.TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it },
                    label = { Text("Code", color = TelePhotosTheme.TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TelePhotosTheme.AccentBlue,
                        unfocusedBorderColor = TelePhotosTheme.SurfaceVariant,
                        focusedLabelColor = TelePhotosTheme.AccentBlue,
                        unfocusedLabelColor = TelePhotosTheme.TextSecondary,
                        focusedTextColor = TelePhotosTheme.TextPrimary,
                        unfocusedTextColor = TelePhotosTheme.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = {
                        isLoading = true
                        AuthManager.verifyOtp(otp) {
                            isLoading = false
                        }
                    },
                    enabled = otp.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = TelePhotosTheme.AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    } else {
                        Text("Log In", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
