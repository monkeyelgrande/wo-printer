-- ============================================================
-- WorldOffice Printer - Esquema de Base de Datos
-- Ejecutar en tu base de datos PostgreSQL existente
-- ============================================================

-- NOTA: Si ya tienes la tabla vieja, ejecuta primero:
-- DROP TABLE IF EXISTS log_impresiones;
-- DROP TABLE IF EXISTS impresoras;

-- Tabla de impresoras POS configuradas
CREATE TABLE IF NOT EXISTS impresoras (
    id SERIAL PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,
    nombre_windows VARCHAR(200) NOT NULL,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    fecha_creacion TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
	tipo_bodega BOOLEAN NOT NULL DEFAULT TRUE,
	tipo_venta BOOLEAN NOT NULL DEFAULT FALSE
);

-- Tabla de log de impresiones realizadas
CREATE TABLE IF NOT EXISTS log_impresiones (
    id SERIAL PRIMARY KEY,
    numero_factura VARCHAR(50) NOT NULL,
    impresora_id INTEGER REFERENCES impresoras(id),
    archivo_origen VARCHAR(500) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    mensaje_error TEXT,
    fecha_procesado TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Datos de ejemplo (usa los nombres exactos de tus impresoras en Windows)
INSERT INTO impresoras (nombre, nombre_windows) VALUES
    ('Impresora Bodega 1', 'POS80C'),
    ('Impresora Bodega 2', 'POS80C1');
