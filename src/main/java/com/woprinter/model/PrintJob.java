package com.woprinter.model;

import java.util.Date;

public class PrintJob {

    public enum Estado {
        PENDIENTE, EN_PROCESO, IMPRESO, ERROR
    }

    private String archivoOrigen;
    private String numeroFactura;
    private Impresora impresora;
    private Estado estado;
    private String mensajeError;
    private Date fechaCreacion;
    private Date fechaProcesado;

    public PrintJob(String archivoOrigen, String numeroFactura, Impresora impresora) {
        this.archivoOrigen = archivoOrigen;
        this.numeroFactura = numeroFactura;
        this.impresora = impresora;
        this.estado = Estado.PENDIENTE;
        this.fechaCreacion = new Date();
    }

    // --- Getters y Setters ---

    public String getArchivoOrigen() { return archivoOrigen; }
    public void setArchivoOrigen(String archivoOrigen) { this.archivoOrigen = archivoOrigen; }

    public String getNumeroFactura() { return numeroFactura; }
    public void setNumeroFactura(String numeroFactura) { this.numeroFactura = numeroFactura; }

    public Impresora getImpresora() { return impresora; }
    public void setImpresora(Impresora impresora) { this.impresora = impresora; }

    public Estado getEstado() { return estado; }
    public void setEstado(Estado estado) { this.estado = estado; }

    public String getMensajeError() { return mensajeError; }
    public void setMensajeError(String mensajeError) { this.mensajeError = mensajeError; }

    public Date getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(Date fechaCreacion) { this.fechaCreacion = fechaCreacion; }

    public Date getFechaProcesado() { return fechaProcesado; }
    public void setFechaProcesado(Date fechaProcesado) { this.fechaProcesado = fechaProcesado; }
}
