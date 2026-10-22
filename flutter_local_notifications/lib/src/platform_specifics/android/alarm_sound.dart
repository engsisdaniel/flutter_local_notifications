/// Som de alarme tocado no lado nativo quando a notificação dispara.
///
/// Extensão deste fork. O som sai de um foreground service no fluxo de
/// alarme (`USAGE_ALARM`), em loop, e não depende de a tela do app abrir: em
/// fabricantes que bloqueiam abrir activities em segundo plano o alarme
/// continua tocando. A notificação fica fixa com a ação de parar até
/// [maxDurationSeconds]; depois vira uma notificação comum.
///
/// A fonte é escolhida nesta ordem: [path], [asset] e, se nenhum abrir, o
/// alarme padrão do aparelho.
class AndroidAlarmSound {
  const AndroidAlarmSound({
    this.path,
    this.asset,
    this.volume = 1,
    this.maxDurationSeconds = 120,
    this.vibrate = true,
    this.stopLabel,
  });

  /// Caminho absoluto de um arquivo de áudio no aparelho.
  final String? path;

  /// Chave de asset do Flutter, como declarada no `pubspec.yaml`.
  final String? asset;

  /// Volume relativo ao volume de alarme do aparelho, de 0 a 1.
  final double volume;

  final int maxDurationSeconds;

  final bool vibrate;

  /// Rótulo da ação "Parar" na notificação, já traduzido.
  final String? stopLabel;

  Map<String, Object?> toMap() => <String, Object?>{
    'path': path,
    'asset': asset,
    'volume': volume,
    'maxDurationSeconds': maxDurationSeconds,
    'vibrate': vibrate,
    'stopLabel': stopLabel,
  };
}
