package com.woprinter.service;

import com.woprinter.model.AsignacionBodega;
import com.woprinter.model.AsignacionBodega.Fuente;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decide de qué bodega(s) se descarga una cantidad requerida de un producto.
 *
 * Reglas:
 *  - disponible = GREATEST(cantidad, 0) - pendientes    (cantidad negativa cuenta como 0)
 *  - Se consumen primero las bodegas con disponible > 0, ordenadas DESC.
 *  - Empate en disponible → desempata la bodega con mayor id de movimiento (más reciente).
 *  - Si la demanda excede la suma de disponibles, el remanente cae en la bodega
 *    con MENOR disponible inicial (entre las que tenían > 0), quedando negativa.
 *  - Si NINGUNA bodega tiene disponible > 0:
 *      1) última bodega con movimiento en movimientos_inventario
 *      2) última bodega con ingreso en ingresos_mercancias_detalle
 *      3) bodega por defecto id=1 (marcado como SIN_HISTORICO_FALLBACK)
 */
public class BodegaAsignacionService {

    private static final int ID_BODEGA_FALLBACK = 1;

    private final DatabaseService db;

    public BodegaAsignacionService() {
        this.db = DatabaseService.getInstance();
    }

    public List<AsignacionBodega> asignar(int idProducto, double cantidadRequerida) {
        try (Connection conn = db.getConnection()) {
            return asignar(conn, idProducto, cantidadRequerida);
        } catch (SQLException e) {
            System.err.println("[ASIGNACION] Error DB: " + e.getMessage());
            return Collections.singletonList(new AsignacionBodega(
                    ID_BODEGA_FALLBACK, cantidadRequerida, 0.0, Fuente.SIN_HISTORICO_FALLBACK));
        }
    }

    public List<AsignacionBodega> asignar(Connection conn, int idProducto, double cantidadRequerida) throws SQLException {
        if (cantidadRequerida <= 0) {
            return Collections.emptyList();
        }

        List<StockBodega> stocks = obtenerStockPorBodega(conn, idProducto);

        List<StockBodega> conDisponible = new ArrayList<StockBodega>();
        for (StockBodega s : stocks) {
            if (s.disponible > 0) conDisponible.add(s);
        }

        // CASO A: nadie tiene disponible > 0
        if (conDisponible.isEmpty()) {
            return Collections.singletonList(resolverSinStock(conn, idProducto, cantidadRequerida));
        }

        // CASO B: repartir entre bodegas con disponible > 0 (ya vienen ordenadas DESC por disponible)
        List<AsignacionBodega> asignaciones = new ArrayList<AsignacionBodega>();
        double pendiente = cantidadRequerida;

        for (StockBodega s : conDisponible) {
            if (pendiente <= 0) break;
            double toma = Math.min(s.disponible, pendiente);
            asignaciones.add(new AsignacionBodega(
                    s.idBodega, toma, s.disponible, Fuente.STOCK_DISPONIBLE));
            pendiente -= toma;
        }

        // Remanente -> bodega con MENOR disponible inicial (última en la lista ordenada DESC)
        if (pendiente > 0) {
            StockBodega menorDisp = conDisponible.get(conDisponible.size() - 1);
            boolean acumulado = false;
            for (AsignacionBodega a : asignaciones) {
                if (a.getIdBodega() == menorDisp.idBodega) {
                    a.sumarCantidad(pendiente);
                    a.setFuente(Fuente.STOCK_NEGATIVO_FORZADO);
                    acumulado = true;
                    break;
                }
            }
            if (!acumulado) {
                asignaciones.add(new AsignacionBodega(
                        menorDisp.idBodega, pendiente, menorDisp.disponible,
                        Fuente.STOCK_NEGATIVO_FORZADO));
            }
        }

        return asignaciones;
    }

    // ------------------------------------------------------------------------
    // Consulta de stock ordenado
    // ------------------------------------------------------------------------

    /**
     * Orden: disponible DESC; desempate por id máximo de movimiento DESC (más reciente).
     */
    private List<StockBodega> obtenerStockPorBodega(Connection conn, int idProducto) throws SQLException {
        String sql =
            "SELECT sp.id_bodega, "
          + "       sp.cantidad, "
          + "       sp.pendientes, "
          + "       (GREATEST(sp.cantidad, 0) - sp.pendientes) AS disponible, "
          + "       COALESCE(( "
          + "           SELECT MAX(m.id) FROM movimientos_inventario m "
          + "           WHERE m.id_producto = sp.id_producto AND m.id_bodega = sp.id_bodega "
          + "       ), 0) AS ultimo_mov_id "
          + "FROM stock_productos sp "
          + "WHERE sp.id_producto = ? "
          + "ORDER BY disponible DESC, ultimo_mov_id DESC";

        List<StockBodega> lista = new ArrayList<StockBodega>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idProducto);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new StockBodega(
                            rs.getInt("id_bodega"),
                            rs.getDouble("cantidad"),
                            rs.getDouble("pendientes"),
                            rs.getDouble("disponible")));
                }
            }
        }
        return lista;
    }

    // ------------------------------------------------------------------------
    // Resolución cuando no hay disponible en ninguna bodega
    // ------------------------------------------------------------------------

    private AsignacionBodega resolverSinStock(Connection conn, int idProducto, double cantidad) throws SQLException {
        // 1. Última bodega con movimiento
        Integer idBod = ultimaBodegaPorMovimiento(conn, idProducto);
        if (idBod != null) {
            return new AsignacionBodega(idBod, cantidad, 0.0, Fuente.SIN_STOCK_ULTIMO_MOVIMIENTO);
        }
        // 2. Última bodega con ingreso de mercancía
        idBod = ultimaBodegaPorIngreso(conn, idProducto);
        if (idBod != null) {
            return new AsignacionBodega(idBod, cantidad, 0.0, Fuente.SIN_STOCK_ULTIMO_INGRESO);
        }
        // 3. Fallback
        return new AsignacionBodega(ID_BODEGA_FALLBACK, cantidad, 0.0, Fuente.SIN_HISTORICO_FALLBACK);
    }

    private Integer ultimaBodegaPorMovimiento(Connection conn, int idProducto) throws SQLException {
        String sql = "SELECT id_bodega FROM movimientos_inventario "
                   + "WHERE id_producto = ? "
                   + "ORDER BY fecha DESC, id DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idProducto);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return null;
    }

    private Integer ultimaBodegaPorIngreso(Connection conn, int idProducto) throws SQLException {
        String sql = "SELECT c.id_bodega "
                   + "FROM ingresos_mercancias_detalle d "
                   + "JOIN ingresos_mercancias_cabecera c ON c.id = d.id_ingreso_cabecera "
                   + "WHERE d.id_producto = ? "
                   + "ORDER BY c.id DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idProducto);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    if (!rs.wasNull()) return id;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Helper interno
    // ------------------------------------------------------------------------

    private static final class StockBodega {
        final int idBodega;
        final double cantidad;
        final double pendientes;
        final double disponible;

        StockBodega(int idBodega, double cantidad, double pendientes, double disponible) {
            this.idBodega = idBodega;
            this.cantidad = cantidad;
            this.pendientes = pendientes;
            this.disponible = disponible;
        }
    }
}
