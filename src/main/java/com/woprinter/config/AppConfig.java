package com.woprinter.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {

    private static AppConfig instance;
    private Properties props;

    private AppConfig() {
        props = new Properties();
        try {
            // Primero intenta cargar desde archivo externo (junto al JAR)
            File externalConfig = new File("config.properties");
            if (externalConfig.exists()) {
                props.load(new FileInputStream(externalConfig));
                System.out.println("[CONFIG] Cargado desde archivo externo: " + externalConfig.getAbsolutePath());
            } else {
                // Si no existe, cargar desde resources dentro del JAR
                InputStream is = getClass().getClassLoader().getResourceAsStream("config.properties");
                if (is != null) {
                    props.load(is);
                    is.close();
                    System.out.println("[CONFIG] Cargado desde resources internos");
                }
            }
        } catch (Exception e) {
            System.err.println("[CONFIG] Error cargando configuracion: " + e.getMessage());
        }
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            instance = new AppConfig();
        }
        return instance;
    }

    public String get(String key) {
        return props.getProperty(key, "");
    }

    public String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = props.getProperty(key);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    // Getters de conveniencia
    public String getDbUrl() { return get("db.url"); }
    public String getDbUser() { return get("db.user"); }
    public String getDbPassword() { return get("db.password"); }
    public String getWatchFolder() { return get("watch.folder"); }
    public String getProcessedFolder() { return get("watch.folder.procesados"); }
    public String getErrorFolder() { return get("watch.folder.errores"); }
    public int getWatchDelay() { return getInt("watch.delay.ms", 2000); }
    public boolean isGenerarOrdenesAutomaticas() { return getBoolean("ordenes.generar.automaticas", true); }
}
