package com.woprinter.model;

public class AsignacionBodega {

    public enum Fuente {
        /** La bodega tenía disponible > 0 y cubre (total o parcialmente) la demanda. */
        STOCK_DISPONIBLE,
        /** La bodega absorbe un remanente que no cubrió el stock agregado; queda en disponible negativo. */
        STOCK_NEGATIVO_FORZADO,
        /** Sin stock en ninguna bodega; se usó la última bodega con movimiento registrado. */
        SIN_STOCK_ULTIMO_MOVIMIENTO,
        /** Sin stock y sin movimientos; se usó la última bodega con ingreso de mercancía. */
        SIN_STOCK_ULTIMO_INGRESO,
        /** Sin ningún dato histórico; cae a bodega por defecto (id=1). */
        SIN_HISTORICO_FALLBACK
    }

    private final int idBodega;
    private double cantidad;
    private final double disponibleInicial;
    private Fuente fuente;

    public AsignacionBodega(int idBodega, double cantidad, double disponibleInicial, Fuente fuente) {
        this.idBodega = idBodega;
        this.cantidad = cantidad;
        this.disponibleInicial = disponibleInicial;
        this.fuente = fuente;
    }

    public int getIdBodega() { return idBodega; }
    public double getCantidad() { return cantidad; }
    public double getDisponibleInicial() { return disponibleInicial; }
    public Fuente getFuente() { return fuente; }

    public void sumarCantidad(double extra) { this.cantidad += extra; }
    public void setFuente(Fuente f) { this.fuente = f; }

    @Override
    public String toString() {
        return "AsignacionBodega{bodega=" + idBodega + ", cantidad=" + cantidad
             + ", dispIni=" + disponibleInicial + ", fuente=" + fuente + "}";
    }
}
