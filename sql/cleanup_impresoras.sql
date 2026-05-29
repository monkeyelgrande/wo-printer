-- ============================================================================
-- LIMPIEZA — Eliminación de la integración de impresión de wo-printer
-- Base de datos objetivo: bodega_nuevo  |  PostgreSQL 9.4+
-- ============================================================================
-- A partir de la versión actual, wo-printer ya NO imprime tirillas: solo
-- procesa el Excel de WorldOffice y genera las órdenes/novedades. La impresión
-- y notificación la realiza el sistema aliado "control bodega".
--
-- Este script elimina de una BD ya migrada las tablas propias de la antigua
-- integración de impresión:
--   * log_impresiones   (auditoría de impresiones; referencia a impresoras)
--   * impresoras         (impresoras POS y su asignación a bodegas)
--
-- IDEMPOTENTE: usa DROP TABLE IF EXISTS y es seguro de re-ejecutar.
--
-- ⚠️ DESTRUCTIVO: borra esas tablas y todos sus datos. Haz backup antes:
--     pg_dump -Fc bodega_nuevo > backup_pre_cleanup_impresoras.dump
--
-- APLICACIÓN:
--     psql -d bodega_nuevo -v ON_ERROR_STOP=1 -f cleanup_impresoras.sql
-- ============================================================================

BEGIN;

-- 1. log_impresiones (tiene FK impresora_id -> impresoras.id; se elimina primero)
DROP TABLE IF EXISTS log_impresiones;

-- 2. impresoras (la FK fk_impresoras_bodega cae junto con la tabla)
DROP TABLE IF EXISTS impresoras;

COMMIT;

-- ============================================================================
-- VERIFICACIÓN POST-LIMPIEZA
-- ============================================================================
-- Debe retornar 2 filas con existe=FALSE.
-- ============================================================================
/*
SELECT 'impresoras' AS tabla,
       EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema='public' AND table_name='impresoras') AS existe
UNION ALL SELECT 'log_impresiones',
       EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema='public' AND table_name='log_impresiones');
*/
