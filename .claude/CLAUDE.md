# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## O que é

Fork de [MaikuB/flutter_local_notifications](https://github.com/MaikuB/flutter_local_notifications) mantido para o app **Me Lembra**. O código é do upstream; o fork só **acrescenta** o que o Me Lembra usa.

**Regra principal: mexer o mínimo no código do upstream.** Cada linha alterada é um conflito em potencial no próximo merge de `upstream/master`. Por isso:
- prefira acrescentar (parâmetro opcional novo, método auxiliar novo, constante nova) a reescrever trecho existente;
- não reformate, não renomeie, não reordene nem "melhore" código do upstream que não precisa mudar;
- todo parâmetro novo é **opcional e com default que mantém o comportamento original**, para que chamadas do upstream (e os testes dele) continuem valendo;
- siga o estilo do arquivo (inglês nos identificadores, formatação do upstream). Comentários das adições do fork podem ser em português, como os já existentes.

## Sincronizar com o upstream

O remoto `upstream` aponta para o repositório original (se faltar: `git remote add upstream https://github.com/MaikuB/flutter_local_notifications.git`):

```bash
git fetch upstream
git merge upstream/master          # o histórico do fork usa merge, não rebase
```

Para ver tudo o que o fork diverge do upstream: `git diff upstream/master...HEAD --stat`.

Depois do merge, confira se essa lista continua a mesma e rode análise e testes. Rodar `flutter pub get` fora do Melos reescreve `example/analysis_options.yaml`; desfaça antes de commitar.

Os 12 testes Dart de `periodicallyShow` (Android, iOS e macOS) **já falham por causa do fork**: o mapa esperado pelo upstream não tem as chaves novas, e no Android o `calledAt` não segue o relógio congelado. Uma falha fora desse grupo é regressão de verdade.

## Comandos

Monorepo gerenciado por **Melos** (`melos.yaml`). Não rode `flutter pub get` nos pacotes: o bootstrap liga os pacotes locais entre si.

```bash
dart pub global activate melos
melos bootstrap

melos run analyze                  # dart analyze --fatal-infos (escolhe o pacote)
melos run format                   # dart format -o write .
melos run format-ci                # o que o CI confere
melos run test:unit                # flutter test em todos os pacotes com test/ (menos Windows)
melos run test:unit:android        # testes Java (compila o APK do example antes)
melos run build:example_ios        # também: build:example_android / _macos / _linux

# Um arquivo ou um teste, dentro do pacote
cd flutter_local_notifications
flutter test test/ios_flutter_local_notifications_test.dart
flutter test --plain-name 'periodicallyShow'
```

Formatação nativa é conferida pelo workflow `.github/workflows/format.yml`, que roda em **todo push** e, se encontrar diferença, **commita e dá push sozinho** (é de onde vêm os commits "Clang Format" / "Swift Format"). Para evitar isso, formate antes do commit:

```bash
find . -name '*.m' -exec clang-format -i {} \;      # Objective-C (iOS)
swiftlint --fix                                     # Swift (macOS)
# Java: google-java-format --skip-sorting-imports --replace <arquivo>
```

## Arquitetura (upstream)

Pacotes federados:
- `flutter_local_notifications_platform_interface` — classe abstrata `FlutterLocalNotificationsPlatform`.
- `flutter_local_notifications` — fachada `FlutterLocalNotificationsPlugin` (`lib/src/flutter_local_notifications_plugin.dart`) e as implementações por plataforma em `lib/src/platform_flutter_local_notifications.dart` (`AndroidFlutterLocalNotificationsPlugin`, `IOSFlutterLocalNotificationsPlugin`, `MacOSFlutterLocalNotificationsPlugin`), que falam com o nativo por `MethodChannel`.
- `flutter_local_notifications_linux` e `flutter_local_notifications_windows` — implementações separadas (o fork não mexe nelas).

Código nativo:
- Android: `flutter_local_notifications/android/src/main/java/com/dexterous/flutterlocalnotifications/` — `FlutterLocalNotificationsPlugin.java` (agendamento via `AlarmManager`) e `models/NotificationDetails.java` (desserializa o mapa do channel).
- iOS: `flutter_local_notifications/ios/.../FlutterLocalNotificationsPlugin.m` (Objective-C).
- macOS: `flutter_local_notifications/macos/.../FlutterLocalNotificationsPlugin.swift`.

Uma feature que atravessa plataformas sempre toca a mesma cadeia: fachada Dart → classe da plataforma em Dart (monta o mapa do channel) → chave constante no nativo → leitura e uso no nativo. A chave do mapa (`'repeatMinutes'`, `'daysOfWeek'`…) precisa ser idêntica nos quatro lugares.

O `CONTRIBUTING.md` do upstream pede que recurso específico de plataforma não entre na fachada multiplataforma — vá em configuração da plataforma ou em método da classe da plataforma. Os testes Dart (`flutter_local_notifications/test/`) conferem principalmente **o mapa enviado pelo channel**.

## O que o fork adiciona

Tudo gira em torno de `periodicallyShow` (notificação repetida). Parâmetros opcionais novos:

| Parâmetro | Chave no channel | Significado |
|---|---|---|
| `initialDate` | `calledAt` | início da contagem (default: agora) |
| `repeatMinutes` | `repeatMinutes` | intervalo em minutos; tem precedência sobre `repeatIntervalMilliseconds` e `repeatInterval` |
| `customRepeatInterval` | `customRepeatInterval` | `"Monthly"` / `"Annually"` (soma mês/ano no calendário); outro valor cai no intervalo normal |
| `daysOfWeek` | `daysOfWeek` | dias permitidos, `1..7` com **1 = domingo** (`Calendar.DAY_OF_WEEK` / `NSCalendarUnitWeekday`); default todos |
| `startTime` / `endTime` | idem | janela `"HH:mm"` em que pode disparar; aceita janela que cruza a meia-noite; default `00:00`–`23:59` |

Onde está:
- **Dart**: fachada `FlutterLocalNotificationsPlugin.periodicallyShow` e os `periodicallyShow` das três classes de plataforma.
- **Android**: `calculateNextNotificationTrigger` avança o disparo até cair no futuro, num dia permitido e dentro da janela (`isOnValidWeekDay`, `isOnValidInterval`); `calculateRepeatIntervalMilliseconds` lê `repeatMinutes`. O reagendamento a cada disparo passa pelo mesmo cálculo, então as restrições valem em toda repetição.
- **iOS/macOS**: mesmos auxiliares portados (`calculateNextNotificationTrigger`, `calculateRepeatIntervalSeconds`, `isOnValidWeekDay`, `isOnValidInterval`). Só entram quando há restrição ativa; sem restrição, o caminho do upstream fica intacto.

Divergências deliberadas ou conhecidas — confira antes de "consertar":
- No Android, o fallback de `scheduleMode` ausente (dados antigos serializados) foi trocado de `inexact`/`exact` para `exactAllowWhileIdle`, nos dois pontos que tratam isso.
- Na fachada, os novos parâmetros **só são repassados ao Android**; os ramos iOS e macOS chamam `periodicallyShow` sem eles. Para o Me Lembra usá-los no iOS/macOS é preciso chamar a classe da plataforma via `resolvePlatformSpecificImplementation`, ou repassá-los na fachada.
- No iOS/macOS o resultado é um `UNTimeIntervalNotificationTrigger` repetindo com o intervalo até o *primeiro* disparo válido: o sistema não reavalia dia da semana e janela nas repetições seguintes (limitação reconhecida no comentário do código).
- O `periodicallyShow` do Android usa `DateTime.now()` para o default de `calledAt`, enquanto iOS/macOS usam `clock.now()` como o upstream — testes que congelam o relógio com `package:clock` não pegam o Android.
- Arquivos `.project` / `.settings` do Eclipse e um `gradle-wrapper.properties` em `flutter_local_notifications/android` e no `example/android` foram commitados por engano do IDE; não fazem parte da feature.

## Commits

Mensagens em português, no mesmo formato do projeto Megleo: `[<branch>] <categoria>: <resumo no imperativo, minúsculo, sem ponto final>`, com categoria `feat`, `fix`, `refactor`, `test`, `docs`, `build` ou `chore`. No corpo, os blocos `Motivo:`, `Mudança:` e `Impacto:` — no `Impacto`, diga quais plataformas foram afetadas e se a mudança aumenta a área de conflito com o upstream.
