package com.woprinter.model;

public class ItemFactura {

    private String codigo;          // A5939
    private String descripcion;     // ALUM UNION DE 1-1/2"
    private String bodega;
    private String medida;          // Unidad, Metros, Kilogramo
    private double cantidad;
    private double iva;             // 0.19
    private double precioUnitario;  // sin IVA
    private double totalLinea;      // con IVA

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

    public double getIva() { return iva; }
    public void setIva(double iva) { this.iva = iva; }

    public double getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(double precioUnitario) { this.precioUnitario = precioUnitario; }

    public double getTotalLinea() { return totalLinea; }
    public void setTotalLinea(double totalLinea) { this.totalLinea = totalLinea; }

    @Override
    public String toString() {
        return codigo + " " + descripcion + " x" + cantidad + " $" + totalLinea;
    }
}
