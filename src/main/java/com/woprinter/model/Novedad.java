package com.woprinter.model;

public class Novedad {

    public enum Tipo {
        /** Código con espacios intermedios o mal formado. */
        CODIGO_INVALIDO,
        /** Código no encontrado en productos. */
        NO_EXISTE,
        /** Producto existe pero está inactivo. */
        INACTIVO,
        /** Producto sin stock ni histórico en ninguna bodega (cayó a bodega 1). */
        SIN_HISTORICO,
        /** Línea con cantidad 0 — se registra pero no mueve stock. */
        CANTIDAD_CERO,
        /** Falla de DB consultando el producto. */
        ERROR_LOOKUP
    }

    private final Tipo tipo;
    private final String codigoOriginal;
    private final String codigoNormalizado;
    private final String descripcion;
    private final double cantidad;
    private final String motivo;

    public Novedad(Tipo tipo, String codigoOriginal, String codigoNormalizado,
                   String descripcion, double cantidad, String motivo) {
        this.tipo = tipo;
        this.codigoOriginal = codigoOriginal;
        this.codigoNormalizado = codigoNormalizado;
        this.descripcion = descripcion;
        this.cantidad = cantidad;
        this.motivo = motivo;
    }

    public Tipo getTipo() { return tipo; }
    public String getCodigoOriginal() { return codigoOriginal; }
    public String getCodigoNormalizado() { return codigoNormalizado; }
    public String getDescripcion() { return descripcion; }
    public double getCantidad() { return cantidad; }
    public String getMotivo() { return motivo; }
}
