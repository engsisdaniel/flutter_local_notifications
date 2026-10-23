package com.dexterous.flutterlocalnotifications;

import android.util.Log;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Calcula o próximo disparo de uma notificação repetida a partir do primeiro disparo.
 *
 * <p>Extraído do plugin para ser testável e para corrigir três problemas da versão anterior:
 *
 * <ul>
 *   <li>Intervalo zero ou lista de dias vazia deixavam o laço infinito (ANR no receiver e no boot,
 *       e nenhum alarme seguinte era agendado).
 *   <li>Intervalos múltiplos de um dia somavam milissegundos fixos e deslocavam uma hora a cada
 *       virada de horário de verão; agora avançam pelo calendário no fuso atual, mantendo a hora de
 *       relógio.
 *   <li>Um primeiro disparo antigo com intervalo curto fazia o laço andar de minuto em minuto por
 *       anos; agora salta direto para perto de agora antes de conferir dias e janela.
 * </ul>
 */
final class RepeatTriggerCalculator {
  private static final String TAG = "RepeatTriggerCalc";
  private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

  /** Limite de passos depois do salto inicial; janela + dias da semana cabem com folga. */
  static final int MAX_ITERATIONS = 200_000;

  private RepeatTriggerCalculator() {}

  /**
   * Próximo disparo (epoch millis) no futuro, dentro dos dias e da janela.
   *
   * @param firstTrigger primeiro disparo agendado (âncora da série)
   * @param repeatMillis intervalo em milissegundos; ignorado quando [customRepeatInterval] é
   *     "Monthly"/"Annually"
   * @param daysOfWeek dias permitidos no formato de {@link java.util.Calendar} (1 = domingo); nulo
   *     ou vazio = todos
   * @param startTime início da janela "HH:mm"; com [endTime], restringe o horário
   */
  static long next(
      long firstTrigger,
      long now,
      long repeatMillis,
      String customRepeatInterval,
      int[] daysOfWeek,
      String startTime,
      String endTime,
      ZoneId zone) {
    final boolean monthly = "Monthly".equals(customRepeatInterval);
    final boolean annually = "Annually".equals(customRepeatInterval);
    // Intervalo inválido vira diário: melhor alertar todo dia do que travar o receiver.
    if (!monthly && !annually && repeatMillis <= 0) repeatMillis = DAY_MILLIS;
    final int[] days = daysOfWeek != null && daysOfWeek.length == 0 ? null : daysOfWeek;
    final boolean calendarDays = !monthly && !annually && repeatMillis % DAY_MILLIS == 0;
    final long stepDays = repeatMillis / DAY_MILLIS;

    ZonedDateTime trigger = Instant.ofEpochMilli(firstTrigger).atZone(zone);
    final ZonedDateTime anchor = trigger;
    long steps = 0;

    // --- Salto até perto de agora ---
    if (trigger.toInstant().toEpochMilli() < now) {
      long behind = now - trigger.toInstant().toEpochMilli();
      if (monthly) {
        steps = Math.max(0, behind / (31 * DAY_MILLIS));
        trigger = anchor.plusMonths(steps);
      } else if (annually) {
        steps = Math.max(0, behind / (366 * DAY_MILLIS));
        trigger = anchor.plusYears(steps);
      } else if (calendarDays) {
        steps = Math.max(0, behind / repeatMillis);
        trigger = anchor.plusDays(steps * stepDays);
      } else {
        steps = Math.max(0, behind / repeatMillis);
        trigger = anchor.plusNanos(0).plusSeconds(steps * (repeatMillis / 1000));
      }
    }

    // --- Avanço passo a passo até um horário válido ---
    for (int i = 0; i < MAX_ITERATIONS; i++) {
      long millis = trigger.toInstant().toEpochMilli();
      if (millis >= now && isValidDay(trigger, days) && isInWindow(trigger, startTime, endTime)) {
        return millis;
      }
      steps++;
      // Mensal e anual partem sempre da âncora: somar um mês por vez a partir do dia 31
      // grudaria no dia 28 depois de fevereiro.
      if (monthly) {
        trigger = anchor.plusMonths(steps);
      } else if (annually) {
        trigger = anchor.plusYears(steps);
      } else if (calendarDays) {
        trigger = anchor.plusDays(steps * stepDays);
      } else {
        trigger = trigger.plusSeconds(repeatMillis / 1000);
      }
    }

    Log.e(TAG, "Sem horário válido após " + MAX_ITERATIONS + " passos; adiando um dia.");
    return now + DAY_MILLIS;
  }

  static boolean isValidDay(ZonedDateTime time, int[] daysOfWeek) {
    if (daysOfWeek == null) return true;
    // Calendar: 1 = domingo ... 7 = sábado. java.time: 1 = segunda ... 7 = domingo.
    int calendarDay = time.getDayOfWeek().getValue() % 7 + 1;
    for (int d : daysOfWeek) {
      if (d == calendarDay) return true;
    }
    return false;
  }

  static boolean isInWindow(ZonedDateTime time, String startTime, String endTime) {
    if (startTime == null || endTime == null) return true;
    LocalTime start = LocalTime.parse(startTime);
    LocalTime end = LocalTime.parse(endTime);
    LocalTime t = time.toLocalTime().withSecond(0).withNano(0);
    if (!end.isBefore(start)) {
      return !t.isBefore(start) && !t.isAfter(end);
    }
    // Janela que atravessa a meia-noite (ex.: 22:00–06:00).
    return !t.isBefore(start) || !t.isAfter(end);
  }
}
