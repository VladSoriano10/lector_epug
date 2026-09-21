# Volver a la versión estable de EPUB

Antes de añadir PDF e ilustraciones se conservó la versión 0.3.3 en la rama `rollback/v0.3.3-epub-estable`, commit `c9d60bde8e34b08476b89d6620619ffdb04c081a`.

La versión nueva es 0.4.0 (versionCode 7). No se borran ni migran los EPUB guardados ni sus marcadores. Los PDF se guardan como archivos independientes.

## Si todavía no instalaste 0.4.0

Puedes generar la versión anterior en Actions → VladER - APK con firma permanente → Run workflow, seleccionando `rollback/v0.3.3-epub-estable`.

## Si ya instalaste 0.4.0

Android normalmente bloquea instalar un versionCode inferior, aunque la firma sea la misma. Para conservar los datos, restaura el código de la rama de respaldo en un nuevo PR y aumenta `versionCode` por encima del último instalado (por ejemplo, 8 si instalaste 7). Pon un nombre como `0.4.1-rollback`, fusiona a main y genera la APK con el mismo workflow y los mismos secretos de firma.

No regeneres la clave ni desinstales la app para este procedimiento. La clave estable permite actualizar sin eliminar los datos. La versión antigua no sabe abrir PDF: para un rollback completo se debe filtrar temporalmente la biblioteca a los identificadores terminados en `.epub`; se conservan los archivos PDF para una futura actualización.

Conserva también los archivos originales fuera de la aplicación. El respaldo de GitHub guarda el código, no los libros privados ni el progreso del teléfono.

## Dependencia PDF

La extracción usa [PdfBox-Android 2.0.27.0](https://github.com/TomRoush/PdfBox-Android), port de Apache PDFBox, con licencia Apache-2.0. La visualización usa PdfRenderer de Android. No se envían libros a servidores.
