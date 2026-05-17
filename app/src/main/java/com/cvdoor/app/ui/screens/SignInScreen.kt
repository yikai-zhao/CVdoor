package com.cvdoor.app.ui.screens

import android.app.Activity
import android.content.pm.ApplicationInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cvdoor.app.auth.AuthDataStore
import com.cvdoor.app.ui.components.PrimaryGradientButton
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun SignInScreen(onSignedIn: () -> Unit) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth  = remember { AuthDataStore(ctx) }
    var err by remember { mutableStateOf<String?>(null) }

    val isDebug = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // 請求 email + idToken（用於以後發給後端換 session）
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(ctx.getString(com.cvdoor.app.R.string.default_web_client_id))
            .build()
    }
    val client = remember { GoogleSignIn.getClient(ctx, gso) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        try {
            if (res.resultCode != Activity.RESULT_OK) {
                err = "Google sign-in was cancelled or failed (code=${res.resultCode})."
                return@rememberLauncherForActivityResult
            }
            val task = GoogleSignIn.getSignedInAccountFromIntent(res.data)
            val acc: GoogleSignInAccount = task.getResult(ApiException::class.java)

            val uid  = acc.id ?: acc.email ?: "local-user"
            val name = acc.displayName ?: acc.email ?: "User"
            val idToken = acc.idToken // 以後可以發給你的後端換會話

            scope.launch {
                // 現在先用你本地的 AuthDataStore（和現有 VM/Repo 對齊）
                auth.set(uid, name)
                onSignedIn()
            }
        } catch (e: Exception) {
            err = if (e is ApiException)
                "Google sign-in failed (status=${e.statusCode})."
            else e.message ?: "Unknown error."
        }
    }

    Box(Modifier.fillMaxSize()) {
        NocturneAuroraBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(36.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "CVDoor",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    style = LocalTextStyle.current.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.6f),
                            offset = Offset(0f, 2f),
                            blurRadius = 8f
                        )
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text("Model 1.0", color = Color(0xFF9FB1FF), fontSize = 14.sp)

                Spacer(Modifier.height(18.dp))
                Text(
                    "ATS-tuned resume optimizer that boosts match rate with actionable edits.",
                    color = Color(0xFFCED6F6),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "• Smart keyword alignment  • Title & formatting fixes  • Clarity & readability",
                    color = Color(0xFF9FB1FF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .alpha(0.98f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Sign in to sync your history and credits",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))

                PrimaryGradientButton(
                    text = "Continue with Google",
                    modifier = Modifier.fillMaxWidth()
                ) { launcher.launch(client.signInIntent) }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val gid = "guest-${UUID.randomUUID().toString().take(8)}"
                            auth.set(gid, "Guest")
                            onSignedIn()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0x1AFFFFFF),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) { Text("Continue as Guest") }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Note: purchases & cloud sync require Google sign-in.",
                    color = Color(0xFFB7C1E0),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                if (isDebug) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                auth.set("dev-local-user", "Developer")
                                onSignedIn()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0x1AFFFFFF),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) { Text("Developer local sign-in (skip Google)") }
                }

                err?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 18.dp)
            ) {
                Text(
                    "By continuing you agree to our Terms and Privacy Policy.",
                    color = Color(0xFFB7C1E0),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/* ======================= Animated dark background ======================= */

@Composable
private fun NocturneAuroraBackground() {
    val inf = rememberInfiniteTransition(label = "nocturne")

    val a by inf.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing)),
        label = "a"
    )
    val b by inf.animateFloat(
        initialValue = PI.toFloat() / 2f,
        targetValue = 2f * PI.toFloat() + PI.toFloat() / 2f,
        animationSpec = infiniteRepeatable(tween(34_000, easing = LinearEasing)),
        label = "b"
    )
    val sweep by inf.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            tween(5_000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "sweep"
    )

    val stars = remember {
        List(24) {
            Star(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                r = Random.nextFloat() * 1.3f + 0.5f,
                alpha = Random.nextFloat() * 0.7f + 0.2f
            )
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val long = maxOf(w, h)

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFF060912),
                0.5f to Color(0xFF0C1230),
                1f to Color(0xFF0A0F23)
            )
        )

        val c1 = Offset(w * (0.28f + 0.05f * sin(a)), h * (0.30f + 0.05f * cos(a)))
        val c2 = Offset(w * (0.72f + 0.04f * cos(b)), h * (0.38f + 0.04f * sin(b)))
        val c3 = Offset(w * (0.50f + 0.04f * sin(a / 1.3f)), h * (0.78f + 0.03f * cos(b / 1.6f)))

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x443B82F6), Color.Transparent),
                center = c1, radius = long * 0.9f
            ),
            radius = long * 0.9f, center = c1
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x448B5CF6), Color.Transparent),
                center = c2, radius = long * 0.85f
            ),
            radius = long * 0.85f, center = c2
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x3306B6D4), Color.Transparent),
                center = c3, radius = long * 1.0f
            ),
            radius = long * 1.0f, center = c3
        )

        val start = Offset(w * (sweep - 0.3f), h * (sweep - 0.5f))
        val end   = Offset(w * (sweep + 0.6f), h * (sweep + 0.8f))
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.05f),
                    Color.Transparent
                ),
                start = start, end = end
            )
        )

        stars.forEach {
            drawCircle(
                color = Color.White.copy(alpha = it.alpha),
                radius = it.r,
                center = Offset(it.x * w, it.y * h)
            )
        }

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color(0xAA000000)
            )
        )
    }
}

private data class Star(val x: Float, val y: Float, val r: Float, val alpha: Float)
