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
 * Flujo (Excel, sin NIT explícito):
 *   1. Buscar por nombre (trim + ILIKE)
 *   2. Extraer cédula del concepto con patrón *NUMERO
 *   3. Sin cédula -> contacto id=1 (CONSUMIDOR FINAL)
 *   4. Con cédula existente -> usar ese contacto
 *   5. Con cédula nueva -> crear contacto (nombre + cédula)
 *   6. Fallback final -> contacto id=1
 *
 * Flujo con NIT (facturas HTML, que sí traen el NIT/cédula del cliente):
 *   1. Buscar por NIT en {@code contactos.cedula}; si existe -> usar ese contacto
 *   2. Si no existe -> crear contacto con ese NIT y el nombre del HTML
 *   3. Si la creación falla -> caer al flujo clásico de arriba
 */
public class ContactoResolverService {

    public static final int ID_CONTACTO_DEFAULT = 1;
    private static final Pattern PATTERN_CEDULA_CONCEPTO = Pattern.compile("\\*(\\d+)");

    private final DatabaseService db;

    public ContactoResolverService() {
        this.db = DatabaseService.getInstance();
    }

    public ContactoInfo resolver(String nombreCliente, String concepto) {
        return resolver(nombreCliente, concepto, null);
    }

    public ContactoInfo resolver(String nombreCliente, String concepto, String nit) {
        try (Connection conn = db.getConnection()) {
            return resolver(conn, nombreCliente, concepto, nit);
        } catch (SQLException e) {
            System.err.println("[CONTACTO] Error DB: " + e.getMessage());
            return new ContactoInfo(ID_CONTACTO_DEFAULT, "CONSUMIDOR FINAL", "");
        }
    }

    /**
     * Resuelve el contacto priorizando el NIT cuando la factura lo trae (HTML).
     * Si no hay NIT (Excel) delega en el flujo clásico basado en nombre/concepto.
     */
    public ContactoInfo resolver(Connection conn, String nombreCliente, String concepto, String nit)
            throws SQLException {
        String nitNorm = normalizarNit(nit);
        if (nitNorm != null) {
            // 1. Por NIT: si el cliente ya existe, se carga tal cual.
            ContactoInfo c = buscarPorCedula(conn, nitNorm);
            if (c != null) {
                System.out.println("[CONTACTO] Encontrado por NIT " + nitNorm + ": "
                        + c.getNombre() + " (id=" + c.getId() + ")");
                return c;
            }
            // 2. NIT nuevo: crear el contacto con el NIT y el nombre del HTML.
            c = crearContacto(conn, nombreCliente, nitNorm);
            if (c != null) {
                System.out.println("[CONTACTO] Creado por NIT id=" + c.getId()
                        + " nombre=" + c.getNombre() + " NIT=" + nitNorm);
                return c;
            }
            // 3. Si la creación falla, se cae al flujo clásico como red de seguridad.
            System.err.println("[CONTACTO] No se pudo crear contacto por NIT " + nitNorm
                    + "; se intenta resolución clásica");
        }
        return resolver(conn, nombreCliente, concepto);
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

    /**
     * Canoniza el NIT/cédula que trae el HTML a la forma {@code base-DV} cuando
     * incluye dígito de verificación, o {@code base} cuando no lo trae:
     * <ul>
     *   <li>Quita los puntos de miles ({@code 900.123.456-7} -> {@code 900123456-7}).</li>
     *   <li>Convierte SIEMPRE a guion el separador del dígito de verificación, venga
     *       como espacio o guion ({@code 901142934 1} -> {@code 901142934-1}).</li>
     *   <li>El DV es significativo: {@code 222222222-7} y {@code 222222222} se
     *       consideran identificadores DISTINTOS.</li>
     * </ul>
     * Devuelve null si no queda nada útil, para que el flujo caiga a la
     * resolución clásica. Expuesto como package-private para tests.
     */
    static String normalizarNit(String nit) {
        if (nit == null) return null;
        String t = nit.trim();
        if (t.isEmpty()) return null;
        // Puntos de miles fuera.
        t = t.replace(".", "");
        // Separador del DV (espacio(s) o guion) -> guion canónico, solo al final.
        t = t.replaceAll("[ -]+(\\d)$", "-$1");
        // Deja únicamente dígitos y el guion del DV (quita prefijos como "C.C." o espacios sobrantes).
        t = t.replaceAll("[^0-9-]", "");
        return t.isEmpty() ? null : t;
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
        // El parámetro ya viene canonizado (base o base-DV con guion). Normalizamos la
        // columna a la MISMA forma (quitando puntos y unificando el separador del DV a
        // guion) y comparamos exacto: el DV es significativo, así que "222222222-7" NO
        // iguala a "222222222". ORDER BY id da un resultado determinista cuando ya hay
        // duplicados históricos para un mismo NIT.
        String sql = "SELECT id, nombre, cedula FROM contactos "
                   + "WHERE regexp_replace(regexp_replace(TRIM(cedula), '[.]', '', 'g'), '[ -]+([0-9])$', '-\\1') = ? "
                   + "ORDER BY id LIMIT 1";
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
