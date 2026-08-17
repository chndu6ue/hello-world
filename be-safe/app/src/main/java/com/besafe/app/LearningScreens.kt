package com.besafe.app

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LessonScreen(
    hindi: Boolean,
    alreadyDone: Boolean,
    back: () -> Unit,
    onComplete: () -> Unit
) {
    fun tr(hi: String, en: String) = if (hindi) hi else en
    var answer by remember { mutableStateOf<Boolean?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item { TopBar(tr("आज का सुरक्षा पाठ", "Today's Safety Skill"), back) }
        item {
            Row(
                modifier = Modifier.padding(18.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.permission_chor),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp)
                )
                Spacer(Modifier.width(12.dp))
                Surface(color = Color(0xFFFFEFEF), shape = RoundedCornerShape(20.dp)) {
                    Text(
                        text = tr(
                            "😈 OTP बता दो… बैंक से बोल रहा हूँ!",
                            "😈 Tell me the OTP… I'm calling from your bank!"
                        ),
                        modifier = Modifier.padding(16.dp),
                        color = Ink,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        item {
            Text(
                text = tr("आप क्या करेंगे?", "What will you do?"),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = DeepBlue
            )
        }
        item { ChoiceButton(tr("OTP बता दूँ", "Share the OTP"), Red) { answer = false } }
        item {
            ChoiceButton(tr("कभी नहीं बताऊँगा / बताऊँगी", "Never share it"), Green) {
                answer = true
            }
        }
        item {
            AnimatedVisibility(visible = answer != null) {
                Card(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (answer == true) Color(0xFFE7F9EB) else Color(0xFFFFECEB)
                    ),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(
                                if (answer == true) R.drawable.safey_green else R.drawable.permission_chor
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(105.dp)
                        )
                        Text(
                            text = if (answer == true) {
                                tr("सही जवाब! 🎉", "Correct! 🎉")
                            } else {
                                tr("यही चाल है! फिर से सोचें।", "That's the trap. Think again.")
                            },
                            fontSize = 23.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (answer == true) Green else Red
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = tr(
                                "आज का नियम: OTP, UPI PIN और पासवर्ड किसी को नहीं बताना — बैंक कर्मचारी को भी नहीं।",
                                "Today's rule: Never share OTP, UPI PIN or passwords — not even with someone claiming to be from your bank."
                            ),
                            textAlign = TextAlign.Center,
                            color = Ink
                        )
                        if (answer == true) {
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { onComplete(); back() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    if (alreadyDone) tr("होम पर जाएँ", "Back home")
                                    else tr("+100 XP • पाठ पूरा करें", "+100 XP • Complete"),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChoiceButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(58.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LightningScreen(hindi: Boolean, back: () -> Unit) {
    fun tr(hi: String, en: String) = if (hindi) hi else en
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item { TopBar(tr("बिजली सुरक्षा", "Lightning Safety"), back) }
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFF56206F), Color(0xFF101B4A))))
                    .padding(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.bijli_bhoot),
                        contentDescription = null,
                        modifier = Modifier.size(170.dp)
                    )
                    Text(
                        tr("⚡ बिजली भूत आ गया!", "⚡ Bijli Bhoot is here!"),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        tr(
                            "डेमो: लाइव बिजली डेटा अभी जुड़ा नहीं है",
                            "Demo: live lightning data is not connected yet"
                        ),
                        color = Color(0xFFFFD762),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFECEB)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        tr("🔴 बिजली पास हो तो क्या करें", "🔴 What to do when lightning is nearby"),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        color = Red
                    )
                    Spacer(Modifier.height(10.dp))
                    val tips = listOf(
                        tr("🏠 पक्की इमारत या बंद गाड़ी में जाएँ", "🏠 Go inside a substantial building or enclosed vehicle"),
                        tr("🌳 अकेले पेड़ के नीचे न खड़े हों", "🌳 Do not shelter under an isolated tree"),
                        tr("🌾 खुले मैदान से दूर रहें", "🌾 Stay away from open fields"),
                        tr("💧 पानी से दूर रहें", "💧 Stay away from water")
                    )
                    tips.forEach { tip ->
                        Text(tip, Modifier.padding(vertical = 5.dp), color = Ink, fontSize = 16.sp)
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE9F8EC)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.safey_green),
                        contentDescription = null,
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        tr(
                            "सेफी: बाहर हीरो बाद में बनना — पहले सुरक्षित जगह चलो! 😄",
                            "Safey: Be a hero later — get to a safe place first! 😄"
                        ),
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                }
            }
        }
    }
}

@Composable
fun FeedbackScreen(hindi: Boolean, back: () -> Unit) {
    fun tr(hi: String, en: String) = if (hindi) hi else en
    val context = LocalContext.current
    var rating by remember { mutableIntStateOf(0) }
    var useful by remember { mutableStateOf(false) }
    var funApp by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp)
    ) {
        item { TopBar(tr("परिवार की राय", "Family Feedback"), back) }
        item {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.detective_tara),
                    contentDescription = null,
                    modifier = Modifier.size(125.dp)
                )
                Text(
                    tr("आप Be Safe को कितने सितारे देंगे?", "How many stars would you give Be Safe?"),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 21.sp,
                    textAlign = TextAlign.Center,
                    color = DeepBlue
                )
                Row {
                    (1..5).forEach { n ->
                        Text(
                            if (n <= rating) "★" else "☆",
                            Modifier.padding(5.dp).clickable { rating = n },
                            fontSize = 40.sp,
                            color = Amber
                        )
                    }
                }
                FeedbackToggle(tr("यह उपयोगी है", "This is useful"), useful) { useful = !useful }
                FeedbackToggle(tr("सीखना मज़ेदार लगा", "Learning felt fun"), funApp) { funApp = !funApp }
                FeedbackToggle(
                    tr("मैं इसे फोन में रखूँगा / रखूँगी", "I would keep it installed"),
                    keep
                ) { keep = !keep }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val text = "Be Safe family feedback: $rating/5 stars | useful=$useful | fun=$funApp | keep=$keep"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, tr("राय साझा करें", "Share feedback"))
                        )
                    },
                    enabled = rating > 0,
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(tr("राय साझा करें", "Share feedback"), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(
                        "कोई नाम, फोन नंबर या ईमेल नहीं माँगा जाता।",
                        "No name, phone number or email is requested."
                    ),
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun FeedbackToggle(text: String, value: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (value) Color(0xFFE6F8EB) else Color.White,
        tonalElevation = 2.dp
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (value) "✅" else "○", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Text(text, fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}

@Composable
fun TopBar(title: String, back: () -> Unit) {
    Surface(color = Blue, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = back) { Text("‹", color = Color.White, fontSize = 36.sp) }
            Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
