package com.echoos

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
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

// --- 1. UYGULAMA ÇEKİRDEĞİ ---
class EchoApplication : Application() {
    companion object { const val CHANNEL_ID = "echo_core_channel" }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ECHO-OS Kalkanı",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}

// --- 2. GÖVDE DUBLÖRÜ (ALTER EGO TTS) ---
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

// --- 3. 8D SES MOTORU (ALARM ANINDA ANINDA SUSTURULABİLİR) ---
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

// --- 4. MERKEZİ ARKA PLAN SERVİSİ ---
class IntentGateService : Service() {
    private var tts: EchoTtsEngine? = null
    private val audioEngine = ProceduralAudioEngine()
    private var runwayTimer: CountDownTimer? = null
    private var alarmRingtone: Ringtone? = null

    companion object {
        const val ACTION_START_DEEP_FOCUS = "ACTION_START_DEEP_FOCUS"
        const val ACTION_START_KINETIC = "ACTION_START_KINETIC"
        const val ACTION_STOP_FOCUS = "ACTION_STOP_FOCUS"
        const val ACTION_START_BREAK = "ACTION_START_BREAK"
        const val ACTION_STOP_BREAK = "ACTION_STOP_BREAK"
        const val ACTION_TRIGGER_ALARM = "ACTION_TRIGGER_ALARM"
        const val ACTION_STOP_ALARM = "ACTION_STOP_ALARM"
        const val ACTION_TOGGLE_EMERGENCY = "ACTION_TOGGLE_EMERGENCY"
        const val EXTRA_BREAK_MINUTES = "EXTRA_BREAK_MINUTES"
    }

    override fun onCreate() {
        super.onCreate()
        tts = EchoTtsEngine(this) {}
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        alarmRingtone = RingtoneManager.getRingtone(this, alarmUri)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopFocusIntent = Intent(this, IntentGateService::class.java).apply { action = ACTION_STOP_FOCUS }
        val pStopFocus = PendingIntent.getService(this, 1, stopFocusIntent, PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, EchoApplication.CHANNEL_ID)
            .setContentTitle("ECHO-OS Kalkanı Devrede")
            .setContentText("Nöro-İşletim Sistemi Aktif")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Çalışmayı/Molayı Bitir", pStopFocus)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(4040, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(4040, notif)
        }

        when (intent?.action) {
            ACTION_START_DEEP_FOCUS -> {
                alarmRingtone?.stop()
                tts?.speak("Yanındayım. Bütün işi düşünme, sadece önündeki ilk parçaya bakıyoruz.", "FOCUS")
                audioEngine.start(isKinetic = false)
            }
            ACTION_START_KINETIC -> {
                alarmRingtone?.stop()
                tts?.speak("Bedenini harekete geçiriyoruz. Küçük bir hareketle başla.", "KINETIC")
                audioEngine.start(isKinetic = true)
            }
            ACTION_STOP_FOCUS -> {
                audioEngine.stop()
                tts?.speak("Odak seansı tamamlandı. Zihnini dinlendir.", "STOP_FOCUS")
            }
            ACTION_START_BREAK -> {
                audioEngine.stop()
                val minutes = intent.getIntExtra(EXTRA_BREAK_MINUTES, 15)
                tts?.speak("$minutes dakikalık mola başladı. Zamanı ben tutuyorum.", "BREAK")
                startRunwayTimer(minutes)
            }
            ACTION_STOP_BREAK -> {
                runwayTimer?.cancel()
                tts?.speak("Mola bitti. Tekrar ritme girmeye hazırız.", "STOP_BREAK")
            }
            ACTION_TRIGGER_ALARM -> {
                // KRİTİK DÜZELTME: Alarm çaldığı an Brown Noise ve TTS BIÇAK GİBİ kesilir!
                audioEngine.stop()
                tts?.stop()
                try {
                    alarmRingtone?.play()
                } catch (e: Exception) {}
            }
            ACTION_STOP_ALARM -> {
                alarmRingtone?.stop()
                tts?.speak("On beş adım tamamlandı. Korteks uyandı, alarm susturuldu.", "ALARM_STOPPED")
            }
            ACTION_TOGGLE_EMERGENCY -> {
                audioEngine.stop()
                alarmRingtone?.stop()
                tts?.stop()
            }
        }
        return START_STICKY
    }

    private fun startRunwayTimer(minutes: Int) {
        runwayTimer?.cancel()
        runwayTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val min = (millisUntilFinished / 1000) / 60
                val sec = (millisUntilFinished / 1000) % 60
                if (min == 5L && sec == 0L) {
                    tts?.speak("Molada son 5 dakikan. Yavaşça hazırlan.", "RUNWAY_5")
                }
            }
            override fun onFinish() {
                tts?.speak("Mola süresi doldu.", "RUNWAY_END")
            }
        }.start()
    }

    override fun onDestroy() {
        audioEngine.stop()
        alarmRingtone?.stop()
        runwayTimer?.cancel()
        tts?.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

// --- 5. ANA EKRAN & SAYFALI GEZİNTİ ---
enum class EchoTab { FOCUS_BREAK, ALARM, NEEDS, SETTINGS }

class MainActivity : ComponentActivity(), SensorEventListener {
    private var sm: SensorManager? = null
    private var stepCountState = mutableIntStateOf(0)
    private var isAlarmActiveState = mutableStateOf(false)
    private var isFocusActiveState = mutableStateOf(false)
    private var isBreakActiveState = mutableStateOf(false)
    private var activeTabState = mutableStateOf(EchoTab.FOCUS_BREAK)

    // Kullanıcının İhtiyaç Filtreleri (Neye İhtiyacın Var?)
    private var showBrainDumpState = mutableStateOf(true)
    private var showFocusSoundsState = mutableStateOf(true)
    private var showBreakState = mutableStateOf(true)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        setContent {
            MaterialTheme {
                Scaffold(
                    bottomBar = {
                        EchoBottomBar(
                            currentTab = activeTabState.value,
                            onTabSelected = { activeTabState.value = it }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (activeTabState.value) {
                            EchoTab.FOCUS_BREAK -> FocusBreakScreen(
                                isFocusActive = isFocusActiveState.value,
                                isBreakActive = isBreakActiveState.value,
                                showBrainDump = showBrainDumpState.value,
                                showFocusSounds = showFocusSoundsState.value,
                                showBreak = showBreakState.value,
                                onStartFocus = { isKinetic ->
                                    isFocusActiveState.value = true
                                    isBreakActiveState.value = false
                                    sendServiceAction(
                                        if (isKinetic) IntentGateService.ACTION_START_KINETIC 
                                        else IntentGateService.ACTION_START_DEEP_FOCUS
                                    )
                                },
                                onStopFocus = {
                                    isFocusActiveState.value = false
                                    sendServiceAction(IntentGateService.ACTION_STOP_FOCUS)
                                },
                                onStartBreak = { min ->
                                    isBreakActiveState.value = true
                                    isFocusActiveState.value = false
                                    val intent = Intent(this@MainActivity, IntentGateService::class.java).apply {
                                        action = IntentGateService.ACTION_START_BREAK
                                        putExtra(IntentGateService.EXTRA_BREAK_MINUTES, min)
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onStopBreak = {
                                    isBreakActiveState.value = false
                                    sendServiceAction(IntentGateService.ACTION_STOP_BREAK)
                                }
                            )
                            EchoTab.ALARM -> AlarmProtocolScreen(
                                isAlarmActive = isAlarmActiveState.value,
                                stepCount = stepCountState.intValue,
                                onTestAlarm = { startNeuroAwakeAlarm() }
                            )
                            EchoTab.NEEDS -> NeedsSelectionScreen(
                                showBrainDump = showBrainDumpState.value,
                                showFocusSounds = showFocusSoundsState.value,
                                showBreak = showBreakState.value,
                                onToggleBrainDump = { showBrainDumpState.value = !showBrainDumpState.value },
                                onToggleFocusSounds = { showFocusSoundsState.value = !showFocusSoundsState.value },
                                onToggleBreak = { showBreakState.value = !showBreakState.value }
                            )
                            EchoTab.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }

        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun sendServiceAction(action: String) {
        try {
            val intent = Intent(this, IntentGateService::class.java).apply { this.action = action }
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {}
    }

    private fun startNeuroAwakeAlarm() {
        isAlarmActiveState.value = true
        stepCountState.intValue = 0
        sendServiceAction(IntentGateService.ACTION_TRIGGER_ALARM)

        // Adım Sayar Sensörünü Dinlemeye Başla
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isAlarmActiveState.value) return
        stepCountState.intValue++

        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        // 15 ADIM ŞARTI SAĞLANDIĞINDA ALARM SUSTURULUR
        if (stepCountState.intValue >= 15) {
            isAlarmActiveState.value = false
            sm?.unregisterListener(this)
            sendServiceAction(IntentGateService.ACTION_STOP_ALARM)
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}

    override fun onDestroy() {
        sm?.unregisterListener(this)
        super.onDestroy()
    }
}

// --- 6. SAYFA 1: ODAK & MOLA (GİRİŞ VE ÇIKIŞ BUTONLU) ---
@Composable
fun FocusBreakScreen(
    isFocusActive: Boolean,
    isBreakActive: Boolean,
    showBrainDump: Boolean,
    showFocusSounds: Boolean,
    showBreak: Boolean,
    onStartFocus: (Boolean) -> Unit,
    onStopFocus: () -> Unit,
    onStartBreak: (Int) -> Unit,
    onStopBreak: () -> Unit
) {
    var rawThought by remember { mutableStateOf("") }
    var microSteps by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }
    var selectedBreakMin by remember { mutableIntStateOf(15) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0D)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "ECHO-OS // ODAK KORUMASI",
                color = Color(0xFFF8FAFC),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            if (showBrainDump) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B26))) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("ZİHİNSEL DÖKÜM", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = rawThought,
                            onValueChange = { rawThought = it },
                            placeholder = { Text("Zihnini kemiren işi buraya yaz...", color = Color(0xFF64748B), fontSize = 12.sp) },
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF0E131F), RoundedCornerShape(8.dp)),
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (rawThought.isNotBlank()) {
                                    microSteps = listOf(
                                        "1. İlgili alana sadece geç ve otur" to false,
                                        "2. İlk eşyayı/belgeyi ekranda aç" to false,
                                        "3. 2 dakika boyunca sadece ilk adıma bak" to false
                                    )
                                    rawThought = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Text("3 MİKRO ADIMA BÖL", fontSize = 11.sp, color = Color.White)
                        }
                        if (microSteps.isNotEmpty()) {
                            microSteps.forEachIndexed { index, step ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable {
                                        microSteps = microSteps.toMutableList().also {
                                            it[index] = it[index].copy(second = !it[index].second)
                                        }
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = step.second, onCheckedChange = null)
                                    Text(step.first, color = if (step.second) Color.Gray else Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ODAK BÖLÜMÜ (GİRİŞ VE ÇIKIŞ BUTONLARI)
            if (showFocusSounds) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ODAK KALKANI", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (!isFocusActive) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onStartFocus(false) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                                ) {
                                    Text("8D BROWN", color = Color(0xFF38BDF8), fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { onStartFocus(true) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                                ) {
                                    Text("8D KİNETİK", color = Color(0xFFF59E0B), fontSize = 11.sp)
                                }
                            }
                        } else {
                            // BEKLENEN ÇIKIŞ BUTONU
                            Button(
                                onClick = onStopFocus,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                            ) {
                                Text("ÇALIŞMAM BİTTİ (ODAK SESİNİ DURDUR)", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // MOLA BÖLÜMÜ (GİRİŞ VE ÇIKIŞ BUTONLARI)
            if (showBreak) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B))) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("BİLİNÇLİ MOLA", color = Color(0xFFA5B4FC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (!isBreakActive) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(10, 15, 30).forEach { min ->
                                    Box(
                                        modifier = Modifier
                                            .background(if (selectedBreakMin == min) Color(0xFF6366F1) else Color(0xFF312E81), RoundedCornerShape(8.dp))
                                            .clickable { selectedBreakMin = min }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("${min} dk", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                            Button(
                                onClick = { onStartBreak(selectedBreakMin) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4338CA))
                            ) {
                                Text("MOLAYI BAŞLAT", color = Color.White)
                            }
                        } else {
                            // BEKLENEN ÇIKIŞ BUTONU
                            Button(
                                onClick = onStopBreak,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
                            ) {
                                Text("MOLA BİTTİ (ÇALIŞMAYA DÖN)", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- 7. SAYFA 2: NEURO-AWAKE ALARM (1 NUMARALI ÖNCELİK) ---
@Composable
fun AlarmProtocolScreen(
    isAlarmActive: Boolean,
    stepCount: Int,
    onTestAlarm: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0D)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NEURO-AWAKE PROTOKOLÜ", color = Color(0xFFF8FAFC), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Yatak felcini kıran kilit: 15 adım atmadan alarm susmaz.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }

            if (isAlarmActive) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ALARM DEVREDE!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("SUSMASI İÇİN YÜRÜYÜN", color = Color(0xFFFECACA), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "$stepCount / 15 ADIM",
                            color = Color(0xFFFDE047),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 32.sp
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("SABAH ALARM PLANI", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("1. Alarm çalmadan önce Galaxy Watch bilekte titreşim başlatır.", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        Text("2. Telefondan alarm çaldığı an Brown Noise dahil tüm sesler kesilir.", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        Text("3. Telefonu elinize alıp 15 adım atana kadar alarm susturulamaz.", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    }
                }
            }

            Button(
                onClick = onTestAlarm,
                enabled = !isAlarmActive,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C))
            ) {
                Text(
                    text = if (isAlarmActive) "KİLİT DEVREDE (YÜRÜYÜN)" else "ALARM VE 15 ADIM PROTOKOLÜNÜ BAŞLAT",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// --- 8. SAYFA 3: NEYE İHTİYACIN VAR? (MODÜLER FİLTRE) ---
@Composable
fun NeedsSelectionScreen(
    showBrainDump: Boolean,
    showFocusSounds: Boolean,
    showBreak: Boolean,
    onToggleBrainDump: () -> Unit,
    onToggleFocusSounds: () -> Unit,
    onToggleBreak: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0D)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("NEYE İHTİYACIN VAR?", color = Color(0xFFF8FAFC), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Arayüzün zihnini yormaması için sadece kullanmak istediğin modülleri açık tut.",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Zihinsel Döküm (Brain Dump)", color = Color.White, fontSize = 13.sp)
                        Switch(checked = showBrainDump, onCheckedChange = { onToggleBrainDump() })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Odak Kalkanı (8D Ses Motoru)", color = Color.White, fontSize = 13.sp)
                        Switch(checked = showFocusSounds, onCheckedChange = { onToggleFocusSounds() })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bilinçli Mola ve Sayaç", color = Color.White, fontSize = 13.sp)
                        Switch(checked = showBreak, onCheckedChange = { onToggleBreak() })
                    }
                }
            }
        }
    }
}

// --- 9. SAYFA 4: AYARLAR & PROFİL ---
@Composable
fun SettingsScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0D)) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("AYARLAR VE HESAP", color = Color(0xFFF8FAFC), fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HESAP VE VERİ", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Kullanıcı: Yerel Profil (%100 Çevrimdışı)", color = Color.White, fontSize = 12.sp)
                    Text("API Maliyeti: $0 (Bulut bağlantısı yok)", color = Color(0xFF34D399), fontSize = 12.sp)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ALTER EGO SES TONU SEÇİMİ", color = Color(0xFFA855F7), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Mevcut: Sıcak Gövde Dublörü (0.92x)", color = Color.White, fontSize = 12.sp)
                    Text("Aşama 2 ile sisteme özel kadın/erkek yerel ses seçici eklenecektir.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
            }
        }
    }
}

// --- 10. ALT GEZİNTİ ÇUBUĞU (BOTTOM NAVIGATION) ---
@Composable
fun EchoBottomBar(
    currentTab: EchoTab,
    onTabSelected: (EchoTab) -> Unit
) {
    NavigationBar(containerColor = Color(0xFF0F172A)) {
        NavigationBarItem(
            selected = currentTab == EchoTab.FOCUS_BREAK,
            onClick = { onTabSelected(EchoTab.FOCUS_BREAK) },
            label = { Text("Odak", fontSize = 10.sp) },
            icon = { Text("🎯", fontSize = 16.sp) }
        )
        NavigationBarItem(
            selected = currentTab == EchoTab.ALARM,
            onClick = { onTabSelected(EchoTab.ALARM) },
            label = { Text("Alarm", fontSize = 10.sp) },
            icon = { Text("⏰", fontSize = 16.sp) }
        )
        NavigationBarItem(
            selected = currentTab == EchoTab.NEEDS,
            onClick = { onTabSelected(EchoTab.NEEDS) },
            label = { Text("İhtiyaçlar", fontSize = 10.sp) },
            icon = { Text("🎛️", fontSize = 16.sp) }
        )
        NavigationBarItem(
            selected = currentTab == EchoTab.SETTINGS,
            onClick = { onTabSelected(EchoTab.SETTINGS) },
            label = { Text("Ayarlar", fontSize = 10.sp) },
            icon = { Text("⚙️", fontSize = 16.sp) }
        )
    }
}
// --- KODUN SONU: ECHO-OS ---
