# WorldOffice Printer (`wo-printer`)

Aplicación de escritorio en **Java 8 (Swing + FlatLaf)** que **vigila una carpeta** donde
**WorldOffice** exporta facturas en formato Excel (`.xlsx`), las procesa contra una base de
datos **PostgreSQL**, genera órdenes de despacho **por bodega** e **imprime tirillas** en
impresoras **POS (térmicas)** mediante comandos **ESC/POS**.

El objetivo es automatizar el flujo de mostrador/bodega: cada vez que se factura en
WorldOffice y se exporta el Excel a una carpeta, la aplicación lo detecta, registra la
operación en la base de datos e imprime automáticamente la(s) orden(es) de despacho en la
impresora de la bodega correspondiente, además de una tirilla de **novedades** cuando algún
producto no se reconoce.

---

## Tabla de contenido

- [¿Qué hace?](#qué-hace)
- [Arquitectura y flujo](#arquitectura-y-flujo)
- [Estructura de archivos](#estructura-de-archivos)
- [Modelo de datos (PostgreSQL)](#modelo-de-datos-postgresql)
- [Formato del Excel de WorldOffice](#formato-del-excel-de-worldoffice)
- [Requisitos previos](#requisitos-previos)
- [Configuración (`config.properties`)](#configuración-configproperties)
- [Compilación y ejecución](#compilación-y-ejecución)
- [Cómo funciona internamente (detalle)](#cómo-funciona-internamente-detalle)
- [Impresión POS / ESC/POS](#impresión-pos--escpos)
- [Solución de problemas](#solución-de-problemas)
- [Tecnologías y dependencias](#tecnologías-y-dependencias)
- [Notas para el repositorio público](#notas-para-el-repositorio-público)

---

## ¿Qué hace?

1. **Vigila una carpeta** (`watch.folder`) por archivos `.xlsx` nuevos o modificados,
   esperando a que el archivo esté **estable** (que WorldOffice termine de escribirlo).
2. **Parsea el Excel** con Apache POI, leyendo solo las columnas **A–W** (cabecera + ítems).
3. **Orquesta una transacción** contra PostgreSQL:
   - Verifica **idempotencia** (no reprocesa una factura ya impresa).
   - **Clasifica** los ítems en **válidos** (el producto existe en BD) y **novedades**
     (producto no encontrado).
   - Si está habilitado, **genera órdenes por bodega** (cabeceras + detalles), actualiza
     **stock pendiente** y registra **movimientos de inventario**.
   - Registra la factura como impresa y guarda el detalle de impresión y las novedades.
4. **Imprime las tirillas**:
   - Una **orden por bodega**, enviada a la impresora asociada a esa bodega.
   - Una tirilla de **novedades** (si las hay), enviada a las impresoras marcadas como de
     notificaciones.
5. **Mueve el archivo** a `procesados/` o `errores/` según el resultado (con reintentos si
   el archivo está bloqueado).
6. Todo se controla desde una **interfaz gráfica** (`MainWindow`) que muestra el estado de
   la vigilancia, las facturas procesadas y la cola de impresión.

---

## Arquitectura y flujo

```
 WorldOffice ──exporta .xlsx──►  C:\ImpresionesWorldOffice  (watch.folder)
                                          │
                                          ▼
                            ┌──────────────────────────┐
                            │  FileWatcherService       │  polling cada 2s, espera estabilidad
                            └─────────────┬────────────┘
                                          ▼
                            ┌──────────────────────────┐
                            │  ExcelParserService       │  POI, lee columnas A–W → Factura
                            └─────────────┬────────────┘
                                          ▼
                            ┌──────────────────────────┐     ┌──────────────────────────┐
                            │  OrdenGeneratorService    │◄───►│  PostgreSQL (bodega_nuevo)│
                            │  (transaccional)          │     │  productos, stock,        │
                            │  - idempotencia           │     │  facturas_*, novedades_*  │
                            │  - clasifica ítems        │     └──────────────────────────┘
                            │  - genera órdenes/bodega  │
                            │  - novedades              │
                            └─────────────┬────────────┘
                                          ▼
                            ┌──────────────────────────┐
                            │  TicketGeneratorService   │  construye tirillas ESC/POS
                            └─────────────┬────────────┘
                                          ▼
                            ┌──────────────────────────┐
                            │  PrinterService           │  javax.print → impresora por nombre_windows
                            └─────────────┬────────────┘
                                          ▼
                              Impresora de la bodega  +  Impresora(s) de novedades
                                          │
                                          ▼
                       archivo movido a  procesados/  ó  errores/
```

Servicios principales (paquete `com.woprinter.service`):

| Servicio                     | Responsabilidad                                                                 |
|------------------------------|---------------------------------------------------------------------------------|
| `FileWatcherService`         | Vigila la carpeta por polling, detecta archivos estables, orquesta el flujo.     |
| `ExcelParserService`         | Parsea el `.xlsx` de WorldOffice (columnas A–W) → objeto `Factura`.             |
| `OrdenGeneratorService`      | Orquestador transaccional: idempotencia, clasificación, órdenes, novedades.      |
| `ProductoLookupService`      | Busca cada producto por código en la BD.                                         |
| `BodegaAsignacionService`    | Determina la bodega de cada ítem/orden.                                          |
| `ContactoResolverService`    | Resuelve datos de contacto/cliente.                                              |
| `TicketGeneratorService`     | Genera los bytes ESC/POS de las tirillas (orden y novedades).                    |
| `PrinterService`             | Envía los bytes a la impresora vía `javax.print`, buscándola por su nombre de Windows. |
| `DatabaseService`            | Singleton de acceso a PostgreSQL (conexión, consultas, logging de impresión).    |
| `UsuarioSistemaService`      | Información del usuario/sistema.                                                 |

---

## Estructura de archivos

```
wo-printer/
├── README.md                       ← este documento
├── pom.xml                         ← build Maven (fat-jar con assembly plugin)
├── nb-configuration.xml            ← config NetBeans
├── .gitignore                      ← excluye config real, *.xlsx, *.log, target/, etc.
│
├── sql/
│   ├── schema.sql                  ← esquema PostgreSQL (tablas)
│   ├── migracion_produccion.sql    ← script de migración a producción
│   └── test_excel/
│       ├── _generar.py             ← genera Excels de prueba
│       ├── test_1_una_bodega.xlsx
│       ├── test_2_dos_bodegas.xlsx
│       ├── test_3_tres_bodegas.xlsx
│       ├── test_4_dos_bodegas_y_novedades.xlsx
│       ├── test_5_sin_productos_validos.xlsx
│       └── rollback_pruebas.sql    ← limpia los datos de prueba en BD
│
└── src/main/
    ├── java/com/woprinter/
    │   ├── App.java                ← punto de entrada (instala FlatLaf + abre MainWindow)
    │   ├── config/
    │   │   └── AppConfig.java      ← carga config.properties (externo o interno)
    │   ├── model/                  ← POJOs de dominio
    │   │   ├── Factura.java                ItemFactura.java
    │   │   ├── ProductoInfo.java           ResultadoLookupProducto.java
    │   │   ├── OrdenPorBodega.java         ResultadoOrden.java
    │   │   ├── Novedad.java                NovedadRegistro.java
    │   │   ├── Impresora.java              PrintJob.java
    │   │   ├── AsignacionBodega.java       ConfiguracionEmpresa.java
    │   │   └── ContactoInfo.java
    │   ├── service/                ← lógica (ver tabla de arriba)
    │   └── ui/
    │       └── MainWindow.java     ← interfaz Swing
    └── resources/
        └── config.properties      ← configuración (plantilla incluida en el repo)
```

---

## Modelo de datos (PostgreSQL)

Base de datos por defecto: `bodega_nuevo`. Hay **dos grupos** de tablas:

**Tablas propias de `wo-printer`** — las crea [`sql/schema.sql`](sql/schema.sql)
(`CREATE TABLE IF NOT EXISTS`, seguro de re-ejecutar):

| Tabla                 | Propósito                                                                       |
|-----------------------|---------------------------------------------------------------------------------|
| `impresoras`          | Impresoras POS: `nombre_windows` (nombre exacto en Windows), `activa`, `tipo_bodega` (recibe órdenes), `tipo_notificaciones` (TRUE = recibe tirilla de novedades) e `id_bodega` (bodega que atiende). |
| `facturas_impresas`   | Control de **idempotencia** (`numero_factura` único) + cabecera de lo procesado.|
| `detalle_factura`     | Ítems de cada factura procesada, con `es_novedad` y `motivo_novedad`.           |
| `novedades_facturas`  | Espejo operacional de novedades (flujo PENDIENTE→REVISADO→RESUELTO/IGNORADO).    |
| `log_impresiones`     | Auditoría de cada impresión (estado, archivo origen, mensaje de error).         |

**Tablas legadas del sistema `bodega_nuevo`** — deben existir **previamente**; `wo-printer`
las lee/actualiza pero **no** las crea (las migraciones incrementales sobre ellas están en
[`sql/migracion_produccion.sql`](sql/migracion_produccion.sql)):

| Tabla                    | Uso desde `wo-printer`                                              |
|--------------------------|--------------------------------------------------------------------|
| `bodegas`                | Catálogo de bodegas (nombre por `id`).                            |
| `productos`              | Búsqueda de productos por código (lookup/clasificación).          |
| `stock_productos`        | Actualiza **pendientes** por producto/bodega (UPSERT).            |
| `movimientos_inventario` | Inserta movimientos `AUTOMATICO` al generar órdenes.             |
| `facturas_cabeceras`     | Cabecera de cada orden de **Salida** generada (por factura/bodega).|
| `facturas_detalles`      | Ítems de cada orden generada.                                     |
| `contactos` / `users` / `configuraciones` | Resolución de contacto, usuario del sistema y datos de empresa. |

> Inicialización: crea la base `bodega_nuevo` (con el sistema legado), aplica
> `sql/migracion_produccion.sql` y luego `sql/schema.sql`.

---

## Formato del Excel de WorldOffice

`ExcelParserService` lee la **primera hoja** y solo las columnas **A–W**. La cabecera se
toma de la primera fila con datos; cada fila siguiente con la columna **T** no vacía es un
ítem. Columnas X en adelante (IVA, totales, descuentos, etc.) se **ignoran** porque varían
entre configuraciones.

| Col | Campo                | Col | Campo                          |
|-----|----------------------|-----|--------------------------------|
| A   | Tipo de documento    | M   | Teléfono                       |
| B   | Prefijo              | N   | Ciudad                         |
| C   | Número de documento  | O   | Forma de pago                  |
| D   | Fecha                | T   | Inventario (`código descripción`) |
| E   | Empresa              | U   | Bodega                         |
| F   | Vendedor             | V   | Medida                         |
| G   | Cliente              | W   | Cantidad                       |
| I   | Dirección            |     |                                |
| K   | Concepto             |     |                                |

> La columna **T** combina código y descripción: el texto antes del primer espacio es el
> **código** y el resto es la **descripción** (ej.: `A5939 ALUM UNION DE 1-1/2"`).

Hay archivos de ejemplo listos en [`sql/test_excel/`](sql/test_excel/) para probar los
distintos escenarios (una/varias bodegas, con novedades, sin productos válidos).

---

## Requisitos previos

- **JDK 8** (el proyecto compila con `source/target 1.8`).
- **Apache Maven 3.x**.
- **PostgreSQL** con la base de datos creada y el esquema cargado.
- Una o más **impresoras POS** compatibles con **ESC/POS**:
  - de **red** (acepta RAW en el puerto 9100), o
  - **USB/local** instalada en Windows (se referencia por su nombre del sistema).

---

## Configuración (`config.properties`)

`AppConfig` carga la configuración con esta prioridad:

1. **`config.properties` externo**, ubicado **junto al JAR** (recomendado en producción).
2. Si no existe, el `config.properties` **empaquetado** dentro del JAR (`src/main/resources`).

Claves disponibles:

```properties
# Base de datos PostgreSQL
db.url=jdbc:postgresql://localhost:5432/bodega_nuevo
db.user=postgres
db.password=TU_PASSWORD

# Carpeta a vigilar (donde WorldOffice exporta los Excel)
watch.folder=C:\\ImpresionesWorldOffice
watch.folder.procesados=C:\\ImpresionesWorldOffice\\procesados
watch.folder.errores=C:\\ImpresionesWorldOffice\\errores

# Espera (ms) antes de leer un archivo nuevo (que WO termine de escribirlo)
watch.delay.ms=2000

# Ancho de impresión en caracteres (48 = 80mm, 32 = 58mm)
printer.char.width=48

# Timeout de conexión a impresora de red (ms)
printer.timeout.ms=5000

# Generación automática de órdenes:
#   true  -> crea órdenes de salida por bodega (cabeceras+detalles, pendientes, movimientos)
#   false -> omite la creación de órdenes; el resto del flujo (registro de impresión,
#            detalle y novedades) sigue funcionando igual.
ordenes.generar.automaticas=true
```

| Clave                          | Descripción                                                        | Defecto |
|--------------------------------|--------------------------------------------------------------------|---------|
| `db.url` / `db.user` / `db.password` | Conexión JDBC a PostgreSQL.                                  | —       |
| `watch.folder`                 | Carpeta vigilada para los `.xlsx`.                                 | —       |
| `watch.folder.procesados`      | Destino de archivos procesados con éxito.                          | —       |
| `watch.folder.errores`         | Destino de archivos con error.                                     | —       |
| `watch.delay.ms`               | Espera antes de leer un archivo nuevo.                             | `2000`  |
| `printer.char.width`           | Ancho de tirilla en caracteres (`48`=80 mm, `32`=58 mm).          | `48`    |
| `printer.timeout.ms`           | Timeout de impresión (parámetro reservado; no usado por la impresión vía `javax.print`). | `5000`  |
| `ordenes.generar.automaticas`  | Habilita/inhabilita la creación automática de órdenes.            | `true`  |

> ⚠️ **Seguridad:** actualmente `config.properties` está **versionado en el repositorio** y
> contiene la contraseña real de la base de datos. Para un repositorio público se recomienda
> (ver [Notas para el repositorio público](#notas-para-el-repositorio-público)) dejar de
> rastrearlo, agregarlo a `.gitignore` y versionar solo una plantilla
> `config.properties.example` con valores ficticios.

---

## Compilación y ejecución

**Compilar y empaquetar** (genera un JAR ejecutable con dependencias incluidas, vía
`maven-assembly-plugin`):

```bash
mvn clean package
```

Esto produce en `target/` un artefacto `wo-printer-1.0.0-jar-with-dependencies.jar`.

**Ejecutar:**

```bash
java -jar target/wo-printer-1.0.0-jar-with-dependencies.jar
```

Para producción, copia el JAR a una carpeta junto con un `config.properties` ajustado a ese
equipo (la app lo detecta automáticamente y lo prioriza sobre el interno).

En **NetBeans** basta con abrir el proyecto y usar **Run** (clase principal:
`com.woprinter.App`).

---

## Cómo funciona internamente (detalle)

1. **Arranque** — `App.main` instala el Look & Feel **FlatLaf Light** y abre `MainWindow`.
   Desde la ventana se inicia/detiene la vigilancia.

2. **Vigilancia** — `FileWatcherService` corre en un hilo demonio y hace **polling** cada
   2 s. Registra los archivos existentes al inicio (para no reprocesarlos) y considera un
   archivo **listo** cuando:
   - termina en `.xlsx` y no empieza por `~$` (temporales de Excel), y
   - su **tamaño no cambió** entre dos lecturas consecutivas (ya terminó de escribirse).

   Un segundo hilo reintenta cada 10 s **mover** los archivos que quedaron bloqueados.

3. **Parseo** — `ExcelParserService.parsear(...)` construye un objeto `Factura` con la
   cabecera y la lista de `ItemFactura` (columnas A–W).

4. **Orquestación transaccional** — `OrdenGeneratorService.procesar(factura)`:
   1. **Idempotencia**: si `numero_factura` ya está en `facturas_impresas`, se marca como
      duplicada y se omite.
   2. **Clasificación**: por cada ítem busca el producto (`ProductoLookupService`). Si no
      existe → **novedad**; si existe → ítem **válido**.
   3. Si `ordenes.generar.automaticas=true`, genera **una orden por bodega** con sus
      cabeceras/detalles, suma a `stock_productos.pendientes` y registra movimientos.
   4. Registra la factura como impresa y guarda detalle y novedades.
   5. **Commit** al final; **rollback** ante cualquier excepción.

5. **Impresión** — con las impresoras activas (`DatabaseService.getImpresorasActivas()`):
   - Cada **orden por bodega** se imprime en la impresora cuyo `id_bodega` coincide. Si no
     hay impresora para esa bodega, la orden se guarda y se registra como `SIN_IMPRESORA`.
   - Si hay **novedades**, se imprime una tirilla en **todas** las impresoras marcadas con
     `tipo_notificaciones = TRUE`.
   - Cada intento se refleja en la **cola de impresión** (`PrintJob`) y se registra en BD
     (`IMPRESO-*` / `ERROR-*`).

6. **Cierre** — el archivo se mueve a `procesados/` (si todo salió bien) o `errores/`.

---

## Impresión POS / ESC/POS

`TicketGeneratorService` arma las tirillas con comandos **ESC/POS** estándar (inicializar,
negrita, centrado, doble alto/ancho, avance y corte parcial de papel). El ancho de línea se
toma de `printer.char.width` (48 para 80 mm, 32 para 58 mm).

`PrinterService.imprimir(...)` envía los bytes mediante **`javax.print`**: busca el
`PrintService` cuyo nombre coincide (sin distinguir mayúsculas) con `impresoras.nombre_windows`
y le manda los datos como flujo `AUTOSENSE` (RAW). Si no encuentra una impresora con ese
nombre, lanza un error que queda registrado en el log de impresión. Por eso el
`nombre_windows` de cada impresora en la BD debe coincidir **exactamente** con el nombre con
que el sistema operativo la tiene instalada (`PrinterService.listarImpresorasWindows()`
ayuda a obtenerlos).

---

## Solución de problemas

| Síntoma                                             | Causa probable / solución                                                       |
|----------------------------------------------------|---------------------------------------------------------------------------------|
| No detecta los Excel                                | Revisa `watch.folder`; el archivo debe terminar en `.xlsx` y no empezar por `~$`. |
| El archivo "se queda" sin procesar                 | Aún está siendo escrito (tamaño cambiante) o vacío; espera a que se estabilice.  |
| `Error cargando configuracion`                     | Falta/está mal `config.properties`; revisa rutas y formato.                      |
| No conecta a la base de datos                      | Verifica `db.url`, `db.user`, `db.password` y que PostgreSQL esté arriba.        |
| `Factura ... ya estaba en BD`                      | Idempotencia: esa factura ya fue procesada (está en `facturas_impresas`).        |
| Novedades no se imprimen                            | Ninguna impresora tiene `tipo_notificaciones = TRUE`.                            |
| Orden no se imprime (`SIN_IMPRESORA`)              | No hay impresora activa con el `id_bodega` de esa orden.                         |
| `Impresora no encontrada en Windows`               | `impresoras.nombre_windows` no coincide **exactamente** con el nombre instalado en Windows. |
| Archivo no se mueve a procesados/errores            | Estaba bloqueado; el hilo de reintento lo moverá (hasta 60 intentos).           |

---

## Tecnologías y dependencias

Definidas en [`pom.xml`](pom.xml):

| Dependencia                  | Versión  | Uso                                          |
|------------------------------|----------|----------------------------------------------|
| `org.apache.poi:poi`         | 4.1.2    | Lectura de Excel.                            |
| `org.apache.poi:poi-ooxml`   | 4.1.2    | Lectura de `.xlsx` (XSSF).                   |
| `org.postgresql:postgresql`  | 42.2.28  | Driver JDBC de PostgreSQL.                   |
| `com.formdev:flatlaf`        | 3.5.4    | Look & Feel moderno para Swing.             |
| `com.formdev:flatlaf-extras` | 3.5.4    | Extras de FlatLaf.                          |

- **Lenguaje / JDK:** Java 8
- **Build:** Maven (`maven-assembly-plugin` 3.3.0 → JAR con dependencias)
- **Clase principal:** `com.woprinter.App`
- **Impresión:** `javax.print` + sockets RAW (ESC/POS)

---

## Notas para el repositorio público

> ⚠️ **Importante (estado actual del repo).** El `.gitignore` actual solo excluye artefactos
> de build/IDE (`target/`, `*.class`, `nbproject/private/`, `nbactions.xml`, `.idea/`,
> `*.iml`, `.vscode/`). **No** excluye `config.properties`, que hoy está versionado **con la
> contraseña real de la base de datos**. Antes de hacer público el repositorio conviene:
>
> 1. Crear una plantilla `config.properties.example` con valores ficticios.
> 2. Dejar de rastrear el archivo real y añadirlo a `.gitignore`:
>    ```bash
>    git rm --cached src/main/resources/config.properties
>    echo "src/main/resources/config.properties" >> .gitignore
>    ```
> 3. **Rotar la contraseña** de PostgreSQL, ya que quedó en el historial de Git (considera
>    reescribir el historial si el secreto debe eliminarse por completo).
>
> Recomendado también ignorar archivos sensibles/voluminosos: `*.xlsx`, `*.log`.

- Para reproducir el entorno: crea la BD PostgreSQL con el sistema legado `bodega_nuevo`,
  aplica `sql/migracion_produccion.sql` y `sql/schema.sql`, ajusta tu `config.properties`,
  registra bodegas/impresoras/productos y prueba con los Excel de `sql/test_excel/`.
