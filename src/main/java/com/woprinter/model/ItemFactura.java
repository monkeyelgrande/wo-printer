package com.woprinter.model;

public class ItemFactura {

    private String codigo;          // A5939
    private String descripcion;     // ALUM UNION DE 1-1/2"
    private String bodega;
    private String medida;          // Unidad, Metros, Kilogramo
    private double cantidad;

    // Solo disponibles cuando la fuente es HTML; el Excel (columnas A..W)
    // no trae precios y estos campos quedan en 0.
    private double valorUnitario;
    private double porcentajeIva;
    private double valorIva;
    private double totalLinea;

    public ItemFactura() {}

    // --- Getters y Setters ---

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public String getBodega() { return bodega; }
    public void setBodega(String bodega) { this.bodega = bodega; }

    public String getMedida() { return medida; }
    public void setMedida(String medida) { this.medida = medida; }

    public double getCantidad() { return cantidad; }
    public void setCantidad(double cantidad) { this.cantidad = cantidad; }

    public double getValorUnitario() { return valorUnitario; }
    public void setValorUnitario(double valorUnitario) { this.valorUnitario = valorUnitario; }

    public double getPorcentajeIva() { return porcentajeIva; }
    public void setPorcentajeIva(double porcentajeIva) { this.porcentajeIva = porcentajeIva; }

    public double getValorIva() { return valorIva; }
    public void setValorIva(double valorIva) { this.valorIva = valorIva; }

    public double getTotalLinea() { return totalLinea; }
    public void setTotalLinea(double totalLinea) { this.totalLinea = totalLinea; }

    @Override
    public String toString() {
        return codigo + " " + descripcion + " x" + cantidad;
    }
}
