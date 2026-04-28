package com.woprinter.model;

public class ConfiguracionEmpresa {

    private String nombre;
    private String nit;
    private String contacto;
    private String direccion;

    public ConfiguracionEmpresa() {}

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getNit() { return nit; }
    public void setNit(String nit) { this.nit = nit; }

    public String getContacto() { return contacto; }
    public void setContacto(String contacto) { this.contacto = contacto; }

    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
}