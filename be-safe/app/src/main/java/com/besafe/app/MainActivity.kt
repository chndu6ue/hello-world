package com.besafe.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

val Blue = Color(0xFF0759C4)
val DeepBlue = Color(0xFF052B68)
val Sky = Color(0xFFEAF6FF)
val Green = Color(0xFF2CB34A)
val Amber = Color(0xFFFFB300)
val Red = Color(0xFFE53935)
val Purple = Color(0xFF7146D9)
val Ink = Color(0xFF12213A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Green)) { BeSafeApp() } }
    }
}

enum class Screen { HOME, CHECK, LESSON, LIGHTNING, FEEDBACK }
enum class Risk { GOOD, REVIEW, RISK }
data class Finding(val title: String, val detail: String, val risk: Risk, val action: (() -> Unit)? = null)

@Composable
private fun BeSafeApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("be_safe", Context.MODE_PRIVATE) }
    var hindi by remember { mutableStateOf(prefs.getBoolean("hindi", true)) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var xp by remember { mutableIntStateOf(prefs.getInt("xp", 120)) }
    var lessonDone by remember { mutableStateOf(prefs.getBoolean("lesson_done", false)) }

    fun tr(hi: String, en: String) = if (hindi) hi else en
    fun setHindi(value: Boolean) { hindi = value; prefs.edit().putBoolean("hindi", value).apply() }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7FBFF)) {
        when (screen) {
            Screen.HOME -> HomeScreen(hindi, xp, lessonDone, ::setHindi, { screen = Screen.CHECK }, { screen = Screen.LESSON }, { screen = Screen.LIGHTNING }, { screen = Screen.FEEDBACK })
            Screen.CHECK -> PhoneCheckScreen(hindi, { screen = Screen.HOME })
            Screen.LESSON -> LessonScreen(hindi, lessonDone, { screen = Screen.HOME }) {
                if (!lessonDone) { lessonDone = true; xp += 100; prefs.edit().putBoolean("lesson_done", true).putInt("xp", xp).apply() }
            }
            Screen.LIGHTNING -> LightningScreen(hindi) { screen = Screen.HOME }
            Screen.FEEDBACK -> FeedbackScreen(hindi) { screen = Screen.HOME }
        }
    }
}

@Composable
private fun HomeScreen(hindi: Boolean, xp: Int, lessonDone: Boolean, setHindi: (Boolean)->Unit, openCheck:()->Unit, openLesson:()->Unit, openLightning:()->Unit, openFeedback:()->Unit) {
    fun tr(hi:String,en:String)=if(hindi)hi else en
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Blue, DeepBlue))).padding(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        AssistChip(onClick={setHindi(true)}, label={Text("हिन्दी")}, colors=AssistChipDefaults.assistChipColors(containerColor=if(hindi) Color.White else Color.White.copy(.2f), labelColor=if(hindi) Blue else Color.White))
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick={setHindi(false)}, label={Text("English")}, colors=AssistChipDefaults.assistChipColors(containerColor=if(!hindi) Color.White else Color.White.copy(.2f), labelColor=if(!hindi) Blue else Color.White))
                    }
                    Image(painterResource(R.drawable.be_safe_app_icon_master), null, Modifier.size(150.dp))
                    Text("Be Safe", fontSize=38.sp, fontWeight=FontWeight.ExtraBold, color=Color.White)
                    Text(tr("सुरक्षित रहें • समझदार रहें", "Stay alert • Stay smart • Stay safe"), color=Color.White.copy(.9f), fontSize=16.sp)
                    Spacer(Modifier.height(16.dp))
                    Surface(shape=RoundedCornerShape(22.dp), color=Color.White.copy(.15f)) {
                        Row(Modifier.padding(horizontal=18.dp, vertical=10.dp), verticalAlignment=Alignment.CenterVertically) {
                            Text("⭐", fontSize=22.sp); Spacer(Modifier.width(8.dp)); Text("$xp XP", color=Color.White, fontWeight=FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)); SectionTitle(tr("आज सबसे पहले", "Start here")) }
        item {
            ActionCard("🛡️", tr("मेरा फोन जाँचें", "Check My Phone"), tr("VPN, Accessibility, नेटवर्क, रूट और सुरक्षा सेटिंग्स की स्थानीय जाँच", "Local check of VPN, Accessibility, network, root and security settings"), Green, openCheck)
        }
        item { SectionTitle(tr("आज का 3-मिनट सुरक्षा पाठ", "Today's 3-minute safety skill")) }
        item {
            ActionCard(if(lessonDone) "✅" else "🎮", tr("OTP और PIN की सुरक्षा", "OTP & PIN Safety"), if(lessonDone) tr("आज का पाठ पूरा • +100 XP", "Completed today • +100 XP") else tr("क्या नकली बैंक कॉल आपको फँसा सकती है?", "Could a fake bank call fool you?"), Purple, openLesson)
        }
        item { SectionTitle(tr("तुरंत सुरक्षा", "Immediate safety")) }
        item {
            ActionCard("⚡", tr("बिजली अलर्ट डेमो", "Lightning Alert Demo"), tr("बिजली भूत से सीखें कि तूफ़ान में क्या करें", "Learn what to do in a thunderstorm with Bijli Bhoot"), Amber, openLightning)
        }
        item {
            Card(Modifier.padding(16.dp).fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF8DF)), shape=RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.safey_green), null, Modifier.size(82.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("सेफी की बात", "Safey says"), fontWeight=FontWeight.Bold, color=DeepBlue)
                        Text(tr("डराना नहीं है — समझना है, जाँचना है और सुरक्षित रहना है।", "No fear. Understand it, check it, and stay safe."), color=Ink)
                    }
                }
            }
        }
        item {
            OutlinedButton(openFeedback, Modifier.padding(horizontal=16.dp).fillMaxWidth().height(54.dp), shape=RoundedCornerShape(18.dp)) { Text("💬  ${tr("परिवार की राय दें", "Family feedback")}", fontWeight=FontWeight.Bold) }
        }
    }
}

@Composable private fun SectionTitle(text:String) { Text(text, Modifier.padding(horizontal=18.dp, vertical=10.dp), color=DeepBlue, fontWeight=FontWeight.ExtraBold, fontSize=19.sp) }

@Composable
private fun ActionCard(icon:String,title:String,detail:String,color:Color,onClick:()->Unit) {
    Card(Modifier.padding(horizontal=16.dp, vertical=6.dp).fillMaxWidth().clickable(onClick=onClick), shape=RoundedCornerShape(24.dp), colors=CardDefaults.cardColors(containerColor=Color.White), elevation=CardDefaults.cardElevation(3.dp)) {
        Row(Modifier.padding(17.dp), verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=CircleShape,color=color.copy(.14f),modifier=Modifier.size(58.dp)) { Box(contentAlignment=Alignment.Center){Text(icon,fontSize=27.sp)} }
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.ExtraBold,fontSize=18.sp,color=Ink);Spacer(Modifier.height(4.dp));Text(detail,color=Color(0xFF52647A),fontSize=14.sp)};Text("›",fontSize=34.sp,color=color)
        }
    }
}
