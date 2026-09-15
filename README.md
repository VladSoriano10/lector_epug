# Lector EPUB · Android

Primera versión personal para importar EPUB y escuchar su texto con un motor TTS instalado. Android 8 o superior; dispositivo previsto para prueba: Redmi Note 11 con Android 13.

## Obtener el APK sin Android Studio

1. Abre **Actions → Compilar APK** en este repositorio.
2. Entra en una ejecución terminada con marca verde.
3. En **Artifacts**, descarga **lector-epub-apk** (inicia sesión en GitHub).
4. Extrae el ZIP e instala `app-debug.apk` en el teléfono. Autoriza la instalación desde el navegador o gestor de archivos cuando Android lo solicite.

El APK es de prueba y no se publica en Google Play. Las compilaciones de prueba usan la clave debug del ejecutor: entre ejecuciones puede cambiar. Si Android rechaza una actualización por firma distinta, hará falta desinstalar la versión anterior, lo que elimina su biblioteca y progreso. Conserva tus EPUB originales. Para uso continuado, falta configurar una clave de firma estable privada.

## Uso

- **Importar**: selecciona un EPUB sin DRM. Se copia a los datos privados de la app; no necesitas conceder acceso a todo el almacenamiento.
- **Biblioteca**: vuelve a abrir los libros importados. Importar de nuevo el mismo archivo conserva el progreso.
- **Escuchar / Pausar**, capítulos y avance/retroceso por fragmentos. Pausar y reanudar repite el fragmento actual (máximo 700 caracteres), no la palabra exacta.
- **Voces**: selecciona un motor instalado y después una voz local en español. Los idiomas distintos de español y las voces que declaran necesitar red no aparecen. Las variantes distintas de España se muestran primero.
- **Velocidad**: de 0.75× a 2×.
- La reproducción usa un servicio de primer plano, notificación, sesión multimedia y bloqueo parcial de CPU durante la lectura. Se pausa al perder foco de audio o desconectar auriculares. No reinicia la lectura automáticamente después de que Android cierre el proceso.

## Voces y licencias: estado real de esta versión

Esta app **no incluye ninguna voz ni un modelo Piper**. Utiliza la interfaz TTS de Android, por lo que puedes añadir motores compatibles sin modificar el lector. Las voces que ya traiga tu teléfono pueden ser propietarias; aparecer como voz local no significa tener licencia abierta.

[RHVoice](https://github.com/RHVoice/RHVoice) es un candidato de motor abierto; su [aplicación para Android](https://play.google.com/store/apps/details?id=com.github.olga_yakovleva.rhvoice.android) anuncia español castellano, latinoamericano y mexicano. Instala y descarga las voces desde la aplicación del motor; después selecciónalo en **Voces → Elegir motor instalado** y elige una voz en **Elegir voz sin conexión**. La ficha de RHVoice indica que algunas voces son de pago. **La selección de una voz latinoamericana con licencia abierta concreta aún está pendiente de verificar**; no se afirma que todas las voces del catálogo sean abiertas o gratuitas.

Los libros y el progreso se guardan localmente. El lector no solicita permiso de Internet, pero el motor TTS es otra aplicación: prueba en modo avión una vez descargada la voz para verificar su funcionamiento local.

## Límites de la primera versión

- Solo EPUB con texto, sin DRM; no PDF, OCR, cómics ni diseño fijo.
- Presentación de texto simplificada: fragmento actual y contexto cercano, sin imágenes, tablas ni maquetación original.
- Límite de archivo importado: 100 MB; capítulo: 4 MB; contenido HTML acumulado: 16 MB.
- Lectura según el `spine` del EPUB, no según el orden de los archivos ZIP. Omite contenido marcado como no lineal y recursos que no son HTML.
- Pendientes: modelos de voz integrados/importables, selección de voz abierta LATAM verificada, firma estable, eliminación de libros, temporizador y pruebas físicas de batería/pantalla bloqueada en MIUI.

## Compilar y verificar

El flujo `.github/workflows/android.yml` instala JDK 17, Android SDK y Gradle 8.11.1 en GitHub Actions. Ejecuta:

```sh
gradle testDebugUnitTest lintDebug assembleDebug --no-daemon
```

También puedes importar el proyecto en Android Studio o ejecutar ese comando con Gradle 8.11.1 y Android SDK 35 instalados. El repositorio todavía no incluye Gradle Wrapper; la versión de Gradle se fija en Actions.

Las pruebas JVM comprueban orden de capítulos, entidades/acentos, exclusión de scripts, límites de tamaño, referencias externas y fragmentación sin perder texto ni romper caracteres Unicode.

Pruebas manuales necesarias en Redmi Note 11:

1. Importar un EPUB, leer varios fragmentos y cambiar de capítulo.
2. Pausar, cerrar y reabrir: debe conservar el capítulo y fragmento.
3. Descargar la voz, activar modo avión y escuchar.
4. Bloquear pantalla durante 10 minutos y usar los controles de notificación.
5. Desconectar auriculares o reproducir audio en otra app: debe pausar.
6. Si MIUI interrumpe la reproducción, revisar la restricción de batería de esta app y del motor de voz.

Dependencia de extracción HTML: [jsoup](https://jsoup.org/), licencia MIT. No se redistribuyen voces ni libros.
