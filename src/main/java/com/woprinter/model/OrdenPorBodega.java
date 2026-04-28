package com.woprinter.model;

import java.util.ArrayList;
import java.util.List;

public class OrdenPorBodega {

    public static class ItemOrden {
        private final int idProducto;
        private final String codigo;
        private final String descripcion;
        private final double cantidad;
        private final double disponibleInicial;
        private final AsignacionBodega.Fuente fuente;

        public ItemOrden(int idProducto, String codigo, String descripcion,
                         double cantidad, double disponibleInicial,
                         AsignacionBodega.Fuente fuente) {
            this.idProducto = idProducto;
            this.codigo = codigo;
            this.descripcion = descripcion;
            this.cantidad = cantidad;
            this.disponibleInicial = disponibleInicial;
            this.fuente = fuente;
        }

        public int getIdProducto() { return idProducto; }
        public String getCodigo() { return codigo; }
        public String getDescripcion() { return descripcion; }
        public double getCantidad() { return cantidad; }
        public double getDisponibleInicial() { return disponibleInicial; }
        public AsignacionBodega.Fuente getFuente() { return fuente; }
    }

    private final int idBodega;
    private final String nombreBodega;
    private int idCabeceraGenerada = -1;
    private final List<ItemOrden> items = new ArrayList<ItemOrden>();

    public OrdenPorBodega(int idBodega, String nombreBodega) {
        this.idBodega = idBodega;
        this.nombreBodega = nombreBodega;
    }

    public int getIdBodega() { return idBodega; }
    public String getNombreBodega() { return nombreBodega; }
    public int getIdCabeceraGenerada() { return idCabeceraGenerada; }
    public void setIdCabeceraGenerada(int id) { this.idCabeceraGenerada = id; }
    public List<ItemOrden> getItems() { return items; }
    public void addItem(ItemOrden item) { items.add(item); }
}
