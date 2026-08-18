# JEANNE D'ARC — spec pendiente de armado

Confirmado con el usuario. Armar TODO junto solo cuando dé la orden
("dale, armá todo"), después de que confirme que el botón Radio funciona.

## 1. Barra de botones
- **Radio** primero de todos: ícono de radio, nombre "Radio", abre la acción
  implícita `cn.yunovo.nxos.activity.action.radio` (via launchApp "action:").
- **Fijos** (no se cambian ni intercambian): Radio, Mas apps, Config.
- El resto: intercambiables/cambiables. Corren una posición a la derecha.
  Desaparece el botón "Libre".

## 2. Menú "cambiar app" de un botón
- Vuelve a ser **solo apps** + Vaciar.
- Quitar: apps nativas hardcodeadas, pantallas del sistema, y los botones
  buscar-radio / generar-informe / capturar-radio / abrir-radio /
  exportar-launcher / pantallas-de-fábrica.

## 3. Apps precargadas
- Si están instaladas, usar el **ícono real** de la app (PackageManager),
  y reflejar cambios de ícono de futuras actualizaciones.

## 4. Cajón "Mas apps" propio  (mockup: docs/mockups/cajon-mas-apps.html)
- Grilla **10×4**.
- Muestra **solo apps normales** (launchables). NO listar entradas internas
  del firmware (AV, apagar pantalla, ecualizador, cámara 360…).
- Primer casillero: **"Escritorio sistema"** → abre el escritorio del
  firmware (CATEGORY_HOME chooser / launcher OEM) para no perder funciones.
- Fondo = imagen de sistema **día/noche** SIN elementos (barco/luz/velas/cabo).
  Día = playa, noche = luna. (Pedir al usuario las imágenes limpias al armar;
  el mockup usa degradés aproximados.)
- Texto negro de día, blanco de noche, con sombra suave.
- >40 apps: revisar scroll o paginado (hoy tiene <40, entra en 1 pantalla).

## 5. Bloque "Fuentes del equipo"
- Dejar **armado pero desconectado** (para llamarlo en el futuro).
- Acciones/actividades verificadas exportadas en este equipo:
  - Radio FM: action `cn.yunovo.nxos.activity.action.radio`
  - Bluetooth: action `cn.yunovo.nxos.activity.action.bt`
  - Multimedia/Video: action `cn.yunovo.nxos.intent.action.MEDIA_BROWSER`
  - AUX: `cn.yunovo.car.camera/cn.yunovo.car.camera.act.ActAux`
  - Entrada video: `...act.ActFront`  · Entrada AV: `...act.ActFront2`
  - Panorámica 360: `cn.yunovo.car.camera/...act.ActAvm`
  - Ajustes de sonido: `cn.yunovo.nxos.audiofx`
  - Álbum: `cn.yunovo.nxos.mediaui`

## 6. Panel de acceso rápido
- No se toca.

## 7. Botón "Poner como pantalla de inicio"
- Abre la pantalla de Android para elegir app de inicio
  (Settings.ACTION_HOME_SETTINGS, o el chooser de HOME).

## 8. Manifiesto
- Declarar `CATEGORY_HOME` + `CATEGORY_DEFAULT` en el intent-filter de
  MainActivity (habilita ser elegible como home). La prueba SIGUE siendo como
  app; el usuario decide el paso a home por defecto después.

## 9. Widget "sonando ahora"  (mockup: docs/mockups/widget-sonando-ahora.html)
- Posición: **esquina inferior derecha**, arriba de la barra de iconos.
- Recuadro **transparente**: sin fondo ni borde. Solo texto + botones.
- Texto (título / subtítulo / fuente): **negro de día, blanco de noche**,
  con sombra suave. Alineado a la derecha.
- Botones **⏮ ⏯ ⏭**: cada uno con recuadro de contorno transparente,
  color según día/noche.
- **Siempre visible** ("—" cuando no suena nada).
- Fuente de datos: `MediaSessionManager.getActiveSessions()` → requiere
  permiso **Notification Listener** (grant único, como el usage-access).
  Leer metadata (title/artist/art) + estado, y mandar transport controls
  via MediaController.
- Confianza: Spotify/streaming/música local = alto. Radio FM del firmware =
  confirmar en la prueba; si no publica MediaSession estándar, investigar el
  mecanismo interno (MediaEventReceiver de cn.yunovo.nxos.player).

## Referencias
- Acciones del home originales: docs/stock-launcher.xml (launcher.xml
  decompilado del firmware).
