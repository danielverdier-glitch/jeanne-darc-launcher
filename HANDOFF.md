# Barco/cabo/luz invisibles en la pantalla del barco — RESUELTO

## Causa raíz

`.barco-wrap` era la única capa a pantalla completa del archivo declarada
**solo** con el shorthand CSS `inset:0`, sin `width`/`height`:

```css
.barco-wrap{position:absolute;inset:0;z-index:2}   /* ❌ */
```

`inset` requiere Chrome 87+. El WebView del head unit es más viejo (el resto
del código solo necesita ~Chrome 57), así que ahí `inset:0` se descarta en
silencio: `top/left/right/bottom` quedan en `auto`, y como el único contenido
del wrapper está posicionado en absoluto (no aporta al shrink-to-fit), la caja
**colapsa a 0×0**. Todo lo que vive adentro queda recortado a nada.

En el celular del usuario y en Chrome headless `inset` sí existe, el wrapper
mide 1024×600 y todo se ve — por eso el bug era 100% específico de esa pantalla.

### Por qué el diagnóstico decía que todo estaba bien

`getBBox()` devuelve geometría en **espacio de usuario SVG**, totalmente
independiente de la caja CSS. Sigue reportando `bbox=333,36,368x467` aunque el
elemento mida 0×0 y no se pinte nada. Ese fue el punto ciego que ocultó la
causa durante ocho rondas. El número que faltaba era
`getBoundingClientRect()` — ahora el diagnóstico en pantalla lo muestra
(`wrap=...` y `svg=...`).

### Correlación con la historia del repo (perfecta)

| commit | dónde vivía `caboSvg` | resultado en pantalla |
|---|---|---|
| `120544d`–`30535a1` | hijo directo de `#screen` (1024×600 explícito) | cabo ✅ |
| ídem, variantes del barco | dentro de `.barco-wrap` | barco ❌, luz ❌ |
| `30535a1` (prueba que funcionó) | copia del barco dentro de `caboSvg`, o sea **fuera** del wrapper | barco ✅ |
| `dddf301` → `60831f5` | todo movido **adentro** de `.barco-wrap` | todo ❌, **incluido el cabo** |

También explica por qué el overlay de calibración era la única superficie
confiable: su `#calCanvas` tiene `width:1024px;height:600px` explícitos, así
que nunca dependió de `inset`.

Nada que ver con SVG, cantidad de paths, `transform`, filtros,
software/hardware rendering, ni HTML estático vs. inyección por JS.

## Reproducción y verificación

El bug se reprodujo exactamente en Chrome headless quitando las declaraciones
`inset:0` del archivo (simulando el motor viejo): fondo, título e iconbar
perfectos, sin barco/cabo/luz, y el diagnóstico verde intacto — idéntico a las
fotos del head unit. Con el fix aplicado, el render con y sin soporte de
`inset` es **byte a byte idéntico**, en los cuatro estados (día/noche ×
fondeado/navegando) y en el overlay de calibración.

## El fix

- Ningún `inset` en el archivo. Toda capa full-bleed posicionada en absoluto
  usa `top:0;left:0;width:100%;height:100%`. Hay un comentario en el CSS para
  que no se reintroduzca.
- Alcanzó también a `.overlay` (app picker / swap picker) y al overlay de
  calibración, que tenían el mismo defecto latente.
- `#caboSvg` lleva además `width="1024" height="600"` como red de seguridad.
- El diagnóstico en pantalla ahora reporta `getBoundingClientRect()` del
  wrapper y del `<svg>` junto al `getBBox()`.
- Se revirtió la inyección por JS del subárbol SVG (commit `9b89502`, basada en
  la hipótesis equivocada) a HTML estático, que es la configuración probada.

## Nota para el futuro

Este WebView está entre Chrome ~57 y ~86. Otra feature de ese rango que el
archivo usa es `gap` en flexbox (Chrome 84+); se verificó que su ausencia es
solo cosmética (íconos y puntos del statusbar apenas más juntos), así que no se
tocó. Cuidado al agregar CSS moderno: si algo depende de una feature posterior
a Chrome 87, va a funcionar en el celular y fallar solo en el barco.

## Diagnóstico en pantalla y gestos de prueba

Tres líneas verdes arriba a la izquierda:

- `IMG OK WxH` / `IMG ERROR` — carga del JPEG de fondo.
- `BOAT <id> disp=... paths=N bbox=... wrap=WxH svg=WxH` — estado del barco
  activo. **`wrap`/`svg` en 0x0 = la capa colapsó; ese es el síntoma a mirar.**
- `MAST disp=... night=... nav=... activeLight=... lB=... lR=... lV=...`

Gestos (zona superior, arriba del 30% de la altura):

- **Triple tap:** overlay de calibración (posiciones del cabo y la luz).
- **Mantener ~900ms:** alterna día/noche.
- **Mantener ~2.5s:** además simula navegar a 5kn rumbo 045°.

## Convenciones del repo

- El build se dispara por GitHub Actions al pushear a `main`
  (`.github/workflows/build.yml`); el APK queda como artifact
  `JEANNE_DARC_testing` en cada run.
