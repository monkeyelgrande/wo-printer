package com.woprinter.model;

public class ResultadoLookupProducto {

    public enum Estado {
        OK,
        CODIGO_INVALIDO,
        NO_EXISTE,
        INACTIVO,
        ERROR_DB
    }

    private final Estado estado;
    private final ProductoInfo producto;
    private final String codigoOriginal;
    private final String codigoNormalizado;
    private final String motivo;

    public ResultadoLookupProducto(Estado estado, ProductoInfo producto,
                                   String codigoOriginal, String codigoNormalizado,
                                   String motivo) {
        this.estado = estado;
        this.producto = producto;
        this.codigoOriginal = codigoOriginal;
        this.codigoNormalizado = codigoNormalizado;
        this.motivo = motivo;
    }

    public Estado getEstado() { return estado; }
    public ProductoInfo getProducto() { return producto; }
    public String getCodigoOriginal() { return codigoOriginal; }
    public String getCodigoNormalizado() { return codigoNormalizado; }
    public String getMotivo() { return motivo; }

    public boolean isOk() { return estado == Estado.OK; }
    public boolean esNovedad() { return estado != Estado.OK; }
}
