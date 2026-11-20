package com.echoos

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

// --- 1. MİKRO EYLEM ÜRETİCİ (EN BAŞA ALINDI) ---
fun generateContextualSteps(thought: String): List<Pair<String, Boolean>> {
    val lower = thought.lowercase()
    return when {
        lower.contains("oda") || lower.contains("temiz") || lower.contains("topla") || lower.contains("ev") -> {
            listOf(
                "1. Yalnızca yerdeki 1 parça nesneyi kaldır" to false,
                "2. Masanın üzerindeki ilk çöpü kutuya at" to false,
                "3. Yatağın sadece örtüsünü düzelt ve dur" to false
            )
        }
        lower.contains("yaz") || lower.contains("rapor") || lower.contains("mail") || lower.contains("kod") || lower.contains("ödev") -> {
            listOf(
                "1. İlgili belgeyi sadece ekranda aç" to false,
                "2. Yalnızca 1 başlık yazıp bırak" to false,
                "3. 2 dakika ekrana bak, gerisini düşünme" to false
            )
        }
        lower.contains("ara") || lower.contains("telefon") || lower.contains("mesaj") -> {
            listOf(
                "1. Rehberi aç ve numarayı ekrana getir" to false,
                "2. Ne diyeceğinin ilk kelimesini fısılda" to false,
                "3. Çağrı tuşuna basıp telefonu kulağına götür" to false
            )
        }
        else -> {
            listOf(
                "1. Bu iş için gereken fiziksel alana sadece geç ve otur" to false,
                "2. İlgili ilk eşyayı eline al (başlamak zorunda değilsin)" to false,
                "3. Yalnızca 60 saniye boyunca ilk adıma bak" to false
            )
        }
    }
}

// --- 2. UYGULAMA ÇEKİRDEĞİ ---
class EchoApplication : Application() {
    companion object { const val CHANNEL_ID = "echo_core_channel" }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ECHO-OS Kalkanı",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}

// --- 3. GÖVDE DUBLÖRÜ (ALTER EGO TTS) ---
class EchoTtsEngine(private val context: Context, private val onReady: () -> Unit) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("tr", "TR")
            tts?.setPitch(0.92f)
            tts?.setSpeechRate(0.94f)
            isReady = true
            onReady()
        }
    }

    fun speak(text: String, id: String) {
        if (!isReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() { tts?.stop() }
    fun shutdown() { 
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        tts = null 
    }
}

// --- 4. ÇİFT MODLU 8D SES MOTORU ---
class ProceduralAudioEngine {
    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun start(isKinetic: Boolean = false) {
        if (isPlaying) stop()
        isPlaying = true

        val sampleRate = 44100
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBufferSize > 0) minBufferSize else 8192

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            isPlaying = false
            return
        }

        audioJob = scope.launch {
            val buffer = ShortArray(bufferSize)
            var lastOutLeft = 0.0
            var lastOutRight = 0.0
            var panAngle = 0.0
            var phase = 0.0

            while (isActive && isPlaying) {
                panAngle += 0.0006
                if (panAngle >= 2 * Math.PI) panAngle = 0.0
                val pan = sin(panAngle)
                val leftGain = (1.0 - pan) * 0.5
                val rightGain = (1.0 + pan) * 0.5

                for (i in buffer.indices step 2) {
                    val whiteLeft = Random.nextDouble(-1.0, 1.0)
                    val whiteRight = Random.nextDouble(-1.0, 1.0)

                    lastOutLeft = (lastOutLeft + (0.02 * whiteLeft)) / 1.02
                    lastOutRight = (lastOutRight + (0.02 * whiteRight)) / 1.02

                    var sampleL = (lastOutLeft * 32767.0 * leftGain * 0.45)
                    var sampleR = (lastOutRight * 32767.0 * rightGain * 0.45)

                    if (isKinetic) {
                        phase += 0.02
                        val pulse = (sin(phase) + 1.0) * 0.5
                        sampleL *= (0.6 + 0.4 * pulse)
                        sampleR *= (0.6 + 0.4 * pulse)
                    }

                    buffer[i] = sampleL.toInt().coerceIn(-32768, 32767).toShort()
                    buffer[i + 1] = sampleR.toInt().coerceIn(-32768, 32767).toShort()
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }
        }
    }

    fun stop() {
        isPlaying = false
        audioJob?.cancel()
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
    }
}

// --- 5. ÇEKİRDEK SERVİS VE PİST MOTORU ---
class IntentGateService : Service() {
    private var tts: EchoTtsEngine? = null
    private val audioEngine = ProceduralAudioEngine()
    private var runwayTimer: CountDownTimer? = null
    private var previousVolume: Int = -1

    companion object {
        const val ACTION_START_DEEP_FOCUS = "ACTION_START_DEEP_FOCUS"
        const val ACTION_START_KINETIC = "ACTION_START_KINETIC"
        const val ACTION_START_BREAK = "ACTION_START_BREAK"
        const val ACTION_TOGGLE_EMERGENCY = "ACTION_TOGGLE_EMERGENCY"
        const val EXTRA_BREAK_MINUTES = "EXTRA_BREAK_MINUTES"
    }

    override fun onCreate() {
        super.onCreate()
        tts = EchoTtsEngine(this) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notif = NotificationCompat.Builder(this, EchoApplication.CHANNEL_ID)
                .setContentTitle("ECHO Gövde Dublörü Aktif")
                .setContentText("Zaman algısı ve duyusal yük dengeleniyor.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(4040, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(4040, notif)
            }
        } catch (e: Exception) {}

        when (intent?.action) {
            ACTION_START_DEEP_FOCUS -> {
                runwayTimer?.cancel()
                tts?.speak("Yanındayım. Bütün işi düşünme, sadece önündeki ilk parçaya bakıyoruz.", "FOCUS")
                audioEngine.start(isKinetic = false)
            }
            ACTION_START_KINETIC -> {
                runwayTimer?.cancel()
                tts?.speak("Bedenini harekete geçiriyoruz. Küçük bir hareketle başla.", "KINETIC")
                audioEngine.start(isKinetic = true)
            }
            ACTION_START_BREAK -> {
                audioEngine.stop()
                val minutes = intent.getIntExtra(EXTRA_BREAK_MINUTES, 15)
                tts?.speak("$minutes dakikalık bilinçli mola başladı. Zamanı ben tutuyorum, rahatça dinlen.", "BREAK")
                startRunwayEngine(minutes)
            }
            ACTION_TOGGLE_EMERGENCY -> {
                toggleEmergencyMute()
            }
        }
        return START_STICKY
    }

    private fun startRunwayEngine(minutes: Int) {
        runwayTimer?.cancel()
        val totalMs = minutes * 60 * 1000L
        runwayTimer = object : CountDownTimer(totalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remainingMin = (millisUntilFinished / 1000) / 60
                val remainingSec = (millisUntilFinished / 1000) % 60
                if (remainingMin == 5L && remainingSec == 0L) {
                    tts?.speak("Molada son beş dakikan. Zihnini yavaşça toparlamaya başlayabilirsin.", "RUNWAY_5")
                }
            }
            override fun onFinish() {
                tts?.speak("Mola süresi tamamlandı. Hazır hissettiğinde bir sonraki mikro adıma geçelim.", "RUNWAY_END")
            }
        }.start()
    }

    private fun toggleEmergencyMute() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (previousVolume == -1) {
            previousVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioEngine.stop()
            tts?.stop()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } else {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
            previousVolume = -1
            tts?.speak("Ses dengesi açıldı.", "RESTORE")
        }
    }

    override fun onDestroy() {
        audioEngine.stop()
        runwayTimer?.cancel()
        tts?.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

// --- 6. ANA EKRAN VE GÖSTERGE PANELİ ---
class MainActivity : ComponentActivity() {
    private var isMutedState = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                EchoRefinedDashboard(
                    isMuted = isMutedState.value,
                    onStartFocus = { isKinetic ->
                        sendServiceAction(
                            if (isKinetic) IntentGateService.ACTION_START_KINETIC 
                            else IntentGateService.ACTION_START_DEEP_FOCUS
                        )
                    },
                    onStartBreak = { minutes ->
                        val intent = Intent(this@MainActivity, IntentGateService::class.java).apply {
                            action = IntentGateService.ACTION_START_BREAK
                            putExtra(IntentGateService.EXTRA_BREAK_MINUTES, minutes)
                        }
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                    },
                    onToggleMute = {
                        sendServiceAction(IntentGateService.ACTION_TOGGLE_EMERGENCY)
                        isMutedState.value = !isMutedState.value
                    }
                )
            }
        }

        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun sendServiceAction(action: String) {
        try {
            val intent = Intent(this, IntentGateService::class.java).apply { this.action = action }
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {}
    }
}

// --- 7. NÖRO-ARAYÜZ BİLEŞENİ ---
@Composable
fun EchoRefinedDashboard(
    isMuted: Boolean,
    onStartFocus: (isKinetic: Boolean) -> Unit,
    onStartBreak: (minutes: Int) -> Unit,
    onToggleMute: () -> Unit
) {
    var rawThought by remember { mutableStateOf("") }
    var microSteps by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }
    var selectedBreakMin by remember { mutableIntStateOf(15) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0D)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    text = "ECHO-OS",
                    color = Color(0xFFF8FAFC),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Gövde Dublörü // Nöro-Regülasyon Kalkanı",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF171B26)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ZİHİNSEL DÖKÜM (BRAIN DUMP)",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = rawThought,
                        onValueChange = { rawThought = it },
                        placeholder = { 
                            Text("Aklını kemiren işi yaz (Örn: Odayı toplamak, Rapor hazırlamak)...", color = Color(0xFF64748B), fontSize = 12.sp) 
                        },
                        textStyle = TextStyle(color = Color(0xFFF8FAFC), fontSize = 14.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0E131F), RoundedCornerShape(8.dp)),
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (rawThought.isNotBlank()) {
                                microSteps = generateContextualSteps(rawThought)
                                rawThought = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("MİKRO EYLEME DÖNÜŞTÜR (FELÇ KIRICI)", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    if (microSteps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        microSteps.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                    .clickable {
                                        microSteps = microSteps.toMutableList().also {
                                            it[index] = it[index].copy(second = !it[index].second)
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.second,
                                    onCheckedChange = { isChecked ->
                                        microSteps = microSteps.toMutableList().also {
                                            it[index] = it[index].copy(second = isChecked)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.first,
                                    color = if (item.second) Color(0xFF64748B) else Color(0xFFF1F5F9),
                                    fontSize = 13.sp,
                                    fontWeight = if (item.second) FontWeight.Normal else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ODAK MODU SEÇİMİ",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onStartFocus(false) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text("MASA BAŞI (8D BROWN)", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onStartFocus(true) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text("HAREKET (8D KİNETİK)", color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("BİLİNÇLİ MOLA (ZAMAN KORUMASI)", color = Color(0xFFA5B4FC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 15, 30).forEach { min ->
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (selectedBreakMin == min) Color(0xFF6366F1) else Color(0xFF312E81),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { selectedBreakMin = min }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("${min}dk", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onStartBreak(selectedBreakMin) },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4338CA))
                    ) {
                        Text("MOLAYI BAŞLAT (KADEMELİ GERİ SAYIM)", fontSize = 12.sp, color = Color.White)
                    }
                }
            }

            Button(
                onClick = onToggleMute,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMuted) Color(0xFF15803D) else Color(0xFF991B1B)
                )
            ) {
                Text(
                    text = if (isMuted) "SESİ ESKİ HALİNE GETİR (UNMUTE)" else "ACİL SUSTURUCU (MUTLAK SESSİZLİK)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// --- KODUN SONU: ECHO-OS ---
