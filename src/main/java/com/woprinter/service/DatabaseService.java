package com.woprinter.service;

import com.woprinter.config.AppConfig;
import com.woprinter.model.ConfiguracionEmpresa;
import com.woprinter.model.Impresora;
import com.woprinter.model.NovedadRegistro;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseService {

    private static DatabaseService instance;
    private final AppConfig config;

    private DatabaseService() {
        config = AppConfig.getInstance();
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("[DB] Driver PostgreSQL no encontrado: " + e.getMessage());
        }
    }

    public static synchronized DatabaseService getInstance() {
        if (instance == null) {
            instance = new DatabaseService();
        }
        return instance;
    }

    Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                config.getDbUrl(), config.getDbUser(), config.getDbPassword());
    }

    public boolean testConnection() {
        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            System.err.println("[DB] Error de conexion: " + e.getMessage());
            return false;
        }
    }

    // ============================================================
    // Configuracion Empresa
    // ============================================================

    public ConfiguracionEmpresa getConfiguracionEmpresa() {
        ConfiguracionEmpresa ce = new ConfiguracionEmpresa();
        String sql = "SELECT nombre_negocio, nit_negocio, contacto_negocio, direccion FROM configuraciones LIMIT 1";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                ce.setNombre(rs.getString("nombre_negocio"));
                ce.setNit(rs.getString("nit_negocio"));
                ce.setContacto(rs.getString("contacto_negocio"));
                ce.setDireccion(rs.getString("direccion"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo configuracion empresa: " + e.getMessage());
        }
        return ce;
    }

    // ============================================================
    // Impresoras
    // ============================================================

    private static final String SQL_SELECT_IMPRESORAS =
            "SELECT i.id, i.nombre, i.nombre_windows, i.activa, "
          + "       i.tipo_bodega, i.tipo_venta, i.tipo_notificaciones, "
          + "       i.id_bodega, b.nombre AS nombre_bodega "
          + "FROM impresoras i LEFT JOIN bodegas b ON b.id = i.id_bodega ";

    public List<Impresora> getImpresorasActivas() {
        return queryImpresoras(SQL_SELECT_IMPRESORAS + "WHERE i.activa = TRUE ORDER BY i.id");
    }

    public List<Impresora> getAllImpresoras() {
        return queryImpresoras(SQL_SELECT_IMPRESORAS + "ORDER BY i.id");
    }

    private List<Impresora> queryImpresoras(String sql) {
        List<Impresora> lista = new ArrayList<Impresora>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int idBod = rs.getInt("id_bodega");
                Integer idBodega = rs.wasNull() ? null : idBod;
                lista.add(new Impresora(
                        rs.getInt("id"),
                        rs.getString("nombre"),
                        rs.getString("nombre_windows"),
                        rs.getBoolean("activa"),
                        rs.getBoolean("tipo_bodega"),
                        rs.getBoolean("tipo_venta"),
                        rs.getBoolean("tipo_notificaciones"),
                        idBodega,
                        rs.getString("nombre_bodega")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo impresoras: " + e.getMessage());
        }
        return lista;
    }

    public void registrarImpresion(String numeroFactura, Integer impresoraId,
                                    String archivoOrigen, String estado, String mensajeError,
                                    String concepto) {
        String sql = "INSERT INTO log_impresiones (numero_factura, impresora_id, archivo_origen, estado, mensaje_error, concepto) "
                   + "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, numeroFactura);
            if (impresoraId != null && impresoraId > 0) {
                ps.setInt(2, impresoraId);
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            ps.setString(3, archivoOrigen);
            ps.setString(4, estado);
            ps.setString(5, mensajeError);
            ps.setString(6, concepto);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Error registrando impresion: " + e.getMessage());
        }
    }

    public boolean agregarImpresora(String nombre, String nombreWindows,
                                     boolean tipoVenta, boolean tipoNotificaciones, Integer idBodega) {
        String sql = "INSERT INTO impresoras (nombre, nombre_windows, tipo_bodega, tipo_venta, "
                   + "tipo_notificaciones, id_bodega) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nombre);
            ps.setString(2, nombreWindows);
            ps.setBoolean(3, idBodega != null);                 // tipo_bodega derivado: true si hay id_bodega
            ps.setBoolean(4, tipoVenta);
            ps.setBoolean(5, tipoNotificaciones);
            if (idBodega != null) ps.setInt(6, idBodega);
            else ps.setNull(6, Types.INTEGER);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] Error agregando impresora: " + e.getMessage());
            return false;
        }
    }

    public boolean actualizarImpresora(int id, String nombre, String nombreWindows, boolean activa,
                                        boolean tipoVenta, boolean tipoNotificaciones, Integer idBodega) {
        String sql = "UPDATE impresoras SET nombre = ?, nombre_windows = ?, activa = ?, "
                   + "tipo_bodega = ?, tipo_venta = ?, tipo_notificaciones = ?, id_bodega = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nombre);
            ps.setString(2, nombreWindows);
            ps.setBoolean(3, activa);
            ps.setBoolean(4, idBodega != null);
            ps.setBoolean(5, tipoVenta);
            ps.setBoolean(6, tipoNotificaciones);
            if (idBodega != null) ps.setInt(7, idBodega);
            else ps.setNull(7, Types.INTEGER);
            ps.setInt(8, id);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] Error actualizando impresora: " + e.getMessage());
            return false;
        }
    }

    public List<Object[]> getBodegas() {
        List<Object[]> lista = new ArrayList<Object[]>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, nombre FROM bodegas ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) lista.add(new Object[]{rs.getInt(1), rs.getString(2)});
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo bodegas: " + e.getMessage());
        }
        return lista;
    }

    // ============================================================
    // Órdenes generadas automáticamente por wo-printer
    // ============================================================

    /**
     * Lista órdenes (facturas Salida) generadas por wo-printer.
     * Filtra por número de factura (ILIKE) si se provee.
     */
    public List<Object[]> getOrdenesGeneradas(String filtroNumero, int limite) {
        List<Object[]> lista = new ArrayList<Object[]>();
        StringBuilder sql = new StringBuilder(
            "SELECT fc.id, fc.codigo, fc.fecha, fc.hora, "
          + "       COALESCE(b.nombre,'?') AS bodega, "
          + "       COALESCE(u.user_name,'?') AS usuario, "
          + "       (SELECT COUNT(*) FROM facturas_detalles fd WHERE fd.id_cabecera = fc.id) AS items "
          + "FROM facturas_cabeceras fc "
          + "LEFT JOIN bodegas b ON b.id = fc.id_bodega "
          + "LEFT JOIN users u ON u.id = fc.id_user "
          + "WHERE fc.tipo_factura = 'Salida' "
          + "  AND EXISTS (SELECT 1 FROM facturas_impresas fi WHERE fi.numero_factura = fc.codigo) ");
        if (filtroNumero != null && !filtroNumero.trim().isEmpty()) {
            sql.append("AND fc.codigo ILIKE ? ");
        }
        sql.append("ORDER BY fc.id DESC LIMIT ?");

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            if (filtroNumero != null && !filtroNumero.trim().isEmpty()) {
                ps.setString(i++, "%" + filtroNumero.trim() + "%");
            }
            ps.setInt(i, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Object[]{
                            rs.getInt("id"),
                            rs.getString("codigo") != null ? rs.getString("codigo") : "",
                            rs.getDate("fecha") != null ? rs.getDate("fecha").toString() : "",
                            rs.getString("hora") != null ? rs.getString("hora") : "",
                            rs.getString("bodega"),
                            rs.getString("usuario"),
                            rs.getInt("items")
                    });
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo ordenes: " + e.getMessage());
        }
        return lista;
    }

    public List<Object[]> getDetalleOrden(int idCabecera) {
        List<Object[]> lista = new ArrayList<Object[]>();
        String sql = "SELECT COALESCE(p.codigo_barras,'?') AS codigo, "
                   + "       COALESCE(p.descripcion,'?') AS descripcion, "
                   + "       fd.cantidad "
                   + "FROM facturas_detalles fd "
                   + "LEFT JOIN productos p ON p.id = fd.id_producto "
                   + "WHERE fd.id_cabecera = ? ORDER BY fd.id";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idCabecera);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Object[]{
                            rs.getString("codigo"),
                            rs.getString("descripcion"),
                            rs.getDouble("cantidad")
                    });
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo detalle orden: " + e.getMessage());
        }
        return lista;
    }

    // ============================================================
    // Novedades operacionales (tabla novedades_facturas)
    // ============================================================

    public static final String[] TIPOS_NOVEDAD = {
        "CODIGO_INVALIDO", "NO_EXISTE", "INACTIVO", "SIN_HISTORICO", "CANTIDAD_CERO", "ERROR_LOOKUP"
    };
    public static final String[] ESTADOS_NOVEDAD = {
        "PENDIENTE", "REVISADO", "RESUELTO", "IGNORADO"
    };

    public int contarNovedadesPendientes() {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM novedades_facturas WHERE estado_revision = 'PENDIENTE'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            System.err.println("[DB] Error contando novedades: " + e.getMessage());
        }
        return 0;
    }

    public List<NovedadRegistro> getNovedades(String tipo, String estado, String codigo,
                                               String numeroFactura, int limite) {
        List<NovedadRegistro> lista = new ArrayList<NovedadRegistro>();
        StringBuilder sql = new StringBuilder(
            "SELECT n.*, "
          + "       p.codigo_barras AS prod_codigo, "
          + "       u.user_name AS revisor_nombre "
          + "FROM novedades_facturas n "
          + "LEFT JOIN productos p ON p.id = n.id_producto_asociado "
          + "LEFT JOIN users u ON u.id = n.revisado_por "
          + "WHERE 1=1 ");
        List<Object> params = new ArrayList<Object>();
        if (tipo != null && !tipo.isEmpty()) {
            sql.append("AND n.tipo = ? ");
            params.add(tipo);
        }
        if (estado != null && !estado.isEmpty()) {
            sql.append("AND n.estado_revision = ? ");
            params.add(estado);
        }
        if (codigo != null && !codigo.trim().isEmpty()) {
            sql.append("AND (n.codigo_normalizado ILIKE ? OR n.codigo_original ILIKE ?) ");
            params.add("%" + codigo.trim() + "%");
            params.add("%" + codigo.trim() + "%");
        }
        if (numeroFactura != null && !numeroFactura.trim().isEmpty()) {
            sql.append("AND n.numero_factura ILIKE ? ");
            params.add("%" + numeroFactura.trim() + "%");
        }
        sql.append("ORDER BY n.fecha_deteccion DESC, n.id DESC LIMIT ?");
        params.add(limite);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(mapNovedad(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo novedades: " + e.getMessage());
        }
        return lista;
    }

    private NovedadRegistro mapNovedad(ResultSet rs) throws SQLException {
        NovedadRegistro n = new NovedadRegistro();
        n.setId(rs.getInt("id"));
        int facturaImpresaId = rs.getInt("factura_impresa_id");
        n.setFacturaImpresaId(rs.wasNull() ? null : facturaImpresaId);
        n.setNumeroFactura(rs.getString("numero_factura"));
        n.setFechaDeteccion(rs.getTimestamp("fecha_deteccion"));
        n.setTipo(rs.getString("tipo"));
        n.setCodigoOriginal(rs.getString("codigo_original"));
        n.setCodigoNormalizado(rs.getString("codigo_normalizado"));
        n.setDescripcion(rs.getString("descripcion"));
        n.setCantidad(rs.getDouble("cantidad"));
        n.setMotivo(rs.getString("motivo"));
        n.setEstadoRevision(rs.getString("estado_revision"));
        int idProd = rs.getInt("id_producto_asociado");
        n.setIdProductoAsociado(rs.wasNull() ? null : idProd);
        n.setCodigoProductoAsociado(rs.getString("prod_codigo"));
        n.setObservacionRevision(rs.getString("observacion_revision"));
        int revPor = rs.getInt("revisado_por");
        n.setRevisadoPor(rs.wasNull() ? null : revPor);
        n.setRevisadoPorNombre(rs.getString("revisor_nombre"));
        n.setFechaRevision(rs.getTimestamp("fecha_revision"));
        return n;
    }

    /**
     * Actualiza el estado de revisión de una novedad.
     * Si nuevoEstado == RESUELTO, idProducto debe apuntar al producto creado/correspondiente.
     */
    public boolean actualizarNovedad(int idNovedad, String nuevoEstado, Integer idProductoAsociado,
                                      String observacion, int idUser) {
        String sql = "UPDATE novedades_facturas SET estado_revision = ?, "
                   + " id_producto_asociado = ?, observacion_revision = ?, "
                   + " revisado_por = ?, fecha_revision = now() "
                   + "WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nuevoEstado);
            if (idProductoAsociado != null) ps.setInt(2, idProductoAsociado);
            else ps.setNull(2, Types.INTEGER);
            ps.setString(3, observacion);
            ps.setInt(4, idUser);
            ps.setInt(5, idNovedad);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DB] Error actualizando novedad: " + e.getMessage());
            return false;
        }
    }

    public Integer buscarProductoIdPorCodigo(String codigoBarras) {
        if (codigoBarras == null || codigoBarras.trim().isEmpty()) return null;
        String sql = "SELECT id FROM productos WHERE UPPER(TRIM(codigo_barras)) = UPPER(TRIM(?)) LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, codigoBarras);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error buscando producto: " + e.getMessage());
        }
        return null;
    }

    public List<Object[]> getNovedadesFactura(String numeroFactura) {
        List<Object[]> lista = new ArrayList<Object[]>();
        String sql = "SELECT df.codigo_producto, df.descripcion, df.cantidad, df.motivo_novedad "
                   + "FROM detalle_factura df "
                   + "JOIN facturas_impresas fi ON fi.id = df.factura_id "
                   + "WHERE fi.numero_factura = ? AND df.es_novedad = TRUE ORDER BY df.id";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, numeroFactura);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Object[]{
                            rs.getString("codigo_producto"),
                            rs.getString("descripcion") != null ? rs.getString("descripcion") : "",
                            rs.getDouble("cantidad"),
                            rs.getString("motivo_novedad") != null ? rs.getString("motivo_novedad") : ""
                    });
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo novedades: " + e.getMessage());
        }
        return lista;
    }

    public boolean eliminarImpresora(int id) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            // Primero eliminar registros del log asociados
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM log_impresiones WHERE impresora_id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }

            // Luego eliminar la impresora
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM impresoras WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            System.err.println("[DB] Error eliminando impresora: " + e.getMessage());
            return false;
        }
    }

    // ============================================================
    // Facturas Impresas
    // ============================================================

    public void guardarFacturaImpresa(com.woprinter.model.Factura factura) {
        String sqlBuscar = "SELECT id FROM facturas_impresas WHERE numero_factura = ?";
        String sqlContarDetalle = "SELECT COUNT(*) FROM detalle_factura WHERE factura_id = ?";
        String sqlCabecera = "INSERT INTO facturas_impresas (numero_factura, cliente, fecha_factura, vendedor, concepto, forma_pago, prefijo, empresa) "
                           + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        String sqlDetalle = "INSERT INTO detalle_factura (factura_id, codigo_producto, descripcion, cantidad, iva, precio_unitario, total_linea) "
                          + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            int facturaId = -1;
            boolean detalleYaExiste = false;

            try (PreparedStatement ps = conn.prepareStatement(sqlBuscar)) {
                ps.setString(1, factura.getNumeroCompleto());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    facturaId = rs.getInt(1);
                    System.out.println("[DB] Factura ya existe con ID: " + facturaId);
                }
                rs.close();
            }

            if (facturaId > 0) {
                try (PreparedStatement ps = conn.prepareStatement(sqlContarDetalle)) {
                    ps.setInt(1, facturaId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next() && rs.getInt(1) > 0) {
                        detalleYaExiste = true;
                    }
                    rs.close();
                }
                if (detalleYaExiste) {
                    System.out.println("[DB] Factura " + factura.getNumeroCompleto() + " ya tiene detalle, omitiendo");
                    conn.commit();
                    return;
                }
                System.out.println("[DB] Factura existe sin detalle, insertando productos...");
            }

            if (facturaId < 0) {
                try (PreparedStatement ps = conn.prepareStatement(sqlCabecera)) {
                    ps.setString(1, factura.getNumeroCompleto());
                    ps.setString(2, factura.getCliente());
                    if (factura.getFecha() != null) {
                        ps.setDate(3, new java.sql.Date(factura.getFecha().getTime()));
                    } else {
                        ps.setNull(3, Types.DATE);
                    }
                    ps.setString(4, factura.getVendedor());
                    ps.setString(5, factura.getConcepto());
                    ps.setString(6, factura.getFormaPago());
                    ps.setString(7, factura.getPrefijo());
                    ps.setString(8, factura.getEmpresa());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        facturaId = rs.getInt(1);
                        System.out.println("[DB] Cabecera insertada con ID: " + facturaId);
                    }
                    rs.close();
                }
            }

            if (facturaId < 0) {
                conn.rollback();
                System.err.println("[DB] ERROR: No se pudo obtener el ID de la factura");
                return;
            }

            int insertados = 0;
            for (com.woprinter.model.ItemFactura item : factura.getItems()) {
                try (PreparedStatement ps = conn.prepareStatement(sqlDetalle)) {
                    ps.setInt(1, facturaId);
                    ps.setString(2, item.getCodigo());
                    ps.setString(3, item.getDescripcion());
                    ps.setDouble(4, item.getCantidad());
                    ps.setDouble(5, item.getIva());
                    ps.setDouble(6, item.getPrecioUnitario());
                    ps.setDouble(7, item.getTotalLinea());
                    ps.executeUpdate();
                    insertados++;
                }
            }

            conn.commit();
            System.out.println("[DB] Factura guardada: " + factura.getNumeroCompleto()
                    + " - " + insertados + "/" + factura.getItems().size() + " productos insertados");

        } catch (SQLException e) {
            System.err.println("[DB] Error guardando factura: " + e.getMessage());
        }
    }

    /**
     * Obtiene las facturas impresas del dia de hoy.
     */
    public List<String[]> getFacturasDeHoy() {
        List<String[]> resultados = new ArrayList<String[]>();
        String sql = "SELECT numero_factura, cliente, fecha_factura, fecha_impresion "
                   + "FROM facturas_impresas "
                   + "WHERE DATE(fecha_impresion) = CURRENT_DATE "
                   + "ORDER BY fecha_impresion DESC";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                resultados.add(new String[]{
                        rs.getString("numero_factura"),
                        rs.getString("cliente") != null ? rs.getString("cliente") : "",
                        rs.getDate("fecha_factura") != null ? rs.getDate("fecha_factura").toString() : "",
                        rs.getTimestamp("fecha_impresion").toString()
                });
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo facturas de hoy: " + e.getMessage());
        }
        return resultados;
    }

    /**
     * Reconstruye un objeto Factura completo desde la BD para reimprimir.
     */
    public com.woprinter.model.Factura reconstruirFactura(String numeroFactura) {
        com.woprinter.model.Factura factura = new com.woprinter.model.Factura();

        // Cabecera
        String sqlCab = "SELECT numero_factura, cliente, fecha_factura, vendedor, concepto, forma_pago, prefijo, empresa "
                      + "FROM facturas_impresas WHERE numero_factura = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlCab)) {
            ps.setString(1, numeroFactura);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                factura.setPrefijo(rs.getString("prefijo") != null ? rs.getString("prefijo") : "");
                factura.setCliente(rs.getString("cliente"));
                factura.setFecha(rs.getDate("fecha_factura"));
                factura.setVendedor(rs.getString("vendedor") != null ? rs.getString("vendedor") : "");
                factura.setConcepto(rs.getString("concepto") != null ? rs.getString("concepto") : "");
                factura.setFormaPago(rs.getString("forma_pago") != null ? rs.getString("forma_pago") : "");
                factura.setEmpresa(rs.getString("empresa") != null ? rs.getString("empresa") : "");

                // Extraer numero del numero_factura (ej: "FVE-35446" -> 35446)
                String numStr = numeroFactura;
                int guion = numStr.lastIndexOf('-');
                if (guion >= 0 && guion < numStr.length() - 1) {
                    try {
                        factura.setNumero(Integer.parseInt(numStr.substring(guion + 1)));
                    } catch (NumberFormatException e) {
                        factura.setNumero(0);
                    }
                }
            }
            rs.close();
        } catch (SQLException e) {
            System.err.println("[DB] Error reconstruyendo cabecera: " + e.getMessage());
            return null;
        }

        // Detalle
        String sqlDet = "SELECT d.codigo_producto, d.descripcion, d.cantidad, d.iva, d.precio_unitario, d.total_linea "
                      + "FROM detalle_factura d "
                      + "JOIN facturas_impresas f ON d.factura_id = f.id "
                      + "WHERE f.numero_factura = ? ORDER BY d.id";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlDet)) {
            ps.setString(1, numeroFactura);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                com.woprinter.model.ItemFactura item = new com.woprinter.model.ItemFactura();
                item.setCodigo(rs.getString("codigo_producto"));
                item.setDescripcion(rs.getString("descripcion"));
                item.setCantidad(rs.getDouble("cantidad"));
                item.setIva(rs.getDouble("iva"));
                item.setPrecioUnitario(rs.getDouble("precio_unitario"));
                item.setTotalLinea(rs.getDouble("total_linea"));
                factura.addItem(item);
            }
            rs.close();
        } catch (SQLException e) {
            System.err.println("[DB] Error reconstruyendo detalle: " + e.getMessage());
        }

        return factura;
    }

    public List<String[]> buscarFacturas(String numeroBusqueda) {
        List<String[]> resultados = new ArrayList<String[]>();
        String sql = "SELECT numero_factura, cliente, fecha_factura, fecha_impresion "
                   + "FROM facturas_impresas "
                   + "WHERE numero_factura ILIKE ? "
                   + "ORDER BY fecha_impresion DESC LIMIT 50";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + numeroBusqueda + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                resultados.add(new String[]{
                        rs.getString("numero_factura"),
                        rs.getString("cliente") != null ? rs.getString("cliente") : "",
                        rs.getDate("fecha_factura") != null ? rs.getDate("fecha_factura").toString() : "",
                        rs.getTimestamp("fecha_impresion").toString()
                });
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error buscando facturas: " + e.getMessage());
        }
        return resultados;
    }

    public List<String[]> obtenerProductosFactura(String numeroFactura) {
        List<String[]> productos = new ArrayList<String[]>();
        String sql = "SELECT d.codigo_producto, d.descripcion, d.cantidad "
                   + "FROM detalle_factura d "
                   + "JOIN facturas_impresas f ON d.factura_id = f.id "
                   + "WHERE f.numero_factura = ? "
                   + "ORDER BY d.id";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, numeroFactura);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                productos.add(new String[]{
                        rs.getString("codigo_producto"),
                        rs.getString("descripcion") != null ? rs.getString("descripcion") : "",
                        String.valueOf(rs.getDouble("cantidad"))
                });
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo productos: " + e.getMessage());
        }
        return productos;
    }
}