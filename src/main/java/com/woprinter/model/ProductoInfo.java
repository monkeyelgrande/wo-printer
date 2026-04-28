package com.woprinter.model;

public class ProductoInfo {

    private final int id;
    private final String codigoBarras;
    private final String descripcion;
    private final boolean activo;

    public ProductoInfo(int id, String codigoBarras, String descripcion, boolean activo) {
        this.id = id;
        this.codigoBarras = codigoBarras;
        this.descripcion = descripcion;
        this.activo = activo;
    }

    public int getId() { return id; }
    public String getCodigoBarras() { return codigoBarras; }
    public String getDescripcion() { return descripcion; }
    public boolean isActivo() { return activo; }
}
