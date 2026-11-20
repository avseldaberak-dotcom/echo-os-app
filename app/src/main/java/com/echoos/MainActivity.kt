package com.echoos

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

// --- 1. UYGULAMA BAŞLATICI ---
class EchoApplication : Application() {
    companion object { const val CHANNEL_ID = "echo_core_channel" }
    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ECHO-OS Kalkanı",
                    NotificationManager.IMPORTANCE_LOW
                )
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// --- 2. DOĞAL ALTER EGO TTS MOTORU ---
class EchoTtsEngine(private val context: Context, private val onReady: () -> Unit) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("tr", "TR")
            try {
                val voices = tts?.voices
                val bestVoice = voices?.firstOrNull { 
                    it.locale.language == "tr" && !it.isNetworkConnectionRequired 
                }
                if (bestVoice != null) tts?.voice = bestVoice
            } catch (e: Exception) {}

            // Sıcak, sakin Gövde Dublörü frekansı
            tts?.setPitch(0.88f)
            tts?.setSpeechRate(0.96f)
            isReady = true
            onReady()
        }
    }

    fun speak(text: String, id: String, onDone: (() -> Unit)? = null) {
        if (!isReady) return
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(i: String?) {}
            override fun onDone(i: String?) { if (i == id) onDone?.invoke() }
            @Deprecated("Deprecated") override fun onError(i: String?) {}
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() { tts?.stop() }
    fun shutdown() { 
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {}
        tts = null 
    }
}

// --- 3. DOSYASIZ 8D UZAMSAL BROWN NOISE MOTORU ---
class ProceduralBrownNoiseEngine {
    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    fun start() {
        if (isPlaying) return
        isPlaying = true

        val sampleRate = 44100
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Güvenlik Kalkanı: Negatif veya çok küçük buffer çökmesini önler
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
            e.printStackTrace()
            isPlaying = false
            return
        }

        audioJob = scope.launch {
            val buffer = ShortArray(bufferSize)
            var lastOutLeft = 0.0
            var lastOutRight = 0.0
            var panAngle = 0.0

            while (isActive && isPlaying) {
                panAngle += 0.0005
                if (panAngle >= 2 * Math.PI) panAngle = 0.0
                val pan = sin(panAngle)
                val leftGain = (1.0 - pan) * 0.5
                val rightGain = (1.0 + pan) * 0.5

                for (i in buffer.indices step 2) {
                    val whiteLeft = Random.nextDouble(-1.0, 1.0)
                    val whiteRight = Random.nextDouble(-1.0, 1.0)

                    lastOutLeft = (lastOutLeft + (0.02 * whiteLeft)) / 1.02
                    lastOutRight = (lastOutRight + (0.02 * whiteRight)) / 1.02

                    val sampleL = (lastOutLeft * 32767.0 * leftGain * 0.4).toInt().coerceIn(-32768, 32767)
                    val sampleR = (lastOutRight * 32767.0 * rightGain * 0.4).toInt().coerceIn(-32768, 32767)

                    buffer[i] = sampleL.toShort()
                    buffer[i + 1] = sampleR.toShort()
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }
        }
    }

    fun stop() {
        isPlaying = false
        audioJob?.cancel()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
    }
}

// --- 4. ÇEKİRDEK SERVİS (ANDROID 14 GÜVENLİ) ---
class IntentGateService : Service() {
    private var tts: EchoTtsEngine? = null
    private val noiseEngine = ProceduralBrownNoiseEngine()

    companion object {
        const val ACTION_PROMPT_INTENT = "ACTION_PROMPT_INTENT"
        const val ACTION_CHOOSE_FOCUS = "ACTION_CHOOSE_FOCUS"
        const val ACTION_CHOOSE_DOPAMINE = "ACTION_CHOOSE_DOPAMINE"
        const val ACTION_EMERGENCY_MUTE = "ACTION_EMERGENCY_MUTE"
    }

    override fun onCreate() {
        super.onCreate()
        tts = EchoTtsEngine(this) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notif = NotificationCompat.Builder(this, EchoApplication.CHANNEL_ID)
                .setContentTitle("ECHO-OS Kalkanı Devrede")
                .setContentText("Nöro-İşletim Sistemi Aktif")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()

            // Android 14 FGS Kuralı: mediaPlayback türü açıkça bildiriliyor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(4040, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(4040, notif)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        when (intent?.action) {
            ACTION_PROMPT_INTENT -> {
                tts?.speak("Bağlandık. Odaklanmak mı istersin, serbest mola mı?", "GATE")
            }
            ACTION_CHOOSE_FOCUS -> {
                tts?.speak("Kalkanlar devrede. Zihnini serbest bırak, akış başlıyor.", "FOCUS") {
                    noiseEngine.start()
                }
            }
            ACTION_CHOOSE_DOPAMINE -> {
                noiseEngine.stop()
                tts?.speak("Serbest akış. Zaman kalkanı seni izliyor, suçluluk hissetme.", "DOPAMINE")
            }
            ACTION_EMERGENCY_MUTE -> {
                noiseEngine.stop()
                tts?.stop()
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                } catch (e: Exception) {}
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        noiseEngine.stop()
        tts?.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

// --- 5. ANA EKRAN & ZİHİNSEL DÖKÜM ---
class MainActivity : ComponentActivity(), SensorEventListener {
    private var sm: SensorManager? = null
    private var stepCountState = mutableIntStateOf(0)
    private var isAwakeActiveState = mutableStateOf(false)
    private var tts: EchoTtsEngine? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                sendServiceAction(IntentGateService.ACTION_PROMPT_INTENT)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // İzinler verildiğinde güvenle dinleyiciyi aç
        registerBluetoothSafe()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = EchoTtsEngine(this) {}
        sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        setContent {
            MaterialTheme {
                EchoNeuroDashboard(
                    stepCount = stepCountState.intValue,
                    isAwakeActive = isAwakeActiveState.value,
                    onFocus = { sendServiceAction(IntentGateService.ACTION_CHOOSE_FOCUS) },
                    onDopamine = { sendServiceAction(IntentGateService.ACTION_CHOOSE_DOPAMINE) },
                    onAwake = { startNeuroAwake() },
                    onMute = { 
                        sendServiceAction(IntentGateService.ACTION_EMERGENCY_MUTE)
                        isAwakeActiveState.value = false
                    }
                )
            }
        }

        // Açılışta izinleri güvenle talep et
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun registerBluetoothSafe() {
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(bluetoothReceiver, filter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendServiceAction(action: String) {
        try {
            val intent = Intent(this, IntentGateService::class.java).apply { this.action = action }
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startNeuroAwake() {
        isAwakeActiveState.value = true
        stepCountState.intValue = 0
        
        try {
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            tts?.speak("Korteks kilidi devrede. On beş adım atana kadar sistem açık kalacak. Birinci adım.", "WAKE")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isAwakeActiveState.value) return
        stepCountState.intValue++
        
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {}

        if (stepCountState.intValue >= 15) {
            isAwakeActiveState.value = false
            sm?.unregisterListener(this)
            tts?.speak("On beş adım tamamlandı. Beyin uyandı. Harika bir gün olsun.", "WAKE_DONE")
        }
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}

    override fun onDestroy() {
        try { unregisterReceiver(bluetoothReceiver) } catch (e: Exception) {}
        try { sm?.unregisterListener(this) } catch (e: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun EchoNeuroDashboard(
    stepCount: Int,
    isAwakeActive: Boolean,
    onFocus: () -> Unit,
    onDopamine: () -> Unit,
    onAwake: () -> Unit,
    onMute: () -> Unit
) {
    var rawThought by remember { mutableStateOf("") }
    var microSteps by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0D)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Başlık
            Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(
                    text = "ECHO-OS",
                    color = Color(0xFFE2E8F0),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Nöro-İşletim Sistemi // AuDHD Shield",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Zihinsel Döküm & 3 Mikro-Adım Paneli
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131720)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ZİHİNSEL DÖKÜM (BRAIN DUMP)",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rawThought,
                        onValueChange = { rawThought = it },
                        placeholder = { Text("Zihnini kemiren karmaşık işi buraya yaz...", color = Color(0xFF475569), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (rawThought.isNotBlank()) {
                                microSteps = listOf(
                                    "1. Masaya geç ve sadece bir yudum su al" to false,
                                    "2. Dosyayı ya da ilgili ekranı aç (başlamak zorunda değilsin)" to false,
                                    "3. Yalnızca 2 dakika boyunca ilk cümleye odaklan" to false
                                )
                                rawThought = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text("3 MİKRO-ADIMA BÖL (FELÇ KIRICI)", fontSize = 12.sp, color = Color(0xFF38BDF8))
                    }

                    // Tıklanabilir Onay Kutuları
                    if (microSteps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        microSteps.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable {
                                        microSteps = microSteps.toMutableList().also {
                                            it[index] = it[index].copy(second = !it[index].second)
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.second,
                                    onCheckedChange = { isChecked ->
                                        microSteps = microSteps.toMutableList().also {
                                            it[index] = it[index].copy(second = isChecked)
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFF38BDF8),
                                        uncheckedColor = Color(0xFF64748B)
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = item.first,
                                    color = if (item.second) Color(0xFF64748B) else Color(0xFFE2E8F0),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Canlı Adım Göstergesi
            if (isAwakeActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF312E81))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("KORTEKS KİLİDİ: YÜRÜYÜN", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("$stepCount / 15 ADIM", color = Color(0xFF38BDF8), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                }
            }

            // Çekirdek Butonlar
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onFocus,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text("ODAK KALKANI (8D BROWN NOISE)", color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onDopamine,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text("SERBEST MOLA (ZAMAN KORUMASI)", color = Color(0xFFA855F7), fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onAwake,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("NEURO-AWAKE (15 ADIM TEST)", color = Color(0xFF94A3B8))
                }
            }

            // Acil Susturucu
            Button(
                onClick = onMute,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D))
            ) {
                Text("ACİL SUSTURUCU (MUTLAK SESSİZLİK)", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold)
            }
        }
    }
}
