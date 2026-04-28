package com.woprinter.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Resuelve el usuario del sistema con el que wo-printer registra las facturas
 * de Salida y los movimientos automáticos.
 *
 * Regla: usar el hostname de la máquina como {@code user_name}. Si existe,
 * reutilizarlo; si no, crearlo con estado "Inactivo" (para impedir login),
 * perfil "bodeguero" (id=2) y bodega por defecto id=1.
 *
 * El id se cachea en memoria para evitar consultar en cada factura.
 */
public class UsuarioSistemaService {

    private static final int ID_PERFIL_BODEGUERO = 2;
    private static final int ID_BODEGA_DEFAULT = 1;
    private static final String PASSWORD_SISTEMA = "WO_PRINTER_SYS";
    private static final String ESTADO_INACTIVO = "Inactivo";
    private static final int MAX_USER_NAME_LEN = 30;
    private static final int MAX_NOMBRE_LEN = 60;
    private static final String HOSTNAME_FALLBACK = "WO_PRINTER_HOST";

    private static volatile Integer cachedId = null;
    private static volatile String cachedUserName = null;

    private final DatabaseService db;

    public UsuarioSistemaService() {
        this.db = DatabaseService.getInstance();
    }

    public int getIdUsuarioSistema() {
        if (cachedId != null) return cachedId;
        try (Connection conn = db.getConnection()) {
            return resolverOCrear(conn);
        } catch (SQLException e) {
            System.err.println("[USUARIO_SISTEMA] Error DB: " + e.getMessage());
            return 1;
        }
    }

    public int resolverOCrear(Connection conn) throws SQLException {
        if (cachedId != null) return cachedId;

        String hostname = obtenerHostname();
        String userName = truncar(hostname, MAX_USER_NAME_LEN);
        String nombre = truncar(hostname, MAX_NOMBRE_LEN);

        Integer id = buscarPorUserName(conn, userName);
        if (id != null) {
            cachedId = id;
            cachedUserName = userName;
            System.out.println("[USUARIO_SISTEMA] Reutilizando id=" + id + " user_name=" + userName);
            return id;
        }

        int nuevoId;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(id), 0) + 1 FROM users");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            nuevoId = rs.getInt(1);
        }

        String sql = "INSERT INTO users (id, nombre, user_name, password, estado, id_perfil, id_bodega) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, nuevoId);
            ps.setString(2, nombre);
            ps.setString(3, userName);
            ps.setString(4, PASSWORD_SISTEMA);
            ps.setString(5, ESTADO_INACTIVO);
            ps.setInt(6, ID_PERFIL_BODEGUERO);
            ps.setInt(7, ID_BODEGA_DEFAULT);
            ps.executeUpdate();
        }
        cachedId = nuevoId;
        cachedUserName = userName;
        System.out.println("[USUARIO_SISTEMA] Creado id=" + nuevoId + " user_name=" + userName);
        return nuevoId;
    }

    private Integer buscarPorUserName(Connection conn, String userName) throws SQLException {
        String sql = "SELECT id FROM users WHERE UPPER(TRIM(user_name)) = UPPER(TRIM(?)) LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return null;
    }

    /**
     * Obtiene el hostname de la máquina. En Windows cae al env var
     * {@code COMPUTERNAME} si InetAddress falla (ej. sin resolución DNS local).
     */
    static String obtenerHostname() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            if (h != null && !h.trim().isEmpty()) {
                return h.trim().toUpperCase();
            }
        } catch (UnknownHostException ignore) {
            // cae al siguiente fallback
        }
        String env = System.getenv("COMPUTERNAME");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim().toUpperCase();
        }
        return HOSTNAME_FALLBACK;
    }

    private static String truncar(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Para pruebas: invalida el cache. */
    static void resetCache() {
        cachedId = null;
        cachedUserName = null;
    }
}
