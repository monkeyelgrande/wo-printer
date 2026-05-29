-- ============================================================================
-- Rollback de pruebas wo-printer (FVE-90001..90005)
-- Ejecutar SOLO en desarrollo para limpiar estado y re-correr los archivos de prueba.
-- ============================================================================

BEGIN;

-- 1. Revertir pendientes en stock_productos (sumatoria por producto+bodega)
UPDATE stock_productos sp
SET pendientes = GREATEST(pendientes - x.delta, 0),
    updated_at = now()
FROM (
    SELECT fd.id_producto, fc.id_bodega, SUM(fd.cantidad) AS delta
    FROM facturas_detalles fd
    JOIN facturas_cabeceras fc ON fc.id = fd.id_cabecera
    WHERE fc.codigo IN ('FVE-90001','FVE-90002','FVE-90003','FVE-90004','FVE-90005')
      AND EXISTS (SELECT 1 FROM facturas_impresas fi WHERE fi.numero_factura = fc.codigo)
    GROUP BY fd.id_producto, fc.id_bodega
) x
WHERE sp.id_producto = x.id_producto AND sp.id_bodega = x.id_bodega;

-- 2. Borrar movimientos AUTOMATICO asociados
DELETE FROM movimientos_inventario
WHERE tipo = 'AUTOMATICO'
  AND tabla_referencia = 'facturas_cabeceras'
  AND id_referencia IN (
      SELECT fc.id FROM facturas_cabeceras fc
      WHERE fc.codigo IN ('FVE-90001','FVE-90002','FVE-90003','FVE-90004','FVE-90005')
        AND EXISTS (SELECT 1 FROM facturas_impresas fi WHERE fi.numero_factura = fc.codigo)
  );

-- 3. Borrar detalles y cabeceras de las órdenes generadas
DELETE FROM facturas_detalles WHERE id_cabecera IN (
    SELECT fc.id FROM facturas_cabeceras fc
    WHERE fc.codigo IN ('FVE-90001','FVE-90002','FVE-90003','FVE-90004','FVE-90005')
      AND EXISTS (SELECT 1 FROM facturas_impresas fi WHERE fi.numero_factura = fc.codigo)
);
DELETE FROM facturas_cabeceras fc
WHERE fc.codigo IN ('FVE-90001','FVE-90002','FVE-90003','FVE-90004','FVE-90005')
  AND EXISTS (SELECT 1 FROM facturas_impresas fi WHERE fi.numero_factura = fc.codigo);

-- 4. Borrar facturas_impresas + detalle_factura
DELETE FROM detalle_factura WHERE factura_id IN (
    SELECT id FROM facturas_impresas
    WHERE numero_factura IN ('FVE-90001','FVE-90002','FVE-90003','FVE-90004','FVE-90005')
);
DELETE FROM facturas_impresas
WHERE numero_factura IN ('FVE-90001','FVE-90002','FVE-90003','FVE-90004','FVE-90005');

COMMIT;
