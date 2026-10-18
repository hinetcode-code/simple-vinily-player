# Simple Vinyl Player para Android (6.0 o superior)

Versión Android de Simple Vinyl Player 1.0.5: disco de vinilo con la portada, etiqueta de papel que
gira, brazo del tocadiscos que avanza por el álbum, reproductor mínimo abajo que se oculta solo,
biblioteca por artista → álbumes y carátulas/metadatos automáticos (iTunes y MusicBrainz).

- Android 6.0 (API 23) o superior. Sin dependencias externas (solo el framework de Android).
- Lee la música del dispositivo (permiso de almacenamiento/audio).

## Obtener el .apk sin instalar nada (GitHub)

1. Crea un repositorio en GitHub y sube el contenido de esta carpeta (incluida `.github`).
2. Abre la pestaña **Actions** → **Compilar APK** → *Run workflow* (o espera al push).
3. Cuando termine, descarga el artefacto **simple-vinyl-player-apk** y descomprime el `.apk`.
4. Cópialo al móvil y ábrelo (permite "instalar apps de orígenes desconocidos").

## Compilar en local

Con Android Studio (Hedgehog o posterior): *Open* esta carpeta y pulsa *Run*.
Por línea de comandos: JDK 17 + Android SDK (plataforma 34) y `gradle assembleDebug`;
el APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Uso

- Toca el disco para mostrar el reproductor; se oculta solo a los pocos segundos
  (Ajustes → "Fijar el reproductor" para que no se oculte).
- Desliza desde el borde derecho (o toca la barrita) para abrir la biblioteca.
- Ordenar por **Artista** muestra solo artistas; al tocar uno aparecen sus álbumes.
- Mantén pulsado un álbum o artista: buscar carátula y datos, o elegir una carátula del móvil.
# simple-vinily-player
