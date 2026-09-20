# Firma permanente de VladER (una sola vez)

La clave se crea y conserva en tu PC, nunca en este repositorio ni en el chat. No necesitas Android Studio: basta un JDK 17 con `keytool` disponible en PowerShell.

## 1. Crear y respaldar la clave

Abre PowerShell en una carpeta privada **fuera del repositorio**. No ejecutes el comando si ya tienes tu clave permanente: reutilízala.

```powershell
keytool -genkeypair -keystore vlader-release.p12 -storetype PKCS12 -alias vlader -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=VladER"
```

Introduce una contraseña fuerte cuando se solicite y guárdala en tu gestor de contraseñas. No la pongas en la línea de comandos. PKCS12 usa la misma contraseña para el almacén y la clave. Respalda `vlader-release.p12` y la contraseña en un lugar privado seguro: perder la clave impide firmar futuras actualizaciones compatibles. No generes otra clave para cada versión.

## 2. Guardar cuatro secretos en GitHub

En el repositorio abre **Settings → Secrets and variables → Actions → New repository secret**. Deben ser secretos, no variables ni archivos del repositorio.

| Nombre | Valor |
| --- | --- |
| `VLADER_KEYSTORE_BASE64` | Contenido de la clave codificado con el comando de abajo |
| `VLADER_STORE_PASSWORD` | La contraseña elegida |
| `VLADER_KEY_ALIAS` | `vlader` |
| `VLADER_KEY_PASSWORD` | La misma contraseña |

Este comando copia la clave codificada al portapapeles sin imprimirla. Pégala exclusivamente como valor de `VLADER_KEYSTORE_BASE64`:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\vlader-release.p12).Path)) | Set-Clipboard
```

Base64 **no es cifrado**. No compartas ese texto; contiene tu clave privada. Después de guardar el secreto, limpia el portapapeles (y su historial si lo tienes activado):

```powershell
Set-Clipboard -Value ""
```

## 3. Compilar siempre con esa firma

Después de revisar y fusionar el PR a `main`, entra en **Actions → VladER - APK con firma permanente → Run workflow → main**. Un workflow nuevo con ejecución manual puede no aparecer hasta estar en la rama principal.

Descarga el artefacto `VladER-firma-permanente`, extrae el ZIP e instala `VladER.apk`. Usa ese workflow para todas las actualizaciones. Se detiene si faltan secretos; nunca inventa una clave ni recurre a debug. Solo se ejecuta manualmente: no entrega la clave a workflows de pull requests. Revisa siempre el código de la rama que vas a firmar.

El workflow de pruebas existente sigue generando un APK **debug**, cuya firma no es permanente. No lo uses para actualizar una instalación firmada con tu clave.

## Cambio desde las versiones anteriores

La nueva firma no coincide con la firma debug de tu instalación actual. Si no se conserva aquella clave privada, habrá que desinstalar **una última vez** para pasar a la firma permanente. Esto borra biblioteca, preferencias y progreso: conserva los EPUB originales y anota dónde te quedaste **antes**. No hay exportación de progreso implementada todavía.

Tras esa transición, las siguientes APK deben conservar `applicationId=sv.vlad.lector`, esta misma clave y un `versionCode` creciente. Cambiar el nombre visible a VladER no cambia la identidad del paquete.

## Estado de verificación

La automatización se prepara en el código, pero la firma personal **no está activada ni probada** hasta que guardes los secretos y completes una ejecución correcta. Una clave temporal de CI solo sirve para probar la configuración; no es tu clave de distribución y no se conserva.

Referencias: [Firma de aplicaciones Android](https://developer.android.com/studio/publish/app-signing) y [Secretos de GitHub Actions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
