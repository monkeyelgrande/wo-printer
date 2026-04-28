-- ============================================================
-- WorldOffice Printer - Esquema base
-- Aplicar en una BD PostgreSQL para una empresa que vaya a usar
-- wo-printer. Crea solo las tablas propias del aplicativo.
--
-- Las tablas del sistema operativo (bodegas, productos, contactos,
-- users, stock_productos, movimientos_inventario, facturas_cabeceras,
-- facturas_detalles, configuraciones) deben existir previamente como
-- parte del sistema bodega_nuevo. Las migraciones incrementales sobre
-- esas tablas estan en sql/migracion_produccion.sql.
-- ============================================================

-- ------------------------------------------------------------
-- Impresoras configuradas (tickets POS)
-- ------------------------------------------------------------
-- tipo_bodega          : la impresora puede recibir ordenes de despacho
-- tipo_notificaciones  : la impresora recibe la tirilla de novedades
-- id_bodega            : si la impresora atiende una bodega especifica;
--                        FK logica a bodegas(id) (no se declara para
--                        no acoplar el deploy con el orden de creacion).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS impresoras (
    id                  SERIAL PRIMARY KEY,
    nombre              VARCHAR(100) NOT NULL,
    nombre_windows      VARCHAR(200) NOT NULL,
    activa              BOOLEAN      NOT NULL DEFAULT TRUE,
    fecha_creacion      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tipo_bodega         BOOLEAN      NOT NULL DEFAULT TRUE,
    tipo_notificaciones BOOLEAN      NOT NULL DEFAULT FALSE,
    id_bodega           INTEGER
);

-- ------------------------------------------------------------
-- Log de impresiones realizadas (auditoria)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS log_impresiones (
    id              SERIAL PRIMARY KEY,
    numero_factura  VARCHAR(50)  NOT NULL,
    impresora_id    INTEGER REFERENCES impresoras(id),
    archivo_origen  VARCHAR(500) NOT NULL,
    estado          VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE',
    mensaje_error   TEXT,
    fecha_procesado TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ------------------------------------------------------------
-- Cabecera de facturas que wo-printer ha procesado.
-- Sirve como tabla de idempotencia (numero_factura es la clave logica).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS facturas_impresas (
    id              SERIAL PRIMARY KEY,
    numero_factura  VARCHAR(50)  NOT NULL UNIQUE,
    cliente         VARCHAR(200),
    fecha_factura   DATE,
    fecha_impresion TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    vendedor        VARCHAR(200),
    concepto        VARCHAR(500),
    forma_pago      VARCHAR(50),
    prefijo         VARCHAR(20),
    empresa         VARCHAR(200)
);

-- ------------------------------------------------------------
-- Detalle de items por factura procesada.
-- Las columnas iva / precio_unitario / total_linea se conservan por
-- compatibilidad con el esquema legado pero wo-printer escribe 0:
-- desde la version actual el aplicativo solo lee A..W del Excel y no
-- maneja precios.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS detalle_factura (
    id              SERIAL PRIMARY KEY,
    factura_id      INTEGER NOT NULL REFERENCES facturas_impresas(id) ON DELETE CASCADE,
    codigo_producto VARCHAR(100),
    descripcion     VARCHAR(500),
    cantidad        DOUBLE PRECISION NOT NULL DEFAULT 0,
    iva             DOUBLE PRECISION NOT NULL DEFAULT 0,
    precio_unitario DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_linea     DOUBLE PRECISION NOT NULL DEFAULT 0,
    es_novedad      BOOLEAN          NOT NULL DEFAULT FALSE,
    motivo_novedad  VARCHAR(200)
);

CREATE INDEX IF NOT EXISTS idx_detalle_factura_novedad
    ON detalle_factura (factura_id) WHERE es_novedad = TRUE;

-- ------------------------------------------------------------
-- Vista operacional de novedades. Refleja items que no generaron orden
-- (codigo invalido, producto no existe, inactivo, cantidad cero, etc.).
-- El flujo es PENDIENTE -> REVISADO -> RESUELTO/IGNORADO.
--
-- id_producto_asociado y revisado_por hacen referencia a productos(id)
-- y users(id) del sistema legado; no se declaran como FK aqui para que
-- el script aplique aunque esas tablas se creen despues.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS novedades_facturas (
    id                   SERIAL PRIMARY KEY,
    factura_impresa_id   INTEGER REFERENCES facturas_impresas(id),
    numero_factura       VARCHAR(50)  NOT NULL,
    fecha_deteccion      TIMESTAMP    NOT NULL DEFAULT now(),
    tipo                 VARCHAR(30)  NOT NULL,
    codigo_original      VARCHAR(100),
    codigo_normalizado   VARCHAR(100),
    descripcion          VARCHAR(500),
    cantidad             DOUBLE PRECISION,
    motivo               VARCHAR(300),
    estado_revision      VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE',
    id_producto_asociado INTEGER,
    observacion_revision VARCHAR(500),
    revisado_por         INTEGER,
    fecha_revision       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_novedades_tipo    ON novedades_facturas(tipo);
CREATE INDEX IF NOT EXISTS idx_novedades_estado  ON novedades_facturas(estado_revision);
CREATE INDEX IF NOT EXISTS idx_novedades_factura ON novedades_facturas(numero_factura);
CREATE INDEX IF NOT EXISTS idx_novedades_codigo  ON novedades_facturas(codigo_normalizado);
CREATE INDEX IF NOT EXISTS idx_novedades_fecha   ON novedades_facturas(fecha_deteccion DESC);
