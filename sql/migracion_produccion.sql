-- ============================================================================
-- MIGRACIÓN DE PRODUCCIÓN — INTEGRACIÓN wo-printer ↔ bodega_agroinsumos
-- Base de datos objetivo: bodega_nuevo  |  PostgreSQL 9.4
-- ============================================================================
-- ACUMULATIVO E IDEMPOTENTE. Re-ejecutable sin efectos secundarios.
-- La numeración de FASES corresponde a las fases del plan del proyecto; los
-- "huecos" (2, 3, 4, 5, 7, 8) son fases que sólo cambiaron código Java.
-- ============================================================================
--
-- CHANGELOG DE SCHEMA
-- -------------------------------------------------------------------------
-- FASE 1  (2026-04-23)  impresoras.id_bodega (FK bodegas)
--                       impresoras.tipo_notificaciones BOOLEAN
--                       índice idx_mov_prod_tipo_fecha
-- FASE 6  (2026-04-23)  detalle_factura.es_novedad BOOLEAN
--                       detalle_factura.motivo_novedad VARCHAR(200)
--                       índice parcial idx_detalle_factura_novedad
-- FASE 9  (2026-04-23)  setval() sobre 10 secuencias para resincronizar con
--                       MAX(id) (reparación: el legado insertaba con
--                       MAX(id)+1 sin usar nextval).
-- FASE 10 (2026-04-23)  Tabla novedades_facturas (vista operacional de
--                       novedades con flujo PENDIENTE→REVISADO→RESUELTO/IGNORADO)
--                       + 5 índices.
--
-- CAMBIOS CODE-ONLY (no requieren SQL, pero sí redeploy de binarios):
--   * wo-printer: jar completo (todas las fases 1-10)
--   * bodega_agroinsumos: recompilación — se añadió constante
--     TIPO_AUTOMATICO="AUTOMATICO" en DBstock_productos.java (fase 1)
--
-- APLICACIÓN RECOMENDADA:
--   1) pg_dump -Fc bodega_nuevo > backup_pre_wo_printer.dump
--   2) psql -d bodega_nuevo -v ON_ERROR_STOP=1 -f migracion_produccion.sql
--   3) Verificar con la query del bloque "VERIFICACIÓN" al final
--   4) Desplegar wo-printer.jar actualizado
--   5) Desplegar bodega_agroinsumos recompilado (si hay instancias corriendo)
-- ============================================================================


-- ============================================================================
-- FASE 1 — Cambios estructurales base
-- Fecha: 2026-04-23
-- Descripción:
--   * Añadir relación impresora ↔ bodega (impresoras.id_bodega)
--   * Añadir tipo "notificaciones" a impresoras
--   * Índice para consulta de última bodega con movimiento por producto
-- ============================================================================

-- 1.1 Columna id_bodega en impresoras (relación directa con bodega del sistema)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'impresoras'
          AND column_name  = 'id_bodega'
    ) THEN
        ALTER TABLE impresoras
            ADD COLUMN id_bodega INTEGER;
        ALTER TABLE impresoras
            ADD CONSTRAINT fk_impresoras_bodega
            FOREIGN KEY (id_bodega) REFERENCES bodegas(id);
    END IF;
END$$;

-- 1.2 Flag tipo_notificaciones en impresoras
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'impresoras'
          AND column_name  = 'tipo_notificaciones'
    ) THEN
        ALTER TABLE impresoras
            ADD COLUMN tipo_notificaciones BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END$$;

-- 1.3 Índice compuesto para acelerar búsquedas de "última bodega con movimiento"
-- Se usa en BodegaAsignacionService cuando todas las bodegas tienen disponible <= 0
-- (PostgreSQL 9.4 no soporta CREATE INDEX IF NOT EXISTS -> se emula con DO block)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname  = 'idx_mov_prod_tipo_fecha'
    ) THEN
        CREATE INDEX idx_mov_prod_tipo_fecha
            ON movimientos_inventario (id_producto, tipo, fecha DESC, id DESC);
    END IF;
END$$;


-- ============================================================================
-- FASE 6 — Orquestación de órdenes automáticas
-- Fecha: 2026-04-23
-- Descripción:
--   * Flags en detalle_factura para identificar ítems que se convirtieron
--     en novedad (producto no encontrado, inactivo, sin stock, cantidad 0).
--   * Índice para consulta de novedades por factura.
-- ============================================================================

-- 6.1 Columnas de novedad en detalle_factura
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'detalle_factura'
          AND column_name  = 'es_novedad'
    ) THEN
        ALTER TABLE detalle_factura
            ADD COLUMN es_novedad BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'detalle_factura'
          AND column_name  = 'motivo_novedad'
    ) THEN
        ALTER TABLE detalle_factura
            ADD COLUMN motivo_novedad VARCHAR(200);
    END IF;
END$$;

-- 6.2 Índice parcial para consultar novedades
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname  = 'idx_detalle_factura_novedad'
    ) THEN
        CREATE INDEX idx_detalle_factura_novedad
            ON detalle_factura (factura_id) WHERE es_novedad = TRUE;
    END IF;
END$$;


-- ============================================================================
-- FASE 9 — Sincronización de secuencias (REPARACIÓN)
-- Fecha: 2026-04-23
-- Descripción:
--   El sistema legado (bodega_agroinsumos) inserta con id explícito calculado
--   como MAX(id)+1 en lugar de usar nextval(seq), por lo que las secuencias
--   quedan muy atrás respecto a MAX(id). wo-printer inserta con la estrategia
--   SERIAL + RETURNING id, la cual falla con "llave duplicada" cuando la
--   secuencia está detrás.
--   Este bloque resincroniza las secuencias de todas las tablas involucradas
--   en el flujo de wo-printer (y algunas adyacentes) a MAX(id)+1.
--   Idempotente: setval(MAX+1) es seguro de re-ejecutar.
-- ============================================================================

DO $$
DECLARE
    r RECORD;
    tablas TEXT[] := ARRAY[
        'facturas_cabeceras',
        'facturas_detalles',
        'facturas_impresas',
        'detalle_factura',
        'movimientos_inventario',
        'contactos',
        'users',
        'productos',
        'bodegas',
        'stock'
    ];
    t TEXT;
    max_id BIGINT;
    seq_name TEXT;
BEGIN
    FOREACH t IN ARRAY tablas LOOP
        seq_name := t || '_id_seq';
        -- Sólo procesa si existe la secuencia
        IF EXISTS (SELECT 1 FROM pg_class WHERE relkind = 'S' AND relname = seq_name) THEN
            EXECUTE format('SELECT COALESCE(MAX(id), 0) FROM %I', t) INTO max_id;
            -- setval con is_called=false si max=0 (nextval devolverá 1),
            -- con is_called=true si max>=1 (nextval devolverá max+1)
            IF max_id > 0 THEN
                EXECUTE format('SELECT setval(%L, %s, true)', seq_name, max_id);
            ELSE
                EXECUTE format('SELECT setval(%L, 1, false)', seq_name);
            END IF;
            RAISE NOTICE 'Secuencia % -> %', seq_name, max_id;
        END IF;
    END LOOP;
END$$;


-- ============================================================================
-- FASE 10 — Tabla operacional de novedades
-- Fecha: 2026-04-23
-- Descripción:
--   Tabla purpose-built para gestionar novedades generadas por wo-printer con
--   flujo de revisión (PENDIENTE/REVISADO/RESUELTO/IGNORADO). Los registros se
--   siguen reflejando también en detalle_factura (es_novedad=true) para el
--   ticket de novedades; esta tabla es la vista operativa consultable.
-- ============================================================================

CREATE TABLE IF NOT EXISTS novedades_facturas (
    id                     SERIAL PRIMARY KEY,
    factura_impresa_id     INTEGER REFERENCES facturas_impresas(id),
    numero_factura         VARCHAR(50) NOT NULL,
    fecha_deteccion        TIMESTAMP NOT NULL DEFAULT now(),
    tipo                   VARCHAR(30) NOT NULL,
    codigo_original        VARCHAR(100),
    codigo_normalizado     VARCHAR(100),
    descripcion            VARCHAR(500),
    cantidad               DOUBLE PRECISION,
    motivo                 VARCHAR(300),
    estado_revision        VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    id_producto_asociado   INTEGER REFERENCES productos(id),
    observacion_revision   VARCHAR(500),
    revisado_por           INTEGER REFERENCES users(id),
    fecha_revision         TIMESTAMP
);

-- Índices para los filtros más comunes
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname='idx_novedades_tipo') THEN
        CREATE INDEX idx_novedades_tipo ON novedades_facturas(tipo);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname='idx_novedades_estado') THEN
        CREATE INDEX idx_novedades_estado ON novedades_facturas(estado_revision);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname='idx_novedades_factura') THEN
        CREATE INDEX idx_novedades_factura ON novedades_facturas(numero_factura);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname='idx_novedades_codigo') THEN
        CREATE INDEX idx_novedades_codigo ON novedades_facturas(codigo_normalizado);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname='idx_novedades_fecha') THEN
        CREATE INDEX idx_novedades_fecha ON novedades_facturas(fecha_deteccion DESC);
    END IF;
END$$;


-- ============================================================================
-- VERIFICACIÓN POST-APLICACIÓN
-- ============================================================================
-- Ejecutar este bloque manualmente después de la migración. Debe retornar
-- exactamente 12 filas con ok=TRUE. Si alguna sale FALSE, algo no se aplicó.
-- ============================================================================
/*
SELECT '1.1 impresoras.id_bodega' AS item,
       EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='impresoras' AND column_name='id_bodega') AS ok
UNION ALL SELECT '1.2 impresoras.tipo_notificaciones',
       EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='impresoras' AND column_name='tipo_notificaciones')
UNION ALL SELECT '1.3 idx_mov_prod_tipo_fecha',
       EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='idx_mov_prod_tipo_fecha')
UNION ALL SELECT '6.1 detalle_factura.es_novedad',
       EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='detalle_factura' AND column_name='es_novedad')
UNION ALL SELECT '6.1 detalle_factura.motivo_novedad',
       EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='detalle_factura' AND column_name='motivo_novedad')
UNION ALL SELECT '6.2 idx_detalle_factura_novedad',
       EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='idx_detalle_factura_novedad')
UNION ALL SELECT '10 tabla novedades_facturas',
       EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='novedades_facturas')
UNION ALL SELECT '10 idx_novedades_tipo',
       EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='idx_novedades_tipo')
UNION ALL SELECT '10 idx_novedades_estado',
       EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='idx_novedades_estado')
UNION ALL SELECT '10 idx_novedades_factura',
       EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='idx_novedades_factura')
UNION ALL SELECT '10 idx_novedades_codigo',
       EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='idx_novedades_codigo')
UNION ALL SELECT '10 idx_novedades_fecha',
       EXISTS (SELECT 1 FROM pg_indexes WHERE indexname='idx_novedades_fecha');
*/


-- ============================================================================
-- FIN DE MIGRACIÓN
-- ============================================================================
