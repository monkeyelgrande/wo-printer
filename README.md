# WorldOffice Printer (`wo-printer`)

Aplicación de escritorio en **Java 8 (Swing + FlatLaf)** que **vigila una carpeta** donde
**WorldOffice** exporta facturas en formato Excel (`.xlsx`) **o HTML** (`.html`), las
procesa contra una base de datos **PostgreSQL** y genera órdenes de despacho **por bodega**
con sus registros e inventario.

El objetivo es automatizar el flujo de mostrador/bodega: cada vez que se factura en
WorldOffice y se exporta la factura a una carpeta, la aplicación la detecta, clasifica sus
ítems, **genera la(s) orden(es) de salida por bodega**, actualiza el stock pendiente y deja
registradas las **novedades** cuando algún producto no se reconoce.

> **Nota:** a pesar del nombre histórico del proyecto, `wo-printer` **ya no imprime tirillas**.
> La impresión/notificación de las órdenes la realiza el sistema aliado **control bodega**
> mediante sus propias notificaciones. Esta aplicación se limita a **procesar el Excel y
> registrar las órdenes y novedades** en la base de datos.

---

## Tabla de contenido

- [¿Qué hace?](#qué-hace)
- [Arquitectura y flujo](#arquitectura-y-flujo)
- [Estructura de archivos](#estructura-de-archivos)
- [Modelo de datos (PostgreSQL)](#modelo-de-datos-postgresql)
- [Formato del Excel de WorldOffice](#formato-del-excel-de-worldoffice)
- [Formato HTML de WorldOffice](#formato-html-de-worldoffice)
- [Requisitos previos](#requisitos-previos)
- [Configuración (`config.properties`)](#configuración-configproperties)
- [Compilación y ejecución](#compilación-y-ejecución)
- [Cómo funciona internamente (detalle)](#cómo-funciona-internamente-detalle)
- [Solución de problemas](#solución-de-problemas)
- [Tecnologías y dependencias](#tecnologías-y-dependencias)
- [Notas para el repositorio público](#notas-para-el-repositorio-público)

---

## ¿Qué hace?

1. **Vigila una carpeta** (`watch.folder`) por archivos `.xlsx` o `.html` nuevos o
   modificados, esperando a que el archivo esté **estable** (que WorldOffice termine de
   escribirlo). Si la factura HTML es multipágina, además espera a que estén **todas las
   páginas** (hasta 30 s) antes de procesarla.
2. **Parsea la factura**: el Excel con Apache POI (solo columnas **A–W**) o el HTML con
   jsoup (que además trae el **NIT del cliente** y los **precios/IVA** por ítem).
3. **Orquesta una transacción** contra PostgreSQL:
   - Verifica **idempotencia** (no reprocesa una factura ya registrada).
   - **Clasifica** los ítems en **válidos** (el producto existe en BD) y **novedades**
     (producto no encontrado).
   - Si está habilitado, **genera órdenes por bodega** (cabeceras + detalles), actualiza
     **stock pendiente** y registra **movimientos de inventario**.
   - Registra la factura procesada y guarda su detalle y las novedades.
4. **Mueve el archivo** a `procesados/` o `errores/` según el resultado (con reintentos si
   el archivo está bloqueado).
5. Todo se controla desde una **interfaz gráfica** (`MainWindow`) que muestra el estado de
   la vigilancia, las facturas procesadas, las órdenes generadas y las novedades.

---

## Arquitectura y flujo

```
 WorldOffice ──exporta .xlsx / .html──►  C:\ImpresionesWorldOffice  (watch.folder)
                                          │
                                          ▼
                            ┌──────────────────────────┐
                            │  FileWatcherService       │  polling cada 2s, espera estabilidad
                            └─────────────┬────────────┘  (y todas las páginas si es HTML)
                                          ▼
                            ┌──────────────────────────┐
                            │  ExcelParserService       │  POI, columnas A–W → Factura
                            │  HtmlFacturaParserService │  jsoup, multipágina → Factura
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
                       archivo movido a  procesados/  ó  errores/

  La impresión/notificación de las órdenes la realiza aparte el sistema "control bodega".
```

Servicios principales (paquete `com.woprinter.service`):

| Servicio                     | Responsabilidad                                                                 |
|------------------------------|---------------------------------------------------------------------------------|
| `FileWatcherService`         | Vigila la carpeta por polling, detecta archivos estables, orquesta el flujo.     |
| `ExcelParserService`         | Parsea el `.xlsx` de WorldOffice (columnas A–W) → objeto `Factura`.             |
| `HtmlFacturaParserService`   | Parsea el `.html` de WorldOffice (con páginas múltiples) → objeto `Factura`.    |
| `OrdenGeneratorService`      | Orquestador transaccional: idempotencia, clasificación, órdenes, novedades.      |
| `ProductoLookupService`      | Busca cada producto por código en la BD.                                         |
| `BodegaAsignacionService`    | Determina la bodega de cada ítem/orden.                                          |
| `ContactoResolverService`    | Resuelve datos de contacto/cliente.                                              |
| `DatabaseService`            | Singleton de acceso a PostgreSQL (conexión, consultas, registros).               |
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
│   ├── cleanup_impresoras.sql      ← elimina tablas de impresión en BD ya migradas
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
| `facturas_impresas`   | Control de **idempotencia** (`numero_factura` único) + cabecera de lo procesado (incluye `nit_cliente` si la fuente fue HTML).|
| `detalle_factura`     | Ítems de cada factura procesada, con `es_novedad` y `motivo_novedad` (y precios/IVA si la fuente fue HTML).|
| `novedades_facturas`  | Espejo operacional de novedades (flujo PENDIENTE→REVISADO→RESUELTO/IGNORADO).    |

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
>
> Si vienes de una versión anterior que sí imprimía, aplica además
> [`sql/cleanup_impresoras.sql`](sql/cleanup_impresoras.sql) para eliminar las
> tablas `impresoras` y `log_impresiones`, que ya no se usan.

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

## Formato HTML de WorldOffice

`HtmlFacturaParserService` lee la factura exportada como HTML (charset `windows-1252`).
A diferencia del Excel, el HTML **sí trae** el **NIT del cliente** (se guarda en
`facturas_impresas.nit_cliente`) y los **precios por ítem**: valor unitario, % IVA,
valor IVA y total de línea (se guardan en `detalle_factura`, que con fuente Excel quedan
en 0). El HTML no trae bodega por ítem, pero ese dato del Excel es solo informativo: la
asignación real de bodegas la decide `BodegaAsignacionService` según el stock.

Los campos se ubican por sus **etiquetas** (`CLIENTE`, `NIT`, `FECHA FACTURA`, `Item`,
etc.), no por posición, y los ítems son las filas de 9 celdas de la tabla de productos.

**Multipágina:** una factura extensa genera varios archivos: `83977.html` (página 1) más
`83977Página2.html` … `83977PáginaN.html`. Cada página termina con enlaces de navegación
(`Primero / Anterior / Siguiente / Último`) y repite el número de factura y el
`Total líneas o ítems: N`. El flujo es:

1. Las páginas secundarias (`*PáginaK.html`) **no** se procesan por sí solas.
2. Al detectar el archivo base, el parser sigue la cadena de enlaces `Siguiente` y combina
   los ítems de todas las páginas.
3. Si falta alguna página en disco, el watcher **reintenta cada ciclo** hasta **30 s**
   (`HTML_PAGINAS_TIMEOUT_MS`); si siguen faltando, el conjunto se mueve a `errores/`.
4. Antes de procesar se valida que el número de ítems leídos coincida con el
   `Total líneas o ítems` declarado; si no coincide, la factura se rechaza (a `errores/`).
5. Al terminar (bien o mal), se mueven el archivo base **y todas sus páginas**.

> La idempotencia es la misma del Excel (`prefijo-numero`, ej. `FVE-84026`): si una factura
> ya se procesó desde Excel, su HTML se reconoce como duplicada, y viceversa.

---

## Requisitos previos

- **JDK 8** (el proyecto compila con `source/target 1.8`).
- **Apache Maven 3.x**.
- **PostgreSQL** con la base de datos creada y el esquema cargado.

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

# Carpeta a vigilar (donde WorldOffice exporta las facturas .xlsx / .html)
watch.folder=C:\\ImpresionesWorldOffice
watch.folder.procesados=C:\\ImpresionesWorldOffice\\procesados
watch.folder.errores=C:\\ImpresionesWorldOffice\\errores

# Espera (ms) antes de leer un archivo nuevo (que WO termine de escribirlo)
watch.delay.ms=2000

# Generación automática de órdenes:
#   true  -> crea órdenes de salida por bodega (cabeceras+detalles, pendientes, movimientos)
#   false -> omite la creación de órdenes; el resto del flujo (registro de factura,
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
   - termina en `.xlsx` o `.html` y no empieza por `~$` (temporales de Excel), y
   - su **tamaño no cambió** entre dos lecturas consecutivas (ya terminó de escribirse).

   Las páginas secundarias de un HTML multipágina (`*PáginaK.html`) no se procesan solas:
   se procesan y se mueven junto con su archivo base.

   Un segundo hilo reintenta cada 10 s **mover** los archivos que quedaron bloqueados.

3. **Parseo** — `ExcelParserService.parsear(...)` (columnas A–W) o
   `HtmlFacturaParserService.parsear(...)` (todas las páginas del HTML) construyen un
   objeto `Factura` con la cabecera y la lista de `ItemFactura`.

4. **Orquestación transaccional** — `OrdenGeneratorService.procesar(factura)`:
   1. **Idempotencia**: si `numero_factura` ya está en `facturas_impresas`, se marca como
      duplicada y se omite.
   2. **Clasificación**: por cada ítem busca el producto (`ProductoLookupService`). Si no
      existe → **novedad**; si existe → ítem **válido**.
   3. Si `ordenes.generar.automaticas=true`, genera **una orden por bodega** con sus
      cabeceras/detalles, suma a `stock_productos.pendientes` y registra movimientos.
   4. Registra la factura procesada y guarda detalle y novedades.
   5. **Commit** al final; **rollback** ante cualquier excepción.

5. **Cierre** — el archivo se mueve a `procesados/` (si todo salió bien) o `errores/`. La
   impresión/notificación de las órdenes generadas corre por cuenta del sistema aliado
   **control bodega**.

---

## Solución de problemas

| Síntoma                                             | Causa probable / solución                                                       |
|----------------------------------------------------|---------------------------------------------------------------------------------|
| No detecta las facturas                             | Revisa `watch.folder`; el archivo debe terminar en `.xlsx` o `.html` y no empezar por `~$`. |
| El archivo "se queda" sin procesar                 | Aún está siendo escrito (tamaño cambiante) o vacío; espera a que se estabilice.  |
| HTML multipágina termina en `errores/`             | Faltaron páginas tras 30 s de espera o el conteo de ítems no coincide con el `Total líneas o ítems` declarado. |
| `Error cargando configuracion`                     | Falta/está mal `config.properties`; revisa rutas y formato.                      |
| No conecta a la base de datos                      | Verifica `db.url`, `db.user`, `db.password` y que PostgreSQL esté arriba.        |
| `Factura ... ya estaba en BD`                      | Idempotencia: esa factura ya fue procesada (está en `facturas_impresas`).        |
| Archivo no se mueve a procesados/errores            | Estaba bloqueado; el hilo de reintento lo moverá (hasta 60 intentos).           |

---

## Tecnologías y dependencias

Definidas en [`pom.xml`](pom.xml):

| Dependencia                  | Versión  | Uso                                          |
|------------------------------|----------|----------------------------------------------|
| `org.apache.poi:poi`         | 4.1.2    | Lectura de Excel.                            |
| `org.apache.poi:poi-ooxml`   | 4.1.2    | Lectura de `.xlsx` (XSSF).                   |
| `org.jsoup:jsoup`            | 1.17.2   | Lectura de facturas `.html`.                 |
| `org.postgresql:postgresql`  | 42.2.28  | Driver JDBC de PostgreSQL.                   |
| `com.formdev:flatlaf`        | 3.5.4    | Look & Feel moderno para Swing.             |
| `com.formdev:flatlaf-extras` | 3.5.4    | Extras de FlatLaf.                          |

- **Lenguaje / JDK:** Java 8
- **Build:** Maven (`maven-assembly-plugin` 3.3.0 → JAR con dependencias)
- **Clase principal:** `com.woprinter.App`

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
  registra bodegas/productos y prueba con los Excel de `sql/test_excel/`.
