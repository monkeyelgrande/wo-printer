package com.woprinter.service;

import com.woprinter.model.ContactoInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resuelve el contacto asociado a una factura, replicando la lógica de
 * {@code jd_Facturas_Impresas.resolverContacto} de bodega_agroinsumos.
 *
 * Flujo:
 *   1. Buscar por nombre (trim + ILIKE)
 *   2. Extraer cédula del concepto con patrón *NUMERO
 *   3. Sin cédula -> contacto id=1 (CONSUMIDOR FINAL)
 *   4. Con cédula existente -> usar ese contacto
 *   5. Con cédula nueva -> crear contacto (nombre + cédula)
 *   6. Fallback final -> contacto id=1
 */
public class ContactoResolverService {

    public static final int ID_CONTACTO_DEFAULT = 1;
    private static final Pattern PATTERN_CEDULA_CONCEPTO = Pattern.compile("\\*(\\d+)");

    private final DatabaseService db;

    public ContactoResolverService() {
        this.db = DatabaseService.getInstance();
    }

    public ContactoInfo resolver(String nombreCliente, String concepto) {
        try (Connection conn = db.getConnection()) {
            return resolver(conn, nombreCliente, concepto);
        } catch (SQLException e) {
            System.err.println("[CONTACTO] Error DB: " + e.getMessage());
            return new ContactoInfo(ID_CONTACTO_DEFAULT, "CONSUMIDOR FINAL", "");
        }
    }

    public ContactoInfo resolver(Connection conn, String nombreCliente, String concepto) throws SQLException {
        // 1. Por nombre
        ContactoInfo c = buscarPorNombre(conn, nombreCliente);
        if (c != null) {
            System.out.println("[CONTACTO] Encontrado por nombre: " + c.getNombre() + " (id=" + c.getId() + ")");
            return c;
        }

        // 2. Extraer cédula
        String cedula = extraerCedulaDeConcepto(concepto);

        // 3. Sin cédula -> default
        if (cedula == null) {
            System.out.println("[CONTACTO] Sin cédula en concepto, usando id=" + ID_CONTACTO_DEFAULT);
            c = buscarPorId(conn, ID_CONTACTO_DEFAULT);
            return c != null ? c : new ContactoInfo(ID_CONTACTO_DEFAULT, "CONSUMIDOR FINAL", "");
        }

        // 4. Por cédula
        c = buscarPorCedula(conn, cedula);
        if (c != null) {
            System.out.println("[CONTACTO] Encontrado por cédula " + cedula + ": " + c.getNombre() + " (id=" + c.getId() + ")");
            return c;
        }

        // 5. Crear nuevo
        c = crearContacto(conn, nombreCliente, cedula);
        if (c != null) {
            System.out.println("[CONTACTO] Creado id=" + c.getId() + " nombre=" + c.getNombre() + " CC=" + cedula);
            return c;
        }

        // 6. Fallback
        c = buscarPorId(conn, ID_CONTACTO_DEFAULT);
        return c != null ? c : new ContactoInfo(ID_CONTACTO_DEFAULT, "CONSUMIDOR FINAL", "");
    }

    /**
     * Extrae la cédula embebida en el concepto bajo el patrón {@code *NUMERO}.
     * Expuesto como package-private para tests.
     */
    static String extraerCedulaDeConcepto(String concepto) {
        if (concepto == null || concepto.isEmpty()) return null;
        Matcher m = PATTERN_CEDULA_CONCEPTO.matcher(concepto);
        return m.find() ? m.group(1) : null;
    }

    private ContactoInfo buscarPorNombre(Connection conn, String nombre) throws SQLException {
        if (nombre == null || nombre.trim().isEmpty()) return null;
        String sql = "SELECT id, nombre, cedula FROM contactos "
                   + "WHERE TRIM(nombre) ILIKE TRIM(?) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nombre.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapear(rs);
            }
        }
        return null;
    }

    private ContactoInfo buscarPorCedula(Connection conn, String cedula) throws SQLException {
        if (cedula == null || cedula.trim().isEmpty()) return null;
        String sql = "SELECT id, nombre, cedula FROM contactos "
                   + "WHERE TRIM(cedula) = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cedula.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapear(rs);
            }
        }
        return null;
    }

    private ContactoInfo buscarPorId(Connection conn, int id) throws SQLException {
        String sql = "SELECT id, nombre, cedula FROM contactos WHERE id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapear(rs);
            }
        }
        return null;
    }

    /**
     * Inserta el nuevo contacto usando el patrón MAX(id)+1 que usa
     * el sistema actual (el sequence de contactos está desincronizado).
     */
    private ContactoInfo crearContacto(Connection conn, String nombreCliente, String cedula) throws SQLException {
        if (nombreCliente == null || nombreCliente.trim().isEmpty()) {
            nombreCliente = "CLIENTE " + cedula;
        }
        int nuevoId;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(id), 0) + 1 FROM contactos");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            nuevoId = rs.getInt(1);
        }
        String sql = "INSERT INTO contactos (id, nombre, cedula) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, nuevoId);
            ps.setString(2, nombreCliente.trim());
            ps.setString(3, cedula.trim());
            ps.executeUpdate();
        }
        return new ContactoInfo(nuevoId, nombreCliente.trim(), cedula.trim());
    }

    private ContactoInfo mapear(ResultSet rs) throws SQLException {
        String cedula = rs.getString("cedula");
        return new ContactoInfo(
                rs.getInt("id"),
                rs.getString("nombre"),
                cedula != null ? cedula : ""
        );
    }
}
