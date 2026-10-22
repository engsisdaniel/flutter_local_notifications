package com.dexterous.flutterlocalnotifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.dexterous.flutterlocalnotifications.models.AlarmSound;
import com.dexterous.flutterlocalnotifications.models.NotificationDetails;

import java.io.File;
import java.util.Arrays;

/**
 * Toca o som de um alarme no lado nativo, em loop, no fluxo de alarme.
 *
 * <p>Extensão deste fork. Antes o som era tocado pelo app Flutter e só quando a tela de alarme
 * abria; em fabricantes que bloqueiam a abertura em segundo plano (Xiaomi/POCO, entre outros), com
 * o celular em uso ou com o app já aberto, o alarme ficava mudo. Aqui o som não depende de tela:
 *
 * <ul>
 *   <li>{@link AudioAttributes#USAGE_ALARM}: segue o volume de alarme, não o de mídia;
 *   <li>foreground service com a própria notificação do alarme (ação "Parar" e full-screen intent);
 *   <li>para sozinho depois de {@link AlarmSound#maxDurationSeconds}, deixando a notificação comum.
 * </ul>
 *
 * <p>É iniciado pelo {@link ScheduledNotificationReceiver} dentro do broadcast do alarme exato, o
 * que o Android 12+ aceita como exceção para iniciar foreground service em segundo plano.
 */
@Keep
public class AlarmSoundService extends Service {
  private static final String TAG = "AlarmSoundService";
  static final String ACTION_START = "com.dexterous.flutterlocalnotifications.ALARM_SOUND_START";
  static final String ACTION_STOP = "com.dexterous.flutterlocalnotifications.ALARM_SOUND_STOP";
  private static final String EXTRA_DETAILS = "details";
  private static final int DEFAULT_MAX_SECONDS = 120;
  private static final long[] VIBRATION_PATTERN = {0, 800, 800};

  /** Notificação do alarme tocando agora; nulo quando nada toca. Lido pelo plugin. */
  private static volatile NotificationDetails playing;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable timeout = () -> stopAlarm(false);

  private MediaPlayer player;
  private Vibrator vibrator;
  private PowerManager.WakeLock wakeLock;
  private AudioFocusRequest focusRequest;

  /** Inicia o som; devolve falso se o sistema recusar o foreground service. */
  static boolean start(Context context, NotificationDetails details) {
    Intent intent = new Intent(context, AlarmSoundService.class);
    intent.setAction(ACTION_START);
    intent.putExtra(EXTRA_DETAILS, FlutterLocalNotificationsPlugin.buildGson().toJson(details));
    try {
      ContextCompat.startForegroundService(context, intent);
      return true;
    } catch (Exception e) {
      // ForegroundServiceStartNotAllowedException e afins: quem chama mostra a notificação comum.
      Log.e(TAG, "Não foi possível iniciar o som do alarme", e);
      return false;
    }
  }

  /** Para o som e remove a notificação do alarme. Sem efeito se nada estiver tocando. */
  static void stop(Context context) {
    if (playing == null) return;
    Intent intent = new Intent(context, AlarmSoundService.class);
    intent.setAction(ACTION_STOP);
    try {
      context.startService(intent);
    } catch (Exception e) {
      Log.e(TAG, "Não foi possível parar o som do alarme", e);
    }
  }

  @Nullable
  static NotificationDetails playing() {
    return playing;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || ACTION_STOP.equals(intent.getAction())) {
      stopAlarm(true);
      return START_NOT_STICKY;
    }

    String json = intent.getStringExtra(EXTRA_DETAILS);
    NotificationDetails details =
        json == null
            ? null
            : FlutterLocalNotificationsPlugin.buildGson().fromJson(json, NotificationDetails.class);
    if (details == null || details.alarmSound == null) {
      stopSelf();
      return START_NOT_STICKY;
    }

    // Um alarme novo substitui o que estiver tocando; o anterior fica como notificação comum.
    if (playing != null) {
      releasePlayback();
      postFinalNotification(playing);
    }

    startForegroundCompat(details.id, buildOngoingNotification(details));
    playing = details;
    startPlayback(details.alarmSound);

    int seconds =
        details.alarmSound.maxDurationSeconds != null
            ? details.alarmSound.maxDurationSeconds
            : DEFAULT_MAX_SECONDS;
    handler.removeCallbacks(timeout);
    handler.postDelayed(timeout, seconds * 1000L);
    // O serviço não deve voltar sozinho depois de morto: um alarme atrasado tocando do nada é pior.
    return START_NOT_STICKY;
  }

  private void startForegroundCompat(int id, Notification notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    } else {
      startForeground(id, notification);
    }
  }

  /** A notificação do alarme, fixa enquanto toca e com a ação "Parar". */
  private Notification buildOngoingNotification(NotificationDetails details) {
    Notification notification = FlutterLocalNotificationsPlugin.createNotification(this, details);
    notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;

    Intent stopIntent = new Intent(this, AlarmSoundService.class).setAction(ACTION_STOP);
    PendingIntent stopPending =
        PendingIntent.getService(
            this,
            details.id,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String label =
        details.alarmSound.stopLabel != null ? details.alarmSound.stopLabel : "Stop";
    Notification.Action stop =
        new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_lock_idle_alarm),
                label,
                stopPending)
            .build();
    Notification.Action[] actions =
        notification.actions == null
            ? new Notification.Action[0]
            : Arrays.copyOf(notification.actions, notification.actions.length);
    Notification.Action[] withStop = Arrays.copyOf(actions, actions.length + 1);
    withStop[actions.length] = stop;
    notification.actions = withStop;
    return notification;
  }

  /** Notificação comum (sem tela cheia nem som) que fica depois que o alarme parou sozinho. */
  private void postFinalNotification(NotificationDetails details) {
    try {
      Boolean fullScreen = details.fullScreenIntent;
      details.fullScreenIntent = false;
      Notification notification = FlutterLocalNotificationsPlugin.createNotification(this, details);
      details.fullScreenIntent = fullScreen;
      NotificationManagerCompat.from(this).notify(details.id, notification);
    } catch (SecurityException e) {
      // Permissão de notificação revogada no meio do alarme: não há o que mostrar.
      Log.w(TAG, "Sem permissão para a notificação final", e);
    }
  }

  // ---------------------------------------------------------------------

  private void startPlayback(AlarmSound sound) {
    acquireWakeLock(sound);
    requestAudioFocus();

    player = new MediaPlayer();
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build());
    player.setLooping(true);

    // Arquivo da tarefa, depois asset do app, depois o alarme padrão do aparelho: o alarme nunca
    // fica mudo por um arquivo apagado ou corrompido.
    if (!(trySetFile(sound.path) || trySetAsset(sound.asset) || trySetDefaultAlarm())) {
      Log.e(TAG, "Nenhuma fonte de som disponível");
      return;
    }

    float volume = sound.volume == null ? 1f : (float) Math.max(0, Math.min(1, sound.volume));
    player.setVolume(volume, volume);
    try {
      player.prepare();
      player.start();
    } catch (Exception e) {
      Log.e(TAG, "Falha ao tocar o som do alarme", e);
    }

    if (sound.vibrate == null || sound.vibrate) startVibration();
  }

  private boolean trySetFile(@Nullable String path) {
    if (path == null || !new File(path).exists()) return false;
    try {
      player.setDataSource(path);
      return true;
    } catch (Exception e) {
      Log.w(TAG, "Arquivo de som inválido: " + path, e);
      player.reset();
      return false;
    }
  }

  private boolean trySetAsset(@Nullable String asset) {
    if (asset == null) return false;
    // Caminho dos assets do Flutter dentro do APK. Montado na mão porque o FlutterLoader pode não
    // estar inicializado quando o alarme dispara sem o app aberto.
    try (AssetFileDescriptor fd = getAssets().openFd("flutter_assets/" + asset)) {
      player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
      return true;
    } catch (Exception e) {
      Log.w(TAG, "Asset de som inválido: " + asset, e);
      player.reset();
      return false;
    }
  }

  private boolean trySetDefaultAlarm() {
    Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
    if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    if (uri == null) return false;
    try {
      player.setDataSource(this, uri);
      return true;
    } catch (Exception e) {
      Log.w(TAG, "Alarme padrão indisponível", e);
      player.reset();
      return false;
    }
  }

  private void startVibration() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      VibratorManager manager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
      vibrator = manager == null ? null : manager.getDefaultVibrator();
    } else {
      vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }
    if (vibrator == null || !vibrator.hasVibrator()) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      vibrator.vibrate(
          VibrationEffect.createWaveform(VIBRATION_PATTERN, 0),
          new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
    } else {
      vibrator.vibrate(VIBRATION_PATTERN, 0);
    }
  }

  private void acquireWakeLock(AlarmSound sound) {
    PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
    if (power == null) return;
    int seconds = sound.maxDurationSeconds != null ? sound.maxDurationSeconds : DEFAULT_MAX_SECONDS;
    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flutterlocalnotifications:alarm");
    wakeLock.acquire((seconds + 10) * 1000L);
  }

  /** Pausa música ou podcast enquanto o alarme toca. */
  private void requestAudioFocus() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    if (audio == null) return;
    focusRequest =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            .build();
    audio.requestAudioFocus(focusRequest);
  }

  private void releasePlayback() {
    handler.removeCallbacks(timeout);
    if (player != null) {
      try {
        player.stop();
      } catch (IllegalStateException ignored) {
        // Ainda não tinha começado a tocar (prepare falhou); basta liberar.
      }
      player.release();
      player = null;
    }
    if (vibrator != null) {
      vibrator.cancel();
      vibrator = null;
    }
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    wakeLock = null;
    if (focusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
      if (audio != null) audio.abandonAudioFocusRequest(focusRequest);
      focusRequest = null;
    }
  }

  /**
   * Para o som. Com [byUser], a notificação some; sem (tempo esgotado), ela fica como notificação
   * comum para o usuário ver que o alarme tocou.
   */
  private void stopAlarm(boolean byUser) {
    NotificationDetails details = playing;
    releasePlayback();
    playing = null;
    stopForeground(STOP_FOREGROUND_REMOVE);
    if (details != null && !byUser) postFinalNotification(details);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    releasePlayback();
    playing = null;
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
