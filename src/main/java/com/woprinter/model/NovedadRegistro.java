package com.woprinter.model;

import java.sql.Timestamp;

public class NovedadRegistro {

    public enum Estado { PENDIENTE, REVISADO, RESUELTO, IGNORADO }

    private int id;
    private Integer facturaImpresaId;
    private String numeroFactura;
    private Timestamp fechaDeteccion;
    private String tipo;
    private String codigoOriginal;
    private String codigoNormalizado;
    private String descripcion;
    private double cantidad;
    private String motivo;
    private String estadoRevision;
    private Integer idProductoAsociado;
    private String codigoProductoAsociado;  // denormalizado por JOIN
    private String observacionRevision;
    private Integer revisadoPor;
    private String revisadoPorNombre;       // denormalizado por JOIN
    private Timestamp fechaRevision;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public Integer getFacturaImpresaId() { return facturaImpresaId; }
    public void setFacturaImpresaId(Integer v) { this.facturaImpresaId = v; }

    public String getNumeroFactura() { return numeroFactura; }
    public void setNumeroFactura(String v) { this.numeroFactura = v; }

    public Timestamp getFechaDeteccion() { return fechaDeteccion; }
    public void setFechaDeteccion(Timestamp v) { this.fechaDeteccion = v; }

    public String getTipo() { return tipo; }
    public void setTipo(String v) { this.tipo = v; }

    public String getCodigoOriginal() { return codigoOriginal; }
    public void setCodigoOriginal(String v) { this.codigoOriginal = v; }

    public String getCodigoNormalizado() { return codigoNormalizado; }
    public void setCodigoNormalizado(String v) { this.codigoNormalizado = v; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String v) { this.descripcion = v; }

    public double getCantidad() { return cantidad; }
    public void setCantidad(double v) { this.cantidad = v; }

    public String getMotivo() { return motivo; }
    public void setMotivo(String v) { this.motivo = v; }

    public String getEstadoRevision() { return estadoRevision; }
    public void setEstadoRevision(String v) { this.estadoRevision = v; }

    public Integer getIdProductoAsociado() { return idProductoAsociado; }
    public void setIdProductoAsociado(Integer v) { this.idProductoAsociado = v; }

    public String getCodigoProductoAsociado() { return codigoProductoAsociado; }
    public void setCodigoProductoAsociado(String v) { this.codigoProductoAsociado = v; }

    public String getObservacionRevision() { return observacionRevision; }
    public void setObservacionRevision(String v) { this.observacionRevision = v; }

    public Integer getRevisadoPor() { return revisadoPor; }
    public void setRevisadoPor(Integer v) { this.revisadoPor = v; }

    public String getRevisadoPorNombre() { return revisadoPorNombre; }
    public void setRevisadoPorNombre(String v) { this.revisadoPorNombre = v; }

    public Timestamp getFechaRevision() { return fechaRevision; }
    public void setFechaRevision(Timestamp v) { this.fechaRevision = v; }
}
