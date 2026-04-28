package com.woprinter.model;

import java.util.ArrayList;
import java.util.List;

public class ResultadoOrden {

    public enum Estado {
        /** Se generaron órdenes y/o novedades con éxito. */
        EXITO,
        /** La factura ya había sido procesada. */
        DUPLICADA,
        /** Falla irrecuperable (transacción revertida). */
        ERROR
    }

    private final Estado estado;
    private final String numeroFactura;
    private final List<OrdenPorBodega> ordenes;
    private final List<Novedad> novedades;
    private final String mensajeError;
    private int idFacturaImpresa = -1;
    private int idContactoResuelto = -1;
    private int idUserSistema = -1;

    private ResultadoOrden(Estado estado, String numeroFactura,
                           List<OrdenPorBodega> ordenes, List<Novedad> novedades,
                           String mensajeError) {
        this.estado = estado;
        this.numeroFactura = numeroFactura;
        this.ordenes = ordenes != null ? ordenes : new ArrayList<OrdenPorBodega>();
        this.novedades = novedades != null ? novedades : new ArrayList<Novedad>();
        this.mensajeError = mensajeError;
    }

    public static ResultadoOrden exito(String numeroFactura,
                                        List<OrdenPorBodega> ordenes, List<Novedad> novedades) {
        return new ResultadoOrden(Estado.EXITO, numeroFactura, ordenes, novedades, null);
    }

    public static ResultadoOrden duplicada(String numeroFactura) {
        return new ResultadoOrden(Estado.DUPLICADA, numeroFactura, null, null, null);
    }

    public static ResultadoOrden error(String numeroFactura, String mensaje) {
        return new ResultadoOrden(Estado.ERROR, numeroFactura, null, null, mensaje);
    }

    public Estado getEstado() { return estado; }
    public String getNumeroFactura() { return numeroFactura; }
    public List<OrdenPorBodega> getOrdenes() { return ordenes; }
    public List<Novedad> getNovedades() { return novedades; }
    public String getMensajeError() { return mensajeError; }

    public int getIdFacturaImpresa() { return idFacturaImpresa; }
    public void setIdFacturaImpresa(int id) { this.idFacturaImpresa = id; }

    public int getIdContactoResuelto() { return idContactoResuelto; }
    public void setIdContactoResuelto(int id) { this.idContactoResuelto = id; }

    public int getIdUserSistema() { return idUserSistema; }
    public void setIdUserSistema(int id) { this.idUserSistema = id; }

    public boolean isExito() { return estado == Estado.EXITO; }
    public boolean isDuplicada() { return estado == Estado.DUPLICADA; }
    public boolean hayNovedades() { return !novedades.isEmpty(); }
}
