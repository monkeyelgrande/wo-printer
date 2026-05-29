package com.woprinter.service;

import com.woprinter.config.AppConfig;
import com.woprinter.model.Factura;
import com.woprinter.model.ResultadoOrden;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class FileWatcherService implements Runnable {

    public interface WatcherListener {
        void onArchivoDetectado(String archivo);
        void onFacturaProcesada(String archivo, Factura factura);
        void onError(String archivo, String mensaje);
        void onEstadoCambiado(boolean vigilando);
        void onInfo(String mensaje);
    }

    private final AppConfig config;
    private final ExcelParserService parser;
    private final OrdenGeneratorService ordenGen;

    private volatile boolean running;
    private Thread watchThread;
    private Thread moverThread;

    private final List<WatcherListener> listeners;
    private final Set<String> facturasProcesadas;
    private final CopyOnWriteArrayList<PendienteMover> pendientesMover;

    // Registro de archivos ya vistos con su tamanio para detectar nuevos o modificados
    private final Map<String, Long> archivosConocidos;

    // Intervalo de polling en milisegundos
    private static final long POLL_INTERVAL = 2000;

    public FileWatcherService() {
        this.config = AppConfig.getInstance();
        this.parser = new ExcelParserService();
        this.ordenGen = new OrdenGeneratorService();
        this.listeners = new ArrayList<WatcherListener>();
        this.facturasProcesadas = Collections.synchronizedSet(new HashSet<String>());
        this.pendientesMover = new CopyOnWriteArrayList<PendienteMover>();
        this.archivosConocidos = new HashMap<String, Long>();
    }

    public void addListener(WatcherListener listener) { listeners.add(listener); }
    public boolean isRunning() { return running; }

    public void iniciar() {
        if (running) return;
        crearCarpetas();

        running = true;
        watchThread = new Thread(this, "FileWatcher-Thread");
        watchThread.setDaemon(true);
        watchThread.start();

        moverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(10000);
                        reintentarMoverPendientes();
                    } catch (InterruptedException e) {
                        if (!running) break;
                    }
                }
            }
        }, "FileMover-Thread");
        moverThread.setDaemon(true);
        moverThread.start();

        notifyEstado(true);
        System.out.println("[WATCHER] Iniciado - Vigilando: " + config.getWatchFolder() + " (polling cada " + POLL_INTERVAL + "ms)");
    }

    public void detener() {
        running = false;
        if (watchThread != null) watchThread.interrupt();
        if (moverThread != null) moverThread.interrupt();
        notifyEstado(false);
        System.out.println("[WATCHER] Detenido");
    }

    @Override
    public void run() {
        File watchDir = new File(config.getWatchFolder());

        // Registrar archivos existentes al inicio para no reprocesarlos
        registrarArchivosExistentes(watchDir);

        while (running) {
            try {
                escanearCarpeta(watchDir);
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                if (!running) break;
            } catch (Exception e) {
                System.err.println("[WATCHER] Error en ciclo de polling: " + e.getMessage());
                try { Thread.sleep(POLL_INTERVAL); } catch (InterruptedException ie) { break; }
            }
        }

        running = false;
        notifyEstado(false);
    }

    /**
     * Registra los archivos existentes al inicio para no procesarlos de nuevo.
     */
    private void registrarArchivosExistentes(File watchDir) {
        File[] archivos = watchDir.listFiles();
        if (archivos == null) return;

        for (File archivo : archivos) {
            String name = archivo.getName();
            if (archivo.isFile() && name.toLowerCase().endsWith(".xlsx") && !name.startsWith("~$")) {
                archivosConocidos.put(archivo.getAbsolutePath(), archivo.length());
            }
        }
        System.out.println("[WATCHER] Archivos existentes registrados: " + archivosConocidos.size());
    }

    /**
     * Escanea la carpeta buscando archivos nuevos o modificados.
     * Un archivo se considera listo cuando:
     * 1. Es .xlsx y no empieza con ~$
     * 2. No estaba registrado O cambio de tamanio
     * 3. Su tamanio es estable (no cambio entre dos lecturas = ya termino de escribirse)
     */
    private void escanearCarpeta(File watchDir) {
        File[] archivos = watchDir.listFiles();
        if (archivos == null) return;

        for (File archivo : archivos) {
            String name = archivo.getName();

            // Ignorar no-xlsx y archivos temporales de Excel
            if (!archivo.isFile()) continue;
            if (!name.toLowerCase().endsWith(".xlsx")) continue;
            if (name.startsWith("~$")) continue;

            String path = archivo.getAbsolutePath();
            long tamanioActual = archivo.length();

            // Ignorar archivos vacios (aun creandose)
            if (tamanioActual == 0) continue;

            Long tamanioAnterior = archivosConocidos.get(path);

            if (tamanioAnterior == null) {
                // Archivo nuevo: registrar su tamanio, lo procesamos en el siguiente ciclo
                // cuando confirmemos que el tamanio no cambio (archivo estable)
                archivosConocidos.put(path, tamanioActual);
                System.out.println("[WATCHER] Archivo nuevo detectado, esperando estabilidad: " + name + " (" + tamanioActual + " bytes)");

            } else if (tamanioAnterior == -1L) {
                // Ya fue procesado, ignorar
                continue;

            } else if (tamanioAnterior != tamanioActual) {
                // El archivo cambio de tamanio (se esta escribiendo aun o fue modificado)
                archivosConocidos.put(path, tamanioActual);
                System.out.println("[WATCHER] Archivo cambiando: " + name + " (" + tamanioAnterior + " -> " + tamanioActual + " bytes)");

            } else {
                // El tamanio es igual al anterior = archivo estable, listo para procesar
                System.out.println("[WATCHER] Archivo estable, procesando: " + name);
                archivosConocidos.put(path, -1L); // Marcar como procesado
                procesarArchivo(archivo);
            }
        }

        // Limpiar archivos que ya no existen (fueron movidos)
        Iterator<Map.Entry<String, Long>> it = archivosConocidos.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (!new File(entry.getKey()).exists()) {
                it.remove();
            }
        }
    }

    /**
     * Procesa un archivo Excel:
     *   1. Parsea el Excel de WorldOffice
     *   2. Orquestador OrdenGeneratorService:
     *      - Idempotencia (facturas_impresas)
     *      - Clasificación de ítems + novedades
     *      - INSERT facturas_cabeceras/detalles + UPDATE pendientes + movimientos (AUTOMATICO)
     *      - Registro en facturas_impresas / detalle_factura / novedades_facturas
     *      - Todo transaccional
     *   3. Mueve el archivo a procesados o errores
     *
     * La aplicación ya NO imprime tirillas: el sistema aliado (control bodega)
     * notifica las órdenes generadas por su cuenta.
     */
    private void procesarArchivo(File archivo) {
        String nombreArchivo = archivo.getName();
        System.out.println("[WATCHER] Archivo detectado: " + nombreArchivo);
        notifyArchivoDetectado(nombreArchivo);

        try {
            Factura factura = parser.parsear(archivo);
            String claveFactura = factura.getNumeroCompleto();

            // Anti-duplicado en memoria (complementa al check persistente del orquestador)
            if (facturasProcesadas.contains(claveFactura)) {
                notifyInfo("Factura " + claveFactura + " ya procesada en esta sesión, ignorando");
                moverODejarPendiente(archivo, config.getProcessedFolder());
                return;
            }
            facturasProcesadas.add(claveFactura);

            System.out.println("[WATCHER] Factura parseada: " + claveFactura
                    + " - " + factura.getItems().size() + " items");
            notifyFacturaProcesada(nombreArchivo, factura);

            // Orquestación transaccional (creación de órdenes y registros)
            ResultadoOrden resultado = ordenGen.procesar(factura);

            if (resultado.isDuplicada()) {
                notifyInfo("Factura " + claveFactura + " ya estaba en BD; se omite");
                moverODejarPendiente(archivo, config.getProcessedFolder());
                return;
            }

            if (resultado.getEstado() == ResultadoOrden.Estado.ERROR) {
                notifyError(nombreArchivo, "Falla orquestador: " + resultado.getMensajeError());
                moverODejarPendiente(archivo, config.getErrorFolder());
                return;
            }

            notifyInfo("Factura " + claveFactura + " procesada: "
                    + resultado.getOrdenes().size() + " orden(es), "
                    + resultado.getNovedades().size() + " novedad(es)");
            moverODejarPendiente(archivo, config.getProcessedFolder());

        } catch (Exception e) {
            System.err.println("[WATCHER] Error procesando " + nombreArchivo + ": " + e.getMessage());
            e.printStackTrace();
            notifyError(nombreArchivo, e.getMessage());
            moverODejarPendiente(archivo, config.getErrorFolder());
        }
    }

    private void moverODejarPendiente(File archivo, String destFolder) {
        if (!moverArchivo(archivo, destFolder)) agregarPendienteMover(archivo, destFolder);
    }

    // ================================================================
    // Mover archivos
    // ================================================================

    private boolean moverArchivo(File archivo, String destFolder) {
        try {
            File destDir = new File(destFolder);
            if (!destDir.exists()) destDir.mkdirs();

            Path origen = archivo.toPath();
            Path destino = Paths.get(destFolder, archivo.getName());

            if (destino.toFile().exists()) {
                String name = archivo.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                destino = Paths.get(destFolder, base + "_" + System.currentTimeMillis() + ext);
            }

            Files.move(origen, destino, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[WATCHER] Archivo movido a: " + destino);
            return true;
        } catch (IOException e) {
            System.out.println("[WATCHER] Archivo bloqueado, se reintentara mover: " + archivo.getName());
            return false;
        }
    }

    private void agregarPendienteMover(File archivo, String destFolder) {
        for (PendienteMover p : pendientesMover) {
            if (p.archivo.getAbsolutePath().equals(archivo.getAbsolutePath())) return;
        }
        pendientesMover.add(new PendienteMover(archivo, destFolder));
        notifyInfo("Archivo pendiente de mover: " + archivo.getName());
    }

    private void reintentarMoverPendientes() {
        if (pendientesMover.isEmpty()) return;
        Iterator<PendienteMover> it = pendientesMover.iterator();
        while (it.hasNext()) {
            PendienteMover p = it.next();
            if (!p.archivo.exists()) { pendientesMover.remove(p); continue; }
            if (moverArchivo(p.archivo, p.destFolder)) {
                pendientesMover.remove(p);
                notifyInfo("Archivo movido exitosamente: " + p.archivo.getName());
            } else {
                p.intentos++;
                if (p.intentos > 60) {
                    pendientesMover.remove(p);
                    notifyInfo("Se abandono el intento de mover: " + p.archivo.getName());
                }
            }
        }
    }

    private void crearCarpetas() {
        new File(config.getWatchFolder()).mkdirs();
        new File(config.getProcessedFolder()).mkdirs();
        new File(config.getErrorFolder()).mkdirs();
    }

    private static class PendienteMover {
        final File archivo;
        final String destFolder;
        int intentos;
        PendienteMover(File archivo, String destFolder) {
            this.archivo = archivo;
            this.destFolder = destFolder;
            this.intentos = 0;
        }
    }

    // --- Notificaciones ---
    private void notifyArchivoDetectado(String archivo) { for (WatcherListener l : listeners) l.onArchivoDetectado(archivo); }
    private void notifyFacturaProcesada(String archivo, Factura factura) { for (WatcherListener l : listeners) l.onFacturaProcesada(archivo, factura); }
    private void notifyError(String archivo, String mensaje) { for (WatcherListener l : listeners) l.onError(archivo, mensaje); }
    private void notifyEstado(boolean vigilando) { for (WatcherListener l : listeners) l.onEstadoCambiado(vigilando); }
    private void notifyInfo(String mensaje) { for (WatcherListener l : listeners) l.onInfo(mensaje); }
}
