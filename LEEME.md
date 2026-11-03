# Bucle TV

Sistema para controlar las TVs del plató desde el ordenador de realización.

- **App de las TVs** (`BucleTV.apk`): reproduce en bucle, sin cortes, vídeos e imágenes. Recibe las listas del ordenador y, si no hay ordenador, puede leer un pendrive USB.
- **Programa del estudio** (`BucleTV-Estudio.exe`, Windows): panel de control para preparar listas, mandarlas a cada TV y arrancarlas sincronizadas.

---

## 1. Conseguir el APK y el programa (gratis, con GitHub)

1. Crea una cuenta en https://github.com si no tienes.
2. Arriba a la derecha: **+ → New repository**. Nombre `BucleTV`, márcalo como **Private** y pulsa **Create repository**.
3. En la página del repositorio vacío, pulsa **uploading an existing file**.
4. Descomprime `BucleTV.zip` y **arrastra todo el contenido de la carpeta** (no la carpeta en sí). Incluye la carpeta `.github` (en Windows activa "Elementos ocultos" en el Explorador; en Mac, Cmd+Shift+.).
5. Pulsa **Commit changes**.
6. Pestaña **Actions** → "Compilar APK y programa de Windows" (tarda unos 5 minutos).
7. Cuando tenga el tic verde, entra y abajo, en **Artifacts**, descarga:
   - **BucleTV-apk** → dentro está `BucleTV.apk` (para las TVs)
   - **BucleTV-Estudio-Windows** → dentro está `BucleTV-Estudio.exe` (para el ordenador)

> El repositorio privado solo lo ves tú. Si Actions no arranca solo, entra en el flujo y pulsa **Run workflow**.

## 2. Instalar en las TVs

Copia `BucleTV.apk` a un pendrive, conéctalo a la TV y ábrelo con un explorador de archivos (por ejemplo **File Commander** o **X-plore**, de la Play Store de la TV). Acepta "orígenes desconocidos" cuando lo pida.

Al abrir Bucle TV por primera vez:
1. Acepta el permiso para leer archivos.
2. En Android 10 o superior pedirá **"Mostrar sobre otras aplicaciones"**: actívalo para que se abra sola al encender.

Para instalar una versión nueva, haz lo mismo: se instala encima sin perder nada.

## 3. Programa del estudio (ordenador de realización)

1. Pon `BucleTV-Estudio.exe` en una carpeta fija (por ejemplo `C:\BucleTV`). Ahí creará la carpeta `BucleTV-datos` con tus vídeos, imágenes y listas.
2. Ábrelo con doble clic. Se abre una ventana negra (**no la cierres**) y el panel en el navegador.
3. La primera vez, Windows preguntará por el **firewall**: pulsa **Permitir** (redes privadas). Sin esto, las TVs no lo encuentran.
4. Abre Bucle TV en las TVs: aparecerán solas en el panel como TV 1, TV 2, TV 3. Haz clic en el nombre para llamarlas como quieras ("Fondo izquierda"…).

> Windows puede avisar de que el programa "no es de un editor conocido" porque no está firmado. Pulsa **Más información → Ejecutar de todas formas**.

### Trabajar con el panel

1. **Biblioteca**: arrastra tus vídeos e imágenes (o "Abrir carpeta" y cópialos ahí).
2. **Listas**: crea listas ("Apertura", "Entrevista"…) y añade archivos con **+ Lista**. Ordénalos con ↑ ↓ y pon los segundos de cada imagen.
3. Las TVs **descargan los archivos solas** a su memoria (lo ves en cada tarjeta). Hazlo antes del programa: luego en directo no dependen del wifi.
4. En cada TV elige su lista y pulsa **▶ Reproducir**.
5. **Arrancar todas a la vez**: las tres TVs empiezan su lista en el mismo instante. Si las listas duran lo mismo, siguen alineadas vuelta tras vuelta (el panel muestra "sincro ±X ms").
6. **Siguiente** y **Negro** por TV, o **Todas a negro**.

Si editas una lista que ya está en pantalla, no cambia hasta que pulses **Reproducir** otra vez (para que nada salte en directo sin querer).

### Si una TV no aparece en el panel

- La TV y el ordenador tienen que estar en la **misma red**. Una red Wi-Fi de invitados o con "aislamiento de clientes" no sirve.
- Comprueba el permiso del firewall de Windows (punto 3).
- En la TV: botón **OK → Estudio → Escribir IP** y pon la IP que sale arriba en el panel (por ejemplo `192.168.1.20`).

### Sin conexión

Si se cae la red, cada TV **sigue con lo que estaba mostrando** desde su memoria. Si se reinicia sin red, vuelve a la última lista que recibió. En cuanto vuelve la red, se reconecta sola.

## 4. Control desde vMix u otros (URLs)

Cualquier sistema que pueda abrir una URL puede manejar las TVs (cambia la IP por la de tu ordenador):

| Qué | URL |
|---|---|
| Reproducir una lista en la TV 1 | `http://192.168.1.20:8080/api/control?tv=1&accion=reproducir&lista=Apertura` |
| Reproducir su lista asignada | `http://192.168.1.20:8080/api/control?tv=1&accion=reproducir` |
| Arrancar todas sincronizadas | `http://192.168.1.20:8080/api/control?tv=todas&accion=sincronizar` |
| Varias TVs a la vez | `...?tv=1,3&accion=reproducir&lista=Cierre` |
| Siguiente | `...?tv=2&accion=siguiente` |
| Negro | `...?tv=todas&accion=negro` |
| Devolver la TV a modo USB | `...?tv=2&accion=usb` |

`tv` acepta el número, el nombre o `todas`.

## 5. Mando a distancia (en cada TV)

| Botón | Qué hace |
|---|---|
| **OK** | Menú: conexión con el estudio, arranque automático sí/no, transición, sonido, información… |
| **Derecha / Izquierda** | Siguiente / anterior (en modo estudio, rompe la sincronía hasta el próximo "Reproducir") |
| **Home** | Volver al menú normal de la TV |
| **Atrás** (dos veces) | Salir de la app |

Mientras la app está cerrada, esa TV aparece como "desconectada" en el panel.

## 6. Modo USB (sin ordenador)

Si el estudio no le ha asignado nada a una TV, reproduce lo que haya en un pendrive:
- Si el USB tiene una carpeta **BUCLE**, solo usa esa carpeta.
- Orden por nombre: numera los archivos `01_…`, `02_…`.

## Consejos

- Vídeo en **MP4 H.264 o H.265**; para pasos perfectos entre vídeos, misma resolución y formato en todos.
- Si las TVs tienen puerto de red, **cable mejor que wifi**.
- Muchas TV no se apagan del todo ("Inicio rápido"). Si al encender no aparece la app, desactiva esa opción en los ajustes de energía, o simplemente ábrela.
- Arranque automático: se activa o desactiva en cada TV desde **OK → Arranque automático**. Si una TV esconde el permiso, desde un ordenador con ADB: `adb shell appops set com.bucletv.app SYSTEM_ALERT_WINDOW allow`

## Nota sobre la firma

El proyecto incluye su propia clave de firma (`app/bucletv.jks`) para que cada versión se instale encima de la anterior. Mantén el repositorio en privado.
