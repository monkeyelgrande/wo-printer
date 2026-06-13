package com.woprinter.service;

import com.woprinter.config.AppConfig;
import com.woprinter.model.*;
import com.woprinter.model.OrdenPorBodega.ItemOrden;
import com.woprinter.model.ResultadoLookupProducto.Estado;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Orquestador transaccional que convierte una {@link Factura} (Excel de WorldOffice)
 * en órdenes automáticas de Salida en bodega_nuevo.
 *
 * Flujo:
 *   1. Idempotencia vía facturas_impresas.numero_factura
 *   2. Consolidar ítems duplicados (mismo código -> sumar cantidades)
 *   3. Clasificar ítems:
 *        - CODIGO_INVALIDO / NO_EXISTE / INACTIVO / CANTIDAD_CERO  -> novedad
 *        - OK -> asignar a una o varias bodegas (BodegaAsignacionService)
 *   4. Resolver contacto (ContactoResolverService)
 *   5. Resolver usuario del sistema (UsuarioSistemaService)
 *   6. Por cada bodega resultante, INSERT facturas_cabeceras + facturas_detalles
 *      + UPDATE stock_productos.pendientes + INSERT movimientos_inventario (AUTOMATICO)
 *   7. Registrar facturas_impresas + detalle_factura (todos los ítems, flag es_novedad)
 *
 * Todo ejecuta en una sola transacción; ante cualquier falla se hace rollback.
 */
public class OrdenGeneratorService {

    private static final String TIPO_FACTURA_SALIDA = "Salida";
    private static final String TIPO_MOV_AUTOMATICO = "AUTOMATICO";
    private static final DateTimeFormatter FMT_HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    // El concepto de WorldOffice suele venir con la cédula embebida como "*NUMERO";
    // se omite en la observacion de la cabecera para que quede solo el texto del concepto.
    private static final java.util.regex.Pattern PATTERN_CEDULA_CONCEPTO =
            java.util.regex.Pattern.compile("\\*\\d+");

    private final DatabaseService db;
    private final ProductoLookupService lookupSvc;
    private final ContactoResolverService contactoSvc;
    private final UsuarioSistemaService userSvc;
    private final BodegaAsignacionService asignacionSvc;

    public OrdenGeneratorService() {
        this.db = DatabaseService.getInstance();
        this.lookupSvc = new ProductoLookupService();
        this.contactoSvc = new ContactoResolverService();
        this.userSvc = new UsuarioSistemaService();
        this.asignacionSvc = new BodegaAsignacionService();
    }

    public ResultadoOrden procesar(Factura factura) {
        String numeroFactura = factura.getNumeroCompleto();
        boolean generarOrdenes = AppConfig.getInstance().isGenerarOrdenesAutomaticas();
        Connection conn = null;
        try {
            conn = db.getConnection();
            conn.setAutoCommit(false);

            // 0. Resincronización preventiva de secuencias. El sistema legado inserta con
            // MAX(id)+1 sin avanzar nextval, lo que deja las secuencias atrasadas y hace
            // que nuestros RETURNING id colisionen con la PK. setval es no-transaccional
            // y barato; lo corremos al inicio para que todos los inserts posteriores salgan
            // con un id libre.
            resincronizarSecuenciasOrquestador(conn);

            // 1. Idempotencia
            if (facturaYaProcesada(conn, numeroFactura)) {
                conn.rollback();
                System.out.println("[ORDEN] " + numeroFactura + " ya procesada; se ignora");
                return ResultadoOrden.duplicada(numeroFactura);
            }

            // 2. Consolidar ítems duplicados
            List<ItemFactura> itemsConsolidados = consolidarPorCodigo(factura.getItems());

            // 3. Clasificar
            List<Novedad> novedades = new ArrayList<Novedad>();
            List<ItemClasificado> itemsOk = new ArrayList<ItemClasificado>();
            clasificarItems(conn, itemsConsolidados, itemsOk, novedades);

            // 4-6. Generación de órdenes (facturas_cabeceras + facturas_detalles +
            // stock_productos.pendientes + movimientos_inventario). Toda esta sección es
            // opcional: cuando ordenes.generar.automaticas=false el resto del flujo
            // (facturas_impresas, detalle_factura, novedades_facturas) sigue corriendo.
            ContactoInfo contacto = null;
            int idUser = -1;
            Map<Integer, OrdenPorBodega> porBodega = new LinkedHashMap<Integer, OrdenPorBodega>();

            if (generarOrdenes) {
                // 4 & 5. Contacto y usuario
                contacto = contactoSvc.resolver(conn, factura.getCliente(), factura.getConcepto());
                idUser = userSvc.resolverOCrear(conn);

                // 6. Asignar bodegas y generar una orden (factura Salida) por cada bodega
                Map<Integer, String> nombresBodega = cargarNombresBodegas(conn);
                asignarYAcumular(conn, itemsOk, porBodega, nombresBodega, novedades);

                for (OrdenPorBodega orden : porBodega.values()) {
                    generarFacturaSalida(conn, orden, factura, contacto, idUser);
                }
            } else {
                System.out.println("[ORDEN] " + numeroFactura
                        + " - generacion de ordenes deshabilitada (ordenes.generar.automaticas=false); "
                        + "se omiten facturas_cabeceras/facturas_detalles/stock/movimientos");
            }

            // 7. Guardar factura_impresa + detalle (todos los ítems con flags)
            int idFacturaImpresa = guardarFacturaImpresa(conn, factura, itemsConsolidados, novedades);

            // 7.5 Espejo operacional en novedades_facturas (solo si hay novedades)
            if (!novedades.isEmpty()) {
                guardarNovedadesOperativo(conn, idFacturaImpresa, factura.getNumeroCompleto(), novedades);
            }

            conn.commit();

            ResultadoOrden r = ResultadoOrden.exito(numeroFactura,
                    new ArrayList<OrdenPorBodega>(porBodega.values()), novedades);
            if (contacto != null) r.setIdContactoResuelto(contacto.getId());
            if (idUser != -1) r.setIdUserSistema(idUser);
            r.setIdFacturaImpresa(idFacturaImpresa);

            System.out.println("[ORDEN] " + numeroFactura + " OK: " + porBodega.size()
                    + " órdenes, " + novedades.size() + " novedades");
            return r;

        } catch (SQLException e) {
            rollbackQuiet(conn);
            System.err.println("[ORDEN] Error procesando " + numeroFactura + ": " + e.getMessage());
            e.printStackTrace();
            return ResultadoOrden.error(numeroFactura, e.getMessage());
        } finally {
            closeQuiet(conn);
        }
    }

    // ------------------------------------------------------------------------
    // 1. Idempotencia
    // ------------------------------------------------------------------------

    private boolean facturaYaProcesada(Connection conn, String numeroFactura) throws SQLException {
        String sql = "SELECT 1 FROM facturas_impresas WHERE numero_factura = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, numeroFactura);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------------------
    // 2. Consolidación de ítems duplicados
    // ------------------------------------------------------------------------

    private List<ItemFactura> consolidarPorCodigo(List<ItemFactura> items) {
        Map<String, ItemFactura> agrupados = new LinkedHashMap<String, ItemFactura>();
        for (ItemFactura item : items) {
            String key = ProductoLookupService.normalizar(item.getCodigo());
            ItemFactura existente = agrupados.get(key);
            if (existente == null) {
                ItemFactura copia = clonar(item);
                agrupados.put(key, copia);
            } else {
                existente.setCantidad(existente.getCantidad() + item.getCantidad());
                // Acumular tambien los valores de linea (el unitario y el % no cambian)
                existente.setValorIva(existente.getValorIva() + item.getValorIva());
                existente.setTotalLinea(existente.getTotalLinea() + item.getTotalLinea());
            }
        }
        return new ArrayList<ItemFactura>(agrupados.values());
    }

    private ItemFactura clonar(ItemFactura src) {
        ItemFactura c = new ItemFactura();
        c.setCodigo(src.getCodigo());
        c.setDescripcion(src.getDescripcion());
        c.setBodega(src.getBodega());
        c.setMedida(src.getMedida());
        c.setCantidad(src.getCantidad());
        c.setValorUnitario(src.getValorUnitario());
        c.setPorcentajeIva(src.getPorcentajeIva());
        c.setValorIva(src.getValorIva());
        c.setTotalLinea(src.getTotalLinea());
        return c;
    }

    // ------------------------------------------------------------------------
    // 3. Clasificación
    // ------------------------------------------------------------------------

    private void clasificarItems(Connection conn, List<ItemFactura> items,
                                 List<ItemClasificado> itemsOk,
                                 List<Novedad> novedades) throws SQLException {
        for (ItemFactura item : items) {
            if (item.getCantidad() == 0) {
                novedades.add(new Novedad(Novedad.Tipo.CANTIDAD_CERO,
                        item.getCodigo(), ProductoLookupService.normalizar(item.getCodigo()),
                        item.getDescripcion(), 0, "Cantidad cero en factura"));
                continue;
            }

            ResultadoLookupProducto res = lookupSvc.buscar(conn, item.getCodigo());
            switch (res.getEstado()) {
                case OK:
                    itemsOk.add(new ItemClasificado(item, res.getProducto()));
                    break;
                case CODIGO_INVALIDO:
                    novedades.add(new Novedad(Novedad.Tipo.CODIGO_INVALIDO,
                            res.getCodigoOriginal(), res.getCodigoNormalizado(),
                            item.getDescripcion(), item.getCantidad(), res.getMotivo()));
                    break;
                case NO_EXISTE:
                    novedades.add(new Novedad(Novedad.Tipo.NO_EXISTE,
                            res.getCodigoOriginal(), res.getCodigoNormalizado(),
                            item.getDescripcion(), item.getCantidad(), res.getMotivo()));
                    break;
                case INACTIVO:
                    novedades.add(new Novedad(Novedad.Tipo.INACTIVO,
                            res.getCodigoOriginal(), res.getCodigoNormalizado(),
                            item.getDescripcion(), item.getCantidad(), res.getMotivo()));
                    break;
                default:
                    novedades.add(new Novedad(Novedad.Tipo.ERROR_LOOKUP,
                            res.getCodigoOriginal(), res.getCodigoNormalizado(),
                            item.getDescripcion(), item.getCantidad(), res.getMotivo()));
            }
        }
    }

    // ------------------------------------------------------------------------
    // 6. Asignación a bodegas
    // ------------------------------------------------------------------------

    private void asignarYAcumular(Connection conn, List<ItemClasificado> itemsOk,
                                  Map<Integer, OrdenPorBodega> porBodega,
                                  Map<Integer, String> nombresBodega,
                                  List<Novedad> novedades) throws SQLException {
        for (ItemClasificado ic : itemsOk) {
            List<AsignacionBodega> asignaciones = asignacionSvc.asignar(
                    conn, ic.producto.getId(), ic.item.getCantidad());

            for (AsignacionBodega a : asignaciones) {
                OrdenPorBodega orden = porBodega.get(a.getIdBodega());
                if (orden == null) {
                    String nombre = nombresBodega.get(a.getIdBodega());
                    orden = new OrdenPorBodega(a.getIdBodega(), nombre != null ? nombre : ("BODEGA " + a.getIdBodega()));
                    porBodega.put(a.getIdBodega(), orden);
                }
                orden.addItem(new ItemOrden(
                        ic.producto.getId(),
                        ic.producto.getCodigoBarras(),
                        ic.producto.getDescripcion(),
                        a.getCantidad(),
                        a.getDisponibleInicial(),
                        a.getFuente()
                ));

                if (a.getFuente() == AsignacionBodega.Fuente.SIN_HISTORICO_FALLBACK) {
                    novedades.add(new Novedad(Novedad.Tipo.SIN_HISTORICO,
                            ic.item.getCodigo(), ic.producto.getCodigoBarras(),
                            ic.producto.getDescripcion(), a.getCantidad(),
                            "Producto sin stock ni histórico; asignado a bodega 1"));
                }
            }
        }
    }

    private Map<Integer, String> cargarNombresBodegas(Connection conn) throws SQLException {
        Map<Integer, String> m = new HashMap<Integer, String>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, nombre FROM bodegas");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) m.put(rs.getInt(1), rs.getString(2));
        }
        return m;
    }

    // ------------------------------------------------------------------------
    // 6. Generación de la factura Salida por bodega
    // ------------------------------------------------------------------------

    private void generarFacturaSalida(Connection conn, OrdenPorBodega orden, Factura facturaOriginal,
                                      ContactoInfo contacto, int idUser) throws SQLException {
        // 6.1 Insert facturas_cabeceras (id autoincremental, devuelto con RETURNING).
        // anulado=1: las órdenes automáticas nacen "anuladas" por convención del negocio
        // (no representan ventas reales; solo despachos para la bodega).
        // Se envuelve en retry porque el sistema legado inserta con MAX(id)+1 sin avanzar
        // la secuencia; cuando colisionamos en PK resincronizamos y reintentamos.
        int idCabecera = insertarCabeceraConReintentoPk(conn, facturaOriginal, contacto, idUser, orden);
        orden.setIdCabeceraGenerada(idCabecera);

        // 6.2 Por cada ítem: detalle + stock_productos + movimiento
        for (ItemOrden item : orden.getItems()) {
            insertarDetalle(conn, idCabecera, item);
            double[] snapshot = upsertPendientesStock(conn, item.getIdProducto(), orden.getIdBodega(), item.getCantidad());
            insertarMovimiento(conn, item, orden.getIdBodega(), idUser, idCabecera, snapshot,
                               facturaOriginal.getNumeroCompleto());
        }
    }

    private void insertarDetalle(Connection conn, int idCabecera, ItemOrden item) throws SQLException {
        String sql = "INSERT INTO facturas_detalles (id_cabecera, id_producto, cantidad, subtotal) "
                   + "VALUES (?, ?, ?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idCabecera);
            ps.setInt(2, item.getIdProducto());
            ps.setDouble(3, item.getCantidad());
            ps.executeUpdate();
        }
    }

    /**
     * UPSERT manual (PG 9.4 no tiene ON CONFLICT).
     * Devuelve [cantidad_anterior, pendientes_anterior, pendientes_nuevo, costo_promedio].
     */
    private double[] upsertPendientesStock(Connection conn, int idProducto, int idBodega, double delta) throws SQLException {
        String sel = "SELECT cantidad, pendientes, costo_promedio FROM stock_productos "
                   + "WHERE id_producto = ? AND id_bodega = ? FOR UPDATE";
        Double cantidadActual = null;
        Double pendientesActual = null;
        Double costoPromedio = null;
        try (PreparedStatement ps = conn.prepareStatement(sel)) {
            ps.setInt(1, idProducto);
            ps.setInt(2, idBodega);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    cantidadActual = rs.getDouble("cantidad");
                    pendientesActual = rs.getDouble("pendientes");
                    costoPromedio = rs.getDouble("costo_promedio");
                    if (rs.wasNull()) costoPromedio = 0.0;
                }
            }
        }

        double cantAntes = cantidadActual != null ? cantidadActual : 0.0;
        double pendAntes = pendientesActual != null ? pendientesActual : 0.0;
        double pendNuevo = pendAntes + delta;
        double costo = costoPromedio != null ? costoPromedio : 0.0;

        if (cantidadActual == null) {
            String ins = "INSERT INTO stock_productos (id_producto, id_bodega, cantidad, pendientes, costo_promedio, updated_at) "
                       + "VALUES (?, ?, 0, ?, 0, now())";
            try (PreparedStatement ps = conn.prepareStatement(ins)) {
                ps.setInt(1, idProducto);
                ps.setInt(2, idBodega);
                ps.setDouble(3, delta);
                ps.executeUpdate();
            }
        } else {
            String upd = "UPDATE stock_productos SET pendientes = pendientes + ?, updated_at = now() "
                       + "WHERE id_producto = ? AND id_bodega = ?";
            try (PreparedStatement ps = conn.prepareStatement(upd)) {
                ps.setDouble(1, delta);
                ps.setInt(2, idProducto);
                ps.setInt(3, idBodega);
                ps.executeUpdate();
            }
        }

        return new double[]{cantAntes, pendAntes, pendNuevo, costo};
    }

    private void insertarMovimiento(Connection conn, ItemOrden item, int idBodega, int idUser,
                                    int idCabecera, double[] snapshot, String numeroFactura) throws SQLException {
        double cantAntes = snapshot[0];
        double pendAntes = snapshot[1];
        double pendNuevo = snapshot[2];
        double costo = snapshot[3];

        String sql = "INSERT INTO movimientos_inventario ("
                   + " id_producto, id_bodega, id_user, tipo, "
                   + " afecta_cantidad, afecta_pendientes, valor, valor_anterior, valor_nuevo, "
                   + " costo_unitario, costo_promedio_anterior, costo_promedio_nuevo, "
                   + " cantidad_anterior, cantidad_nueva, pendientes_anterior, pendientes_nuevo, "
                   + " id_referencia, tabla_referencia, fecha, hora, observacion"
                   + ") VALUES ("
                   + " ?, ?, ?, ?, "
                   + " 0, 1, ?, ?, ?, "
                   + " NULL, ?, ?, "
                   + " ?, ?, ?, ?, "
                   + " ?, 'facturas_cabeceras', CURRENT_DATE, ?, ?"
                   + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, item.getIdProducto());
            ps.setInt(i++, idBodega);
            ps.setInt(i++, idUser);
            ps.setString(i++, TIPO_MOV_AUTOMATICO);
            ps.setDouble(i++, item.getCantidad());    // valor
            ps.setDouble(i++, pendAntes);             // valor_anterior (refleja pendientes, que es lo que afecta)
            ps.setDouble(i++, pendNuevo);             // valor_nuevo
            ps.setDouble(i++, costo);                 // costo_promedio_anterior
            ps.setDouble(i++, costo);                 // costo_promedio_nuevo (no cambia)
            ps.setDouble(i++, cantAntes);             // cantidad_anterior
            ps.setDouble(i++, cantAntes);             // cantidad_nueva (no cambia)
            ps.setDouble(i++, pendAntes);             // pendientes_anterior
            ps.setDouble(i++, pendNuevo);             // pendientes_nuevo
            ps.setInt(i++, idCabecera);
            ps.setString(i++, LocalDateTime.now().format(FMT_HORA));
            ps.setString(i++, "WO-PRINTER AUTO - " + numeroFactura);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------
    // 7. Registro en facturas_impresas + detalle_factura
    // ------------------------------------------------------------------------

    private int guardarFacturaImpresa(Connection conn, Factura factura, List<ItemFactura> itemsConsolidados,
                                      List<Novedad> novedades) throws SQLException {
        String sqlCab = "INSERT INTO facturas_impresas "
                      + "(numero_factura, cliente, fecha_factura, vendedor, concepto, forma_pago, prefijo, empresa, nit_cliente) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";

        int facturaId;
        try (PreparedStatement ps = conn.prepareStatement(sqlCab)) {
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
            ps.setString(9, factura.getNitCliente()); // solo lo trae el HTML; null para Excel
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("No se obtuvo id de facturas_impresas");
                facturaId = rs.getInt(1);
            }
        }

        // Index novedades por código normalizado -> motivo
        Map<String, Novedad> novPorCodigo = new HashMap<String, Novedad>();
        for (Novedad n : novedades) {
            String k = n.getCodigoNormalizado() != null ? n.getCodigoNormalizado()
                    : ProductoLookupService.normalizar(n.getCodigoOriginal());
            novPorCodigo.put(k, n);
        }

        String sqlDet = "INSERT INTO detalle_factura "
                      + "(factura_id, codigo_producto, descripcion, cantidad, iva, precio_unitario, total_linea, es_novedad, motivo_novedad) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sqlDet)) {
            for (ItemFactura item : itemsConsolidados) {
                String codNorm = ProductoLookupService.normalizar(item.getCodigo());
                Novedad nov = novPorCodigo.get(codNorm);
                ps.setInt(1, facturaId);
                ps.setString(2, item.getCodigo());
                ps.setString(3, item.getDescripcion());
                ps.setDouble(4, item.getCantidad());
                // El Excel (columnas A..W) no trae precios y deja estos campos en 0;
                // las facturas HTML si los traen y se guardan los valores reales.
                ps.setDouble(5, item.getPorcentajeIva());
                ps.setDouble(6, item.getValorUnitario());
                ps.setDouble(7, item.getTotalLinea());
                ps.setBoolean(8, nov != null);
                ps.setString(9, nov != null ? (nov.getTipo().name() + ": " + nov.getMotivo()) : null);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        return facturaId;
    }

    // ------------------------------------------------------------------------
    // 7.5 Espejo operacional en novedades_facturas
    // ------------------------------------------------------------------------

    private void guardarNovedadesOperativo(Connection conn, int idFacturaImpresa, String numeroFactura,
                                           List<Novedad> novedades) throws SQLException {
        String sql = "INSERT INTO novedades_facturas "
                   + "(factura_impresa_id, numero_factura, tipo, codigo_original, codigo_normalizado, "
                   + " descripcion, cantidad, motivo, estado_revision) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDIENTE')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Novedad n : novedades) {
                ps.setInt(1, idFacturaImpresa);
                ps.setString(2, numeroFactura);
                ps.setString(3, n.getTipo().name());
                ps.setString(4, n.getCodigoOriginal());
                ps.setString(5, n.getCodigoNormalizado());
                ps.setString(6, n.getDescripcion());
                ps.setDouble(7, n.getCantidad());
                ps.setString(8, n.getMotivo());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------------
    // Insert facturas_cabeceras con retry ante colisión de PK
    // ------------------------------------------------------------------------

    private static final int MAX_REINTENTOS_PK = 3;
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private int insertarCabeceraConReintentoPk(Connection conn, Factura facturaOriginal,
                                                ContactoInfo contacto, int idUser,
                                                OrdenPorBodega orden) throws SQLException {
        String sqlCab = "INSERT INTO facturas_cabeceras "
                      + "(codigo, id_contacto, id_user, fecha, hora, tipo_factura, observacion, "
                      + " observacion_entrega, anulado, tipo_pago, id_bodega) "
                      + "VALUES (?, ?, ?, CURRENT_DATE, ?, ?, ?, ?, 1, 0, ?) RETURNING id";

        int intentos = 0;
        while (true) {
            Savepoint sp = conn.setSavepoint("ins_fac_cab");
            try (PreparedStatement ps = conn.prepareStatement(sqlCab)) {
                ps.setString(1, facturaOriginal.getNumeroCompleto());
                ps.setInt(2, contacto.getId());
                ps.setInt(3, idUser);
                ps.setString(4, LocalDateTime.now().format(FMT_HORA));
                ps.setString(5, TIPO_FACTURA_SALIDA);
                ps.setString(6, conceptoSinDocumento(facturaOriginal.getConcepto()));
                ps.setString(7, facturaOriginal.getConcepto());
                ps.setInt(8, orden.getIdBodega());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("No se obtuvo id de facturas_cabeceras");
                    int idCabecera = rs.getInt(1);
                    conn.releaseSavepoint(sp);
                    return idCabecera;
                }
            } catch (SQLException e) {
                conn.rollback(sp);
                if (SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState()) && intentos < MAX_REINTENTOS_PK) {
                    intentos++;
                    long nuevoValor = resincronizarSecuenciaFacturasCabeceras(conn);
                    System.err.println("[ORDEN] PK duplicada en facturas_cabeceras (intento "
                            + intentos + "/" + MAX_REINTENTOS_PK
                            + "); secuencia resincronizada a " + nuevoValor + ", reintentando");
                    continue;
                }
                throw e;
            }
        }
    }

    private long resincronizarSecuenciaFacturasCabeceras(Connection conn) throws SQLException {
        return resincronizarSecuencia(conn, "facturas_cabeceras");
    }

    static String conceptoSinDocumento(String concepto) {
        if (concepto == null) return null;
        String limpio = PATTERN_CEDULA_CONCEPTO.matcher(concepto).replaceAll("").replaceAll("\\s+", " ").trim();
        return limpio.isEmpty() ? null : limpio;
    }

    /** Tablas con columna "id" SERIAL tocadas por el orquestador. */
    private static final String[] TABLAS_ORQUESTADOR = {
        "facturas_cabeceras",
        "facturas_detalles",
        "facturas_impresas",
        "detalle_factura",
        "movimientos_inventario",
        "novedades_facturas"
    };

    private void resincronizarSecuenciasOrquestador(Connection conn) throws SQLException {
        for (String tabla : TABLAS_ORQUESTADOR) {
            try {
                resincronizarSecuencia(conn, tabla);
            } catch (SQLException e) {
                // No abortar el flujo si una tabla no existe o no tiene secuencia;
                // la proteccion principal (facturas_cabeceras) tiene retry propio.
                System.err.println("[ORDEN] No se pudo resincronizar secuencia de "
                        + tabla + ": " + e.getMessage());
            }
        }
    }

    private long resincronizarSecuencia(Connection conn, String tabla) throws SQLException {
        // tabla viene de una whitelist interna (TABLAS_ORQUESTADOR); igual validamos
        // para defensa en profundidad antes de interpolarla en la consulta.
        if (!tabla.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new SQLException("Nombre de tabla invalido: " + tabla);
        }
        String sql = "SELECT setval(pg_get_serial_sequence(?, 'id'), "
                   + "(SELECT COALESCE(MAX(id), 1) FROM " + tabla + "), true)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tabla);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static void rollbackQuiet(Connection conn) {
        if (conn == null) return;
        try { conn.rollback(); } catch (SQLException ignore) {}
    }

    private static void closeQuiet(Connection conn) {
        if (conn == null) return;
        try { conn.close(); } catch (SQLException ignore) {}
    }

    private static final class ItemClasificado {
        final ItemFactura item;
        final ProductoInfo producto;
        ItemClasificado(ItemFactura item, ProductoInfo producto) {
            this.item = item;
            this.producto = producto;
        }
    }
}
