package com.woprinter.model;

public class ContactoInfo {

    private final int id;
    private final String nombre;
    private final String cedula;

    public ContactoInfo(int id, String nombre, String cedula) {
        this.id = id;
        this.nombre = nombre;
        this.cedula = cedula != null ? cedula : "";
    }

    public int getId() { return id; }
    public String getNombre() { return nombre; }
    public String getCedula() { return cedula; }
}
