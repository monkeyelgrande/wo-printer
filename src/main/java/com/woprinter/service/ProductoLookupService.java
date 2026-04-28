package com.woprinter.service;

import com.woprinter.model.ProductoInfo;
import com.woprinter.model.ResultadoLookupProducto;
import com.woprinter.model.ResultadoLookupProducto.Estado;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ProductoLookupService {

    private final DatabaseService db;

    public ProductoLookupService() {
        this.db = DatabaseService.getInstance();
    }

    public ResultadoLookupProducto buscar(String codigoRaw) {
        try (Connection conn = db.getConnection()) {
            return buscar(conn, codigoRaw);
        } catch (SQLException e) {
            System.err.println("[LOOKUP] Error de DB: " + e.getMessage());
            String norm = normalizar(codigoRaw);
            return new ResultadoLookupProducto(Estado.ERROR_DB, null, codigoRaw, norm,
                    "Error de base de datos: " + e.getMessage());
        }
    }

    public ResultadoLookupProducto buscar(Connection conn, String codigoRaw) throws SQLException {
        String normalizado = normalizar(codigoRaw);
        String motivoInvalido = validarFormato(codigoRaw, normalizado);
        if (motivoInvalido != null) {
            return new ResultadoLookupProducto(Estado.CODIGO_INVALIDO, null,
                    codigoRaw, normalizado, motivoInvalido);
        }

        String sql = "SELECT id, codigo_barras, descripcion, estado FROM productos "
                   + "WHERE UPPER(TRIM(codigo_barras)) = ? LIMIT 1";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizado);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new ResultadoLookupProducto(Estado.NO_EXISTE, null,
                            codigoRaw, normalizado, "Código no encontrado en el sistema");
                }

                boolean estado = rs.getBoolean("estado");
                if (rs.wasNull()) estado = false;

                ProductoInfo info = new ProductoInfo(
                        rs.getInt("id"),
                        rs.getString("codigo_barras"),
                        rs.getString("descripcion"),
                        estado
                );

                if (!info.isActivo()) {
                    return new ResultadoLookupProducto(Estado.INACTIVO, info,
                            codigoRaw, normalizado, "Producto inactivo");
                }
                return new ResultadoLookupProducto(Estado.OK, info,
                        codigoRaw, normalizado, null);
            }
        }
    }

    public static String normalizar(String raw) {
        if (raw == null) return "";
        return raw.trim().toUpperCase();
    }

    /**
     * Retorna null si el código es válido para buscar, o el motivo del rechazo.
     * Reglas:
     *  - No vacío tras TRIM
     *  - No contiene espacios intermedios (los códigos del sistema nunca los tienen)
     */
    public static String validarFormato(String raw, String normalizado) {
        if (raw == null || normalizado.isEmpty()) {
            return "Código vacío";
        }
        if (normalizado.contains(" ")) {
            return "El código contiene espacios intermedios: '" + raw + "'";
        }
        return null;
    }
}
