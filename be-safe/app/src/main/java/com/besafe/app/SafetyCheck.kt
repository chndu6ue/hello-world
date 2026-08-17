package com.besafe.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun PhoneCheckScreen(hindi:Boolean, back:()->Unit) {
    fun tr(hi:String,en:String)=if(hindi)hi else en
    val context=LocalContext.current
    var scanning by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(.04f) }
    var findings by remember { mutableStateOf(emptyList<Finding>()) }
    LaunchedEffect(Unit){ repeat(5){ delay(330); progress += .18f }; findings=runSafetyChecks(context,hindi); progress=1f; scanning=false }
    val animated by animateFloatAsState(progress,label="scan")
    val score=remember(findings){ (100-findings.sumOf{if(it.risk==Risk.RISK)18 else if(it.risk==Risk.REVIEW)7 else 0}).coerceAtLeast(35) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp)){
        item { TopBar(tr("फोन सुरक्षा जाँच","Phone Safety Check"),back) }
        item {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF087FEA),Blue))).padding(24.dp),contentAlignment=Alignment.Center){
                Column(horizontalAlignment=Alignment.CenterHorizontally){Image(painterResource(if(scanning)R.drawable.be_safe_app_icon_master else R.drawable.safey_green),null,Modifier.size(125.dp));Spacer(Modifier.height(12.dp));Text(if(scanning)tr("जाँच जारी है…","Checking your phone…") else "$score / 100",color=Color.White,fontSize=30.sp,fontWeight=FontWeight.ExtraBold);Spacer(Modifier.height(10.dp));LinearProgressIndicator(progress={animated},Modifier.fillMaxWidth(.78f).height(10.dp).clip(CircleShape),color=Color(0xFF65E17B),trackColor=Color.White.copy(.25f));Spacer(Modifier.height(8.dp));Text(if(scanning)tr("सिर्फ कुछ सेकंड — कोई निजी जानकारी अपलोड नहीं होती।","A few seconds — no personal information is uploaded.") else if(score>=85)tr("बहुत अच्छा! कुछ चीज़ें समय-समय पर देखते रहें।","Looking good. Keep reviewing powerful permissions." ) else tr("कुछ सेटिंग्स आपकी नज़र चाहती हैं।","A few settings deserve your attention."),color=Color.White,textAlign=TextAlign.Center)}
            }
        }
        if(!scanning){
            item { Text(tr("जाँच के परिणाम","Check results"),Modifier.padding(18.dp),fontWeight=FontWeight.ExtraBold,fontSize=20.sp,color=DeepBlue) }
            items(findings){ finding -> FindingCard(finding,hindi) }
            item { Card(Modifier.padding(16.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Sky),shape=RoundedCornerShape(20.dp)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Image(painterResource(R.drawable.safey_green),null,Modifier.size(70.dp));Spacer(Modifier.width(12.dp));Text(tr("याद रखें: VPN या Accessibility अपने-आप में वायरस नहीं हैं। बस शक्तिशाली पहुँच केवल भरोसेमंद ऐप को दें।","Remember: VPN or Accessibility is not automatically malware. Give powerful access only to apps you trust."),color=Ink)}} }
        }
    }
}

@Composable
fun FindingCard(f:Finding,hindi:Boolean){
    val c=when(f.risk){Risk.GOOD->Green;Risk.REVIEW->Amber;Risk.RISK->Red};val icon=when(f.risk){Risk.GOOD->"✓";Risk.REVIEW->"!";Risk.RISK->"×"}
    Card(Modifier.padding(horizontal=16.dp,vertical=5.dp).fillMaxWidth(),shape=RoundedCornerShape(19.dp),colors=CardDefaults.cardColors(containerColor=c.copy(.09f))){Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Surface(shape=CircleShape,color=c,modifier=Modifier.size(38.dp)){Box(contentAlignment=Alignment.Center){Text(icon,color=Color.White,fontWeight=FontWeight.Bold,fontSize=20.sp)}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(f.title,fontWeight=FontWeight.Bold,color=Ink);Text(f.detail,color=Color(0xFF52647A),fontSize=13.sp)};if(f.action!=null)TextButton(onClick=f.action){Text(if(hindi)"देखें" else "Review")}}}
}

fun runSafetyChecks(context:Context,hindi:Boolean):List<Finding>{
    fun tr(hi:String,en:String)=if(hindi)hi else en
    val list=mutableListOf<Finding>()
    val cm=context.getSystemService(ConnectivityManager::class.java);val network=cm.activeNetwork;val caps=network?.let{cm.getNetworkCapabilities(it)}
    val vpn=caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
    val validated=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    val captive=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true
    val wifi=caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
    val am=context.getSystemService(AccessibilityManager::class.java);val access=am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).size
    val dpm=context.getSystemService(DevicePolicyManager::class.java);val admins=dpm.activeAdmins?.size?:0
    val adb=Settings.Global.getInt(context.contentResolver,Settings.Global.ADB_ENABLED,0)==1
    val dev=Settings.Global.getInt(context.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1
    val rooted=Build.TAGS?.contains("test-keys")==true || listOf("/system/bin/su","/system/xbin/su","/sbin/su","/su/bin/su").any{File(it).exists()}
    val emulator=Build.FINGERPRINT.startsWith("generic")||Build.FINGERPRINT.contains("emulator",true)||Build.MODEL.contains("Emulator",true)||Build.HARDWARE.contains("ranchu",true)||Build.HARDWARE.contains("goldfish",true)
    val accessIntent={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    val vpnIntent={context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    val devIntent={context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    list += Finding(tr("नेटवर्क कनेक्शन","Internet connection"),if(validated)tr("इंटरनेट कनेक्शन सत्यापित है","Internet connection is validated") else tr("इंटरनेट सत्यापित नहीं हुआ","Internet could not be validated"),if(validated)Risk.GOOD else Risk.REVIEW)
    if(captive) list += Finding(tr("कैप्टिव पोर्टल","Captive portal"),tr("नेटवर्क लॉगिन पेज मिला — संवेदनशील काम से पहले सावधान रहें","Network login portal detected — avoid sensitive work until you trust it"),Risk.REVIEW)
    list += Finding(tr("VPN स्थिति","VPN status"),if(vpn)tr("VPN सक्रिय है — क्या आप इसे पहचानते हैं?","VPN is active — make sure you recognize it") else tr("कोई VPN सक्रिय नहीं मिला","No active VPN detected"),if(vpn)Risk.REVIEW else Risk.GOOD,if(vpn)vpnIntent else null)
    if(wifi){
        var sec=tr("Wi‑Fi जुड़ा है","Wi‑Fi connected");var risk=Risk.GOOD
        if(Build.VERSION.SDK_INT>=31){val wi=caps?.transportInfo as? WifiInfo;val t=wi?.currentSecurityType?:-1;sec=when(t){0->tr("खुला Wi‑Fi — पासवर्ड सुरक्षा नहीं","Open Wi‑Fi — no password protection");1->"WEP";2->"WPA/WPA2 PSK";3->"Enterprise Wi‑Fi";4->"WPA3 SAE";6->"Enhanced Open (OWE)";else->tr("Wi‑Fi सुरक्षा उपलब्ध","Wi‑Fi security detected")};risk=if(t==0||t==1)Risk.RISK else Risk.GOOD}
        list+=Finding(tr("Wi‑Fi सुरक्षा","Wi‑Fi security"),sec,risk,{context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))})
    }
    list += Finding(tr("Accessibility पहुँच","Accessibility access"),tr("$access सेवा सक्षम — केवल भरोसेमंद ऐप को यह शक्ति दें","$access enabled service(s) — grant this power only to apps you trust"),if(access>1)Risk.REVIEW else Risk.GOOD,if(access>0)accessIntent else null)
    list += Finding(tr("डिवाइस एडमिन","Device administrators"),tr("$admins सक्रिय एडमिन ऐप","$admins active administrator app(s)"),if(admins>1)Risk.REVIEW else Risk.GOOD)
    list += Finding(tr("रूट संकेत","Root indicators"),if(rooted)tr("कुछ स्थानीय रूट संकेत मिले","Local root indicators were found") else tr("साधारण रूट संकेत नहीं मिले","No simple root indicators found"),if(rooted)Risk.RISK else Risk.GOOD)
    list += Finding(tr("डेवलपर सेटिंग्स","Developer settings"),if(adb||dev)tr("Developer Options या ADB चालू है","Developer Options or ADB is enabled") else tr("Developer Options/ADB सामान्य स्थिति में","Developer Options/ADB not enabled"),if(adb||dev)Risk.REVIEW else Risk.GOOD,if(adb||dev)devIntent else null)
    list += Finding(tr("डिवाइस वातावरण","Device environment"),if(emulator)tr("एमुलेटर जैसे संकेत मिले","Emulator-like signals detected") else tr("सामान्य Android फोन जैसा वातावरण","Looks like a normal Android phone environment"),if(emulator)Risk.RISK else Risk.GOOD)
    list += Finding(tr("सुरक्षा पैच","Security patch"),tr("Android सुरक्षा पैच: ${Build.VERSION.SECURITY_PATCH.ifBlank{"अज्ञात"}}","Android security patch: ${Build.VERSION.SECURITY_PATCH.ifBlank{"unknown"}}"),Risk.GOOD)
    return list
}
