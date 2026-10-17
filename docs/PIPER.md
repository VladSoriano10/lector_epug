# Piper local en VladER 0.5.0

En **Voces** selecciona **Piper · Claude** (México) o **Piper · DaveFX** (España).
Las dos voces están incluidas en la APK firmada. No requieren cuenta, API, pago,
instalación de otra app ni conexión. La primera lectura copia los recursos de la
voz al almacenamiento privado; puede tardar unos segundos. La selección se conserva.
**Voces de Android** devuelve la lectura al motor y voces anteriores.

Se usan los modelos Piper oficiales convertidos a ONNX para sherpa-onnx, en sus
variantes compactas int8: Claude high y DaveFX medium. El motor de ejecución es
sherpa-onnx 1.13.8 con eSpeak NG; no se instala el ejecutable Python de Piper.
Las tarjetas originales de las voces se incluyen en la APK sin modificar.

## Lectura y recursos

- Un solo modelo cargado y un hilo de inferencia en segundo plano con prioridad baja.
- Una frase de hasta 220 caracteres por síntesis y un único búfer corto de audio;
  no se convierte el capítulo o libro entero ni se guarda un audiolibro.
- Pausar detiene AudioTrack y conserva su posición exacta y el búfer mientras el
  proceso permanezca abierto. No se sigue sintetizando mientras está en pausa,
  salvo terminar la frase que ya estaba calculándose al tocar el botón.
- El seguimiento visual y la recuperación tras cerrar el proceso son por frases,
  porque los modelos no aportan alineación temporal por palabra. Un cierre del
  proceso puede repetir la última frase; una pausa normal conserva el audio exacto.
- Cambiar de libro, navegar manualmente o cambiar de voz descarta el audio antiguo.
  Cambiar la velocidad vuelve a sintetizar desde la frase actual.
- Continúan las notificaciones de pausa/reanudación, el foco de audio, EPUB, PDF,
  los avisos de ilustración y el tiempo configurable de espera.
- El uso real de CPU/batería depende del teléfono. No se afirma una mejora medida
  sobre las voces de Android. La APK y los archivos privados ocupan más espacio.

## Compilación reproducible

Antes de Gradle ejecuta `python3 scripts/prepare-piper.py` (Python 3.11 o posterior).
Los dos workflows ya lo hacen automáticamente. El script verifica SHA-256 de las
dos voces y del AAR; si cambia algún archivo remoto, la compilación falla en vez
de incluirlo silenciosamente. Los binarios descargados no se suben al repositorio.

El workflow de firma conserva el applicationId, los secretos de firma permanente
y el nombre VladER. Versión 0.5.0, versionCode 9.

## Fuentes y licencias

Consulta [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Los textos de licencia
tienen copia en `app/src/main/assets/licenses/` y se incluyen en la APK.
La combinación distribuida con eSpeak NG está sujeta a GPL-3.0; las licencias
permisivas de sus otros componentes y las condiciones de los modelos se conservan.
El código de esta integración y sus instrucciones de construcción están en este
repositorio. Los enlaces a continuación identifican las fuentes correspondientes
de los componentes nativos de terceros, incluidas las modificaciones usadas por
sherpa-onnx; consérvalos junto con los avisos si redistribuyes la APK.

- [sherpa-onnx v1.13.8, código e instrucciones Android](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8)
- [eSpeak NG, revisión ed530aa](https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1)
- [piper-phonemize, revisión f3ff95a](https://github.com/csukuangfj/piper-phonemize/tree/f3ff95afc03640bc1399e113e83361192a2fafb4)
- [Modelos convertidos y tarjetas](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)
- [Voces Piper originales](https://huggingface.co/rhasspy/piper-voices)

## Verificación

Las pruebas conservan los casos anteriores de EPUB, PDF, paginación, paneles,
ilustraciones y notificaciones. `PiperPlayerTest` sintetiza ambas voces reales
en Android 13 sin red y comprueba que la pausa conserva la posición del audio,
que reanudar no lo reinicia y que una cancelación no entrega eventos obsoletos.

Punto de retorno: `rollback/v0.4.1-antes-piper`, commit
`c8b5a84ce06e3a36297e7713f643ff4f23d4a81a`.
Para instalar una reversión sobre 0.5.0, conserva la clave permanente e incrementa
versionCode por encima de 9; no desinstales si quieres conservar la biblioteca.
