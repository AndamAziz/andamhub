package uk.andam.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.andam.app.BuildConfig
import uk.andam.app.R

/**
 * Welcome screen shown while the app starts and loads your account, providers and playlists.
 * The scorpion emblem rises out of an ember glow with a spinning gold ring around it, then the
 * greeting and a slim loading bar fade in. It fades away into the app once everything is ready.
 */
@Composable
fun WelcomeScreen(email: String?) {
    val tv = LocalTv.current
    val logo = if (tv) 200 else 176

    // Entrance: emblem first, then the text, then the loading bar.
    val emblem = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val footer = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { emblem.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        launch { delay(350); text.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        launch { delay(650); footer.animateTo(1f, tween(600)) }
    }

    val loop = rememberInfiniteTransition(label = "welcome")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "spin")
    val pulse by loop.animateFloat(
        0.88f, 1.08f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val bar by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "bar")

    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(7000); slow = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .drawBehind {
                // Ember glow behind the emblem and a faint gold glow rising from the bottom.
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x59FF3B4E), Color(0x14FF3B4E), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.40f),
                        radius = size.minDimension * 0.85f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x1FFFD27A), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 1.08f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
            },
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .size((logo + 64).dp)
                    .graphicsLayer {
                        alpha = emblem.value
                        val s = 0.78f + 0.22f * emblem.value
                        scaleX = s; scaleY = s
                        translationY = (1f - emblem.value) * 40.dp.toPx()
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Breathing halo.
                Box(
                    Modifier
                        .size((logo + 60).dp)
                        .graphicsLayer { scaleX = pulse; scaleY = pulse }
                        .background(
                            Brush.radialGradient(listOf(Color(0x70FF3B4E), Color(0x22FF3B4E), Color.Transparent)),
                            CircleShape,
                        ),
                )
                // Spinning gold-to-ember ring.
                Canvas(Modifier.size((logo + 24).dp).graphicsLayer { rotationZ = spin }) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color.Transparent, Color(0x33FF3B4E), C.Ember, C.Gold, Color.Transparent),
                        ),
                        startAngle = 0f, sweepAngle = 320f, useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                Image(
                    painter = painterResource(R.drawable.andam_splash),
                    contentDescription = "Andam",
                    modifier = Modifier.size(logo.dp),
                )
            }

            Spacer(Modifier.height(26.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = text.value
                    translationY = (1f - text.value) * 16.dp.toPx()
                },
            ) {
                Text(
                    if (email.isNullOrBlank()) "Welcome to Andam" else "Welcome back",
                    color = C.Text, fontSize = if (tv) 28.sp else 24.sp, fontWeight = FontWeight.Bold,
                )
                if (!email.isNullOrBlank()) Text(email, color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("LIVE TV", "MOVIES", "SERIES").forEachIndexed { i, label ->
                        if (i > 0) Box(Modifier.size(4.dp).clip(CircleShape).background(C.Ember))
                        Text(label, color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = footer.value },
            ) {
                // Slim loading bar with a gliding ember highlight.
                Box(Modifier.width(150.dp).height(3.dp).clip(RoundedCornerShape(50)).background(C.Surface2)) {
                    Box(
                        Modifier
                            .width(60.dp)
                            .fillMaxHeight()
                            .graphicsLayer { translationX = -60.dp.toPx() + bar * 210.dp.toPx() }
                            .background(
                                Brush.horizontalGradient(listOf(Color.Transparent, C.Ember, C.Gold, Color.Transparent)),
                                RoundedCornerShape(50),
                            ),
                    )
                }
                Text(
                    if (slow) "Still connecting…" else "Getting your channels ready",
                    color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "v${BuildConfig.VERSION_NAME}", color = C.Faint, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
