# Lector EPUB · Android

Versión 0.3: icono adaptativo inspirado en el diseño del usuario, detección ampliada de portadas y giro breve de página. Biblioteca y lector paginado con ilustraciones, para leer o escuchar EPUB con un motor TTS instalado. Android 8 o superior; dispositivo de prueba: Redmi Note 11 con Android 13. El usuario confirmó que la voz de la versión 0.1 funciona en su teléfono.

Las portadas se buscan mediante EPUB 3, metadatos EPUB 2, guía de portada, nombres habituales y primera sección. Se resuelven envoltorios HTML/SVG hacia imágenes locales; los libros ya importados se revisan una vez al actualizar. Las portadas puramente vectoriales (sin imagen raster) todavía no tienen miniatura. La transición manual dentro de una sección dura 180 ms y respeta la preferencia de movimiento reducido del WebView; los saltos de capítulo, índice y voz son inmediatos.

## Obtener el APK sin Android Studio

1. Abre **Actions → Compilar APK** en este repositorio.
2. Entra en una ejecución terminada con marca verde.
3. En **Artifacts**, descarga **lector-epub-apk** (inicia sesión en GitHub).
4. Extrae el ZIP e instala `app-debug.apk` en el teléfono. Autoriza la instalación desde el navegador o gestor de archivos cuando Android lo solicite.

El APK es de prueba y no se publica en Google Play. Las compilaciones de prueba usan la clave debug del ejecutor: entre ejecuciones puede cambiar. Si Android rechaza una actualización por firma distinta, hará falta desinstalar la versión anterior, lo que elimina su biblioteca y progreso. Conserva tus EPUB originales. Para uso continuado, falta configurar una clave de firma estable privada.

## Uso

- **Importar**: selecciona un EPUB sin DRM. Se copia a los datos privados de la app; no necesitas conceder acceso a todo el almacenamiento.
- **Biblioteca inicial**: tarjetas con portada cuando está declarada en el EPUB, título, autor y progreso aproximado por secciones. Abre un libro para entrar en la vista de lectura. Importar el mismo archivo no crea duplicados.
- **Abrir con / Compartir**: registrado para EPUB y tipos ZIP/binario genéricos que usan algunos gestores y mensajerías. Acepta `ACTION_VIEW` y `ACTION_SEND`, copia el archivo mientras conserva el permiso temporal y lo añade a la biblioteca. La disponibilidad de la opción depende del tipo MIME y de los permisos que entregue WhatsApp o la app de origen. Un ZIP genérico se valida como EPUB antes de guardarse.
- **Lectura a pantalla completa**: desliza horizontalmente para pasar página; toca para mostrar u ocultar controles superpuestos. También hay botones y deslizador de páginas por sección. El menú no reduce el espacio de paginación.
- **Posición**: guarda sección, elemento y desplazamiento dentro del texto. Cambiar tamaño de letra u orientación recalcula las páginas conservando el contenido de referencia. Las páginas mostradas son de la sección actual: su número cambia con el tamaño de pantalla y fuente.
- **Índice**: utiliza navegación EPUB 3 o NCX EPUB 2, incluidos enlaces a apartados dentro de una misma sección. Si falta el índice, utiliza el orden `spine`.
- **Imágenes interiores**: muestra imágenes locales JPG, PNG, GIF, WebP, SVG como recurso y envoltorios SVG con una imagen raster. Las láminas sin texto se conservan visualmente y la voz pasa a la siguiente sección con texto. No aplica OCR a imágenes.
- **Modo oscuro y letra**: tema persistente en biblioteca y lectura; tamaño entre 16 y 34. Desde el lector toca **Aa**. En la biblioteca usa el botón de luna.
- **Escuchar / Pausar**: mantiene motor, voz, velocidad y reproducción de fondo. Al pasar de página manualmente se pausa y se prepara la lectura desde el primer texto visible. Reanudar tras una pausa de voz puede repetir el fragmento actual, no garantiza la palabra exacta.
- **Voces**: selecciona un motor instalado y después una voz local en español. Los idiomas distintos de español y las voces que declaran necesitar red no aparecen. Las variantes distintas de España se muestran primero.
- **Velocidad**: de 0.75× a 2×.
- La reproducción usa un servicio de primer plano, notificación, sesión multimedia y bloqueo parcial de CPU durante la lectura. Se pausa al perder foco de audio o desconectar auriculares. No reinicia la lectura automáticamente después de que Android cierre el proceso.

## Voces y licencias: estado real de esta versión

Esta app **no incluye ninguna voz ni un modelo Piper**. Utiliza la interfaz TTS de Android, por lo que puedes añadir motores compatibles sin modificar el lector. Las voces que ya traiga tu teléfono pueden ser propietarias; aparecer como voz local no significa tener licencia abierta.

[RHVoice](https://github.com/RHVoice/RHVoice) es un candidato de motor abierto; su [aplicación para Android](https://play.google.com/store/apps/details?id=com.github.olga_yakovleva.rhvoice.android) anuncia español castellano, latinoamericano y mexicano. Instala y descarga las voces desde la aplicación del motor; después selecciónalo en **Voces → Elegir motor instalado** y elige una voz en **Elegir voz sin conexión**. La ficha de RHVoice indica que algunas voces son de pago. **La selección de una voz latinoamericana con licencia abierta concreta aún está pendiente de verificar**; no se afirma que todas las voces del catálogo sean abiertas o gratuitas.

Los libros y el progreso se guardan localmente. El lector no solicita permiso de Internet, pero el motor TTS es otra aplicación: prueba en modo avión una vez descargada la voz para verificar su funcionamiento local.

## Límites

- EPUB sin DRM; no PDF ni OCR. Reorganiza el contenido para adaptarlo a la pantalla; no reproduce la maquetación fija original.
- Conserva texto, ilustraciones y formato básico. Elimina scripts, formularios, estilos del editor y recursos remotos. Las tablas extensas y SVG vectoriales incrustados complejos pueden requerir ajustes; las ilustraciones superiores a 4 MB no se cargan.
- Límite de archivo importado: 100 MB; capítulo: 4 MB; contenido HTML acumulado: 16 MB.
- Lectura según el `spine` del EPUB, no según el orden de los archivos ZIP. Omite contenido marcado como no lineal y recursos que no son HTML.
- Pendientes: modelos de voz integrados/importables, selección de voz abierta LATAM verificada, firma estable, eliminación de libros y temporizador.

## Compilar y verificar

El flujo `.github/workflows/android.yml` instala JDK 17, Android SDK y Gradle 8.11.1 en GitHub Actions. Ejecuta:

```sh
gradle testDebugUnitTest lintDebug assembleDebug --no-daemon
```

También puedes importar el proyecto en Android Studio o ejecutar ese comando con Gradle 8.11.1 y Android SDK 35 instalados. El repositorio todavía no incluye Gradle Wrapper; la versión de Gradle se fija en Actions.

Las pruebas JVM comprueban orden de capítulos, entidades/acentos, exclusión de scripts, límites de tamaño, referencias externas, fragmentación sin romper Unicode, imágenes, secciones sin texto e índice con anclas.

`tests/pagination.cjs` verifica el motor visual en Chromium: pasar/restaurar páginas, cambio de fuente, tema, ajuste de ilustraciones, rotación y seguimiento de voz. Requiere Playwright y Chromium (`npm install --no-save playwright`, `npx playwright install chromium`, `node tests/pagination.cjs`).

Pruebas manuales necesarias en Redmi Note 11:

1. Importar un EPUB, leer varios fragmentos y cambiar de capítulo.
2. Pausar, cerrar y reabrir: debe conservar el capítulo y fragmento.
3. Descargar la voz, activar modo avión y escuchar.
4. Bloquear pantalla durante 10 minutos y usar los controles de notificación.
5. Desconectar auriculares o reproducir audio en otra app: debe pausar.
6. Si MIUI interrumpe la reproducción, revisar la restricción de batería de esta app y del motor de voz.
7. Abrir y compartir un EPUB desde WhatsApp; comprobar que reaparece al cerrar y abrir la app.
8. Abrir un libro ilustrado, deslizar a una imagen y comprobar modo oscuro, tamaño de letra, índice y restauración después de cerrar.

Dependencia de extracción HTML: [jsoup](https://jsoup.org/), licencia MIT. No se redistribuyen voces ni libros.
