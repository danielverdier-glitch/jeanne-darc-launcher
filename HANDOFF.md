# Handoff — Barco/cabo/luz invisibles en la pantalla del barco

## Objetivo

App launcher náutico (`Jeanne D'Arc`) para una pantalla Android aftermarket
(head unit náutico, firmware NXOS, ~Android 11 gama baja) montada en el
barco real del usuario. El launcher (`app/src/main/assets/index.html`,
cargado en un `WebView` nativo) muestra un fondo oceánico, el nombre del
barco, y debía mostrar además:

- El **barco** en sí (silueta vectorial SVG), con y sin vela según
  `isNavigating`.
- El **cabo de fondeo** (línea diagonal marrón) cuando no está navegando.
- La **luz de mástil** (blanca fondeado / roja-verde navegando) de noche.
- El barco debe **cabecear** (pitching, animación CSS) cuando navega, con
  el horizonte/fondo quieto.

El **celular del usuario** (Android moderno) siempre renderizó todo
perfecto con el mismo APK. La pantalla del barco (el head unit real) es
la que nunca lo logró — ese es el problema central de esta sesión.

## Estado actual (no resuelto)

**El cabo, el barco y la luz de mástil NO se ven en la pantalla principal
del head unit real**, a pesar de que:

- El diagnóstico en pantalla (ver más abajo) confirma que los elementos
  existen en el DOM, tienen `display=block`, tienen la cantidad correcta
  de `<path>`, y `getBBox()` devuelve un tamaño no-nulo y correcto
  (ej. `bbox=333,36,368x467`).
- El mismo contenido, copiado dentro del **overlay de calibración**
  (que se arma 100% por JavaScript vía `innerHTML`, no vive en el HTML
  estático), **se ve perfecto** en esa misma pantalla. Esto prueba que
  el contenido de los `<path>` en sí NO es el problema.
- En Chrome headless (entorno de control) y en el celular del usuario,
  todo renderiza siempre bien — el bug es 100% específico de esa
  pantalla del barco.

Solo el elemento **cabo** funcionó de forma confiable en rondas
anteriores (antes de esta sesión) — pero en las últimas pruebas,
**incluso el cabo dejó de verse**, mismo estado que barco/luz.

## Archivos en los que estamos trabajando

- `app/src/main/assets/index.html` — toda la lógica de la app (HTML +
  CSS + JS inline en un solo archivo). Es donde está el barco, el cabo,
  la luz de mástil, el diagnóstico en pantalla, y los gestos de prueba.
- `app/src/main/java/com/jeannedarc/launcher/MainActivity.java` — código
  nativo Android que crea el `WebView`. Se tocó una sola vez esta sesión
  (ver "Cosas que cambiamos").
- El build se dispara automáticamente vía GitHub Actions al pushear a
  `main` (workflow `.github/workflows/build.yml`, no tocado). El APK de
  testing queda como artifact `JEANNE_DARC_testing` en cada run.

## Cosas que cambiamos esta sesión (en orden cronológico)

1. **Vectorización del barco a SVG** — el barco pasó de imágenes PNG
   rasterizadas a paths SVG generados con OpenCV (contornos + polígono
   simplificado) a partir de las imágenes originales. Se hizo así porque
   el cabo (que sí funcionaba) era SVG, y la hipótesis inicial era que
   SVG > raster para esta pantalla. Se afinó el detalle en varias rondas
   (mástil, forestay, botavara, rueda de timón, nombre y número de vela
   como `<text>`, sombra como polígono translúcido) hasta lograr un
   barco visualmente fiel con muy pocos elementos (~6 `<path>` + líneas).
2. **`transform` del grupo → coordenadas horneadas.** El barco vivía en
   un `<g transform="translate(...) scale(...)">`. Se sospechó que el
   motor SVG de esta pantalla no aplica bien `transform` en `<g>`, así
   que se pre-multiplicaron todas las coordenadas y se sacó el atributo.
   **No cambió nada.**
3. **`<g>` anidado → `<svg>` de nivel superior.** El barco pasó de ser
   un `<g style="display:none">` dentro de un `<svg>` compartido, a ser
   su propio `<svg>` de nivel superior (como el cabo y la luz de
   mástil), alternando su propio `display`. **No cambió nada.**
4. **Software rendering → hardware.** En `MainActivity.java` había
   `webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)`, agregado en
   una ronda anterior (antes de esta sesión) para arreglar transparencia
   de PNG que ya no se usa. Se sacó, restaurando aceleración por
   hardware. **No cambió nada** (confirmado con foto: mismo bbox
   correcto, mismo "no se ve nada").
5. **Prueba de diagnóstico clave — pedida por el usuario:** duplicar el
   contenido del barco *dentro* del `<svg id="caboSvg">` (el único
   elemento confiable), sin tocar nada más. **¡Funcionó! El barco
   apareció.** Esto probó que el contenido de los paths no era el
   problema, sino algo estructural sobre tener un `<svg>` propio nuevo.
6. Se hizo permanente: barco + luz de mástil pasaron a vivir dentro del
   mismo `<svg id="caboSvg">`, sacando el filtro `feGaussianBlur` de la
   luz (reemplazado por círculos superpuestos). Esto introdujo un bug
   (el toggle de visibilidad del cabo apagaba el `<svg>` entero,
   tapando también al barco) — se corrigió separando el toggle del
   `<svg>` contenedor del toggle de la línea del cabo en particular.
7. Se notó que sacar el toggle explícito de JS del `<svg id="caboSvg">`
   (dejándolo "visible por defecto" en vez de recibir un
   `style.display="block"` explícito por script) **rompió hasta el
   cabo**, que hasta ese momento nunca había fallado. Se restauró el
   toggle explícito.
8. **Última hipótesis probada — inyección por JS:** dado que el overlay
   de calibración (armado 100% por JS) siempre renderizó bien, se movió
   *todo* el subárbol del `<svg id="caboSvg">` (cabo + ambas variantes
   del barco + luz) de HTML estático a un string JS insertado vía
   `barcoWrap.innerHTML = \`...\`` al cargar la página. **Tampoco
   funcionó** — foto más reciente del usuario confirma: `disp=block`,
   `bbox` correcto, pero nada se pinta. Ni el cabo tampoco.
9. Se agregaron a lo largo de la sesión: diagnóstico en pantalla
   (`imgDiag`, `boatDiag`, `mastDiag` — reportan `display` computado,
   cantidad de `<path>`, `getBBox()`), y gestos de prueba: mantener
   presionado ~900ms en la parte superior de la pantalla fuerza
   día/noche; sostener hasta ~2.5s además simula navegación a 5kn rumbo
   045°. Se agregó `manualOverride` para que el GPS real (que en esta
   pantalla tarda en activarse — parece necesitar que se abra Google
   Maps primero para "despertar" el proveedor de ubicación) no pise el
   toggle manual.

## Cosas que intentamos y sabemos que NO son la causa

- ❌ Complejidad/cantidad de puntos en los `<path>` (se redujo
  drásticamente en varias rondas, sin efecto en el bug de fondo — igual
  no se veía nada).
- ❌ El atributo `transform` en el `<g>`.
- ❌ `<g>` anidado vs. `<svg>` de nivel superior propio.
- ❌ Renderizado por software vs. hardware del `WebView`.
- ❌ Filtros SVG (`feGaussianBlur`) — se sacaron de la luz igual y
    tampoco se ve.
- ❌ HTML estático vs. inyección por JS al cargar — **la última prueba
    (paso 8) muestra que esto tampoco alcanza**, aunque el overlay de
    calibración (JS + siempre creado dinámicamente en respuesta a un
    gesto del usuario, no al cargar la página) sí funciona.

## Pistas activas / diferencia no explicada todavía

El overlay de calibración es la ÚNICA superficie que renderiza el barco
de forma confiable. Diferencias que **todavía no probamos aisladamente**
entre el overlay (funciona) y el `caboSvg` (no funciona), incluso después
de mover ambos a inyección JS:

1. **Timing:** el overlay se crea/llena cuando el usuario dispara un
   gesto (varios segundos después de cargar la página, después de
   `load`, con la página ya interactiva). El `caboSvg` ahora se llena
   por JS pero **al cargar la página**, muy temprano. Puede que el motor
   necesite que el `WebView`/página esté en un estado "asentado" antes
   de aceptar el paint de SVG grande — no probamos meter el barco con un
   `setTimeout` de varios segundos, o disparado por un gesto/evento en
   vez de en la carga inicial.
2. **`display:none` inicial vs. nunca-`display:none`:** el overlay
   arranca con `display:none` en un `<div>` HTML normal (no SVG) y pasa
   a `flex`. El `caboSvg` arranca `display:none` en un `<svg>` y pasa a
   `block`. Puede que el tipo de elemento contenedor (`<div>` vs
   `<svg>`) en el toggle inicial importe.
3. **Toggle anidado (`style.display` en `<g>` hijos) vs. contenido sin
   toggle propio:** la prueba que funcionó (paso 5) tenía el barco
   SIEMPRE visible dentro de `caboSvg` (sin su propio `display:none`).
   Las versiones posteriores (barco con vela / sin vela / luz,
   cada una con su propio `style="display:none"` alternado
   individualmente) NUNCA volvieron a funcionar. **Esta es la hipótesis
   más fuerte sin probar todavía**: puede que alternar `display` en un
   elemento anidado sea en sí mismo poco confiable en este motor,
   independientemente de si el contenido es estático o inyectado por JS.
4. No se probó todavía forzar un *reflow* explícito
   (`el.getBoundingClientRect()` o similar) inmediatamente después de
   cada cambio de `style.display`, por si el motor necesita ese empujón
   para repintar.

## Next steps recomendados (en orden de probabilidad)

1. **Aislar la hipótesis #3 (la más prometedora):** volver a la prueba
   que funcionó — un solo `<g>` (o `<svg>`) con el barco SIN
   `style="display:none"` propio, siempre presente. Para elegir entre
   "con vela" / "sin vela" / "sin barco", en vez de tener 3 variantes
   ocultas con toggle, usar un solo contenedor y **reemplazar su
   contenido** (`innerHTML`) según el modo, o **agregar/quitar el nodo
   del DOM** (`appendChild`/`remove()`) en vez de tocar `style.display`.
   Esto evita por completo el toggle de visibilidad anidado.
2. Si eso tampoco alcanza, probar disparar la inyección del barco con
   un `setTimeout(..., 2000)` o más (en vez de al cargar la página), para
   descartar la hipótesis de timing (#1).
3. Considerar pedirle al usuario acceso ADB (por USB o red) a esa
   pantalla — permitiría ver `chrome://inspect` remoto o logcat en vivo,
   en vez de iterar a ciegas por fotos y builds de GitHub Actions (ciclo
   de ~5-10 min por intento). Ya se le preguntó una vez; no tenía forma
   en ese momento.
4. Si nada de esto funciona, considerar abandonar el enfoque SVG
   completamente para el barco/luz y volver a una imagen rasterizada
   (JPEG, sin alfa) compuesta directamente en el fondo — el único
   approach que confirmadamente mostró el barco en esta pantalla fue el
   de "hornear" el barco como JPEG en el fondo (commit `120544d`, antes
   de esta sesión), aunque con el problema conocido de que el horizonte
   cabeceaba junto con el barco. Se podría revisar si hay una forma de
   lograr el efecto de cabeceo sin SVG (ej. dos capas JPEG con
   `clip-path` o crop dinámico vía canvas, si canvas 2D funciona mejor
   que SVG en este motor — no se probó esta sesión).

## Diagnóstico en pantalla (ya implementado, útil para seguir probando)

En la esquina superior izquierda de la app aparecen 3 líneas verdes:

- `IMG OK WxH` / `IMG ERROR` — carga del JPEG de fondo (`bgImg`).
- `BOAT <id> disp=... paths=N bbox=x,y,WxH` — estado del barco activo
  (`boatSail` o `boatNosail`).
- `MAST disp=... night=... nav=... activeLight=... lB=... lR=... lV=...`
  — estado de la luz de mástil.

Gestos de prueba (zona superior de la pantalla, arriba del 30% de la
altura):

- **Triple tap:** abre el overlay de calibración (posiciones del cabo y
  la luz).
- **Mantener presionado ~900ms:** alterna día/noche manualmente.
- **Mantener presionado ~2.5s:** además simula navegar a 5kn rumbo 045°
  (NE), para poder probar vela + luz roja/verde sin GPS real.

## Convenciones del repo (para quien continúe)

- Commits firmados como `Claude <noreply@anthropic.com>`, push directo a
  `main` (el workflow de build solo dispara en `main`/`master`).
- Cada iteración = 1 commit + push + esperar el build de GitHub Actions
  (`gh`/API, ~1-2 min) + pedirle al usuario que instale y pruebe en el
  head unit real — el celular del usuario y Chrome headless **nunca**
  reproducen el bug, solo sirven para verificar que no se rompió nada
  más.
