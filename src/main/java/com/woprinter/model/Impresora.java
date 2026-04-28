package com.woprinter.model;

public class Impresora {

    private int id;
    private String nombre;
    private String nombreWindows;
    private boolean activa;
    private boolean tipoBodega;
    private boolean tipoVenta;
    private boolean tipoNotificaciones;
    private Integer idBodega;        // nullable
    private String nombreBodega;     // cargado vía JOIN, solo lectura

    public Impresora() {
    }

    public Impresora(int id, String nombre, String nombreWindows, boolean activa,
                     boolean tipoBodega, boolean tipoVenta,
                     boolean tipoNotificaciones, Integer idBodega, String nombreBodega) {
        this.id = id;
        this.nombre = nombre;
        this.nombreWindows = nombreWindows;
        this.activa = activa;
        this.tipoBodega = tipoBodega;
        this.tipoVenta = tipoVenta;
        this.tipoNotificaciones = tipoNotificaciones;
        this.idBodega = idBodega;
        this.nombreBodega = nombreBodega;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getNombreWindows() { return nombreWindows; }
    public void setNombreWindows(String nombreWindows) { this.nombreWindows = nombreWindows; }

    public boolean isActiva() { return activa; }
    public void setActiva(boolean activa) { this.activa = activa; }

    public boolean isTipoBodega() { return tipoBodega; }
    public void setTipoBodega(boolean tipoBodega) { this.tipoBodega = tipoBodega; }

    public boolean isTipoVenta() { return tipoVenta; }
    public void setTipoVenta(boolean tipoVenta) { this.tipoVenta = tipoVenta; }

    public boolean isTipoNotificaciones() { return tipoNotificaciones; }
    public void setTipoNotificaciones(boolean tipoNotificaciones) { this.tipoNotificaciones = tipoNotificaciones; }

    public Integer getIdBodega() { return idBodega; }
    public void setIdBodega(Integer idBodega) { this.idBodega = idBodega; }

    public String getNombreBodega() { return nombreBodega; }
    public void setNombreBodega(String nombreBodega) { this.nombreBodega = nombreBodega; }

    public String getTiposTexto() {
        StringBuilder sb = new StringBuilder();
        if (idBodega != null) sb.append(nombreBodega != null ? nombreBodega : ("Bodega " + idBodega));
        if (tipoVenta) { if (sb.length() > 0) sb.append(" + "); sb.append("Venta"); }
        if (tipoNotificaciones) { if (sb.length() > 0) sb.append(" + "); sb.append("Notificaciones"); }
        if (sb.length() == 0) sb.append("Sin asignar");
        return sb.toString();
    }

    @Override
    public String toString() {
        return nombre + " [" + nombreWindows + "] (" + getTiposTexto() + ")";
    }
}
