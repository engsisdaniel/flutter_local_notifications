package com.dexterous.flutterlocalnotifications.models;

import androidx.annotation.Keep;

import java.io.Serializable;
import java.util.Map;

/**
 * Som de alarme tocado pelo {@code AlarmSoundService} quando a notificação dispara.
 *
 * <p>Extensão deste fork: o som sai do lado nativo, no fluxo de alarme, para tocar mesmo quando a
 * tela de alarme do app não abre (fabricante bloqueia, celular em uso). Fica serializado junto com
 * a notificação no cache, para o boot e a cadeia de repetição preservarem o som.
 */
@Keep
public class AlarmSound implements Serializable {
  /** Caminho absoluto de um arquivo de áudio; tem prioridade sobre [asset]. */
  public String path;

  /** Chave de asset do Flutter (ex.: "assets/sounds/alarm.mp3"). */
  public String asset;

  /** Volume relativo ao volume de alarme do aparelho, de 0 a 1. */
  public Double volume;

  /** Tempo máximo tocando antes de parar sozinho. */
  public Integer maxDurationSeconds;

  public Boolean vibrate;

  /** Rótulo da ação de parar na notificação, já traduzido pelo app. */
  public String stopLabel;

  public static AlarmSound from(Map<String, Object> map) {
    AlarmSound sound = new AlarmSound();
    sound.path = (String) map.get("path");
    sound.asset = (String) map.get("asset");
    Object volume = map.get("volume");
    sound.volume = volume instanceof Number ? ((Number) volume).doubleValue() : null;
    Object duration = map.get("maxDurationSeconds");
    sound.maxDurationSeconds = duration instanceof Number ? ((Number) duration).intValue() : null;
    sound.vibrate = (Boolean) map.get("vibrate");
    sound.stopLabel = (String) map.get("stopLabel");
    return sound;
  }
}
