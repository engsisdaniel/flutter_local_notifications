package com.dexterous.flutterlocalnotifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

public class RepeatTriggerCalculatorTest {
  private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
  private static final long DAY = 24L * 60 * 60 * 1000;
  private static final long HOUR = 60L * 60 * 1000;

  private static long at(int y, int m, int d, int h, int min) {
    return ZonedDateTime.of(y, m, d, h, min, 0, 0, LISBON).toInstant().toEpochMilli();
  }

  private static ZonedDateTime local(long millis) {
    return java.time.Instant.ofEpochMilli(millis).atZone(LISBON);
  }

  @Test
  public void intervaloZeroNaoTravaEViraDiario() {
    long now = at(2026, 5, 10, 12, 0);
    long next =
        RepeatTriggerCalculator.next(
            at(2026, 5, 1, 9, 0), now, 0, null, null, null, null, LISBON);
    assertEquals(at(2026, 5, 11, 9, 0), next);
  }

  @Test
  public void diasDaSemanaVazioValeTodosOsDias() {
    long now = at(2026, 5, 10, 12, 0);
    long next =
        RepeatTriggerCalculator.next(
            at(2026, 5, 1, 9, 0), now, DAY, null, new int[0], null, null, LISBON);
    assertEquals(at(2026, 5, 11, 9, 0), next);
  }

  @Test
  public void diarioMantemAHoraNaViradaDoHorarioDeVerao() {
    // Em Lisboa o horário de verão começa em 29/03/2026.
    long now = at(2026, 3, 30, 8, 0);
    long next =
        RepeatTriggerCalculator.next(
            at(2026, 3, 27, 9, 0), now, DAY, null, null, null, null, LISBON);
    assertEquals(9, local(next).getHour());
    assertEquals(30, local(next).getDayOfMonth());
  }

  @Test
  public void respeitaODiaDaSemana() {
    long now = at(2026, 5, 13, 12, 0); // quarta
    // 2 = segunda no formato do Calendar.
    long next =
        RepeatTriggerCalculator.next(
            at(2026, 5, 1, 9, 0), now, DAY, null, new int[] {2}, null, null, LISBON);
    assertEquals(DayOfWeek.MONDAY, local(next).getDayOfWeek());
    assertEquals(at(2026, 5, 18, 9, 0), next);
  }

  @Test
  public void respeitaAJanelaDeHorario() {
    long now = at(2026, 5, 10, 20, 30);
    long next =
        RepeatTriggerCalculator.next(
            at(2026, 5, 1, 8, 0), now, HOUR, null, null, "08:00", "18:00", LISBON);
    assertEquals(at(2026, 5, 11, 8, 0), next);
  }

  @Test
  public void mensalNoDia31NaoGrudaNo28() {
    long now = at(2026, 3, 1, 12, 0);
    long next =
        RepeatTriggerCalculator.next(
            at(2026, 1, 31, 9, 0), now, 0, "Monthly", null, null, null, LISBON);
    assertEquals(at(2026, 3, 31, 9, 0), next);
  }

  @Test
  public void primeiroDisparoNoFuturoEDevolvidoComoEsta() {
    long now = at(2026, 5, 10, 12, 0);
    long first = at(2026, 5, 10, 15, 0);
    assertEquals(
        first,
        RepeatTriggerCalculator.next(first, now, DAY, null, null, null, null, LISBON));
  }

  @Test
  public void intervaloCurtoComAncoraAntigaTerminaRapido() {
    long now = at(2026, 5, 10, 12, 0);
    long start = System.nanoTime();
    long next =
        RepeatTriggerCalculator.next(
            at(2020, 1, 1, 0, 0), now, 60_000, null, null, null, null, LISBON);
    assertTrue(next >= now && next - now <= 60_000);
    assertTrue("demorou demais", System.nanoTime() - start < 500_000_000L);
  }
}
