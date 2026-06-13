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
    private final HtmlFacturaParserService htmlParser;
    private final OrdenGeneratorService ordenGen;

    private volatile boolean running;
    private Thread watchThread;
    private Thread moverThread;

    private final List<WatcherListener> listeners;
    private final Set<String> facturasProcesadas;
    private final CopyOnWriteArrayList<PendienteMover> pendientesMover;

    // Registro de archivos ya vistos con su tamanio para detectar nuevos o modificados
    private final Map<String, Long> archivosConocidos;

    // Facturas HTML multipagina que estan esperando a que WorldOffice termine
    // de escribir todas sus paginas (path del archivo base -> primer intento)
    private final Map<String, Long> htmlEsperandoPaginas;

    // Intervalo de polling en milisegundos
    private static final long POLL_INTERVAL = 2000;

    // Tiempo maximo de espera por las paginas faltantes de un HTML multipagina
    private static final long HTML_PAGINAS_TIMEOUT_MS = 30000;

    public FileWatcherService() {
        this.config = AppConfig.getInstance();
        this.parser = new ExcelParserService();
        this.htmlParser = new HtmlFacturaParserService();
        this.ordenGen = new OrdenGeneratorService();
        this.listeners = new ArrayList<WatcherListener>();
        this.facturasProcesadas = Collections.synchronizedSet(new HashSet<String>());
        this.pendientesMover = new CopyOnWriteArrayList<PendienteMover>();
        this.archivosConocidos = new HashMap<String, Long>();
        this.htmlEsperandoPaginas = new HashMap<String, Long>();
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
            if (archivo.isFile() && esExtensionSoportada(name) && !name.startsWith("~$")) {
                archivosConocidos.put(archivo.getAbsolutePath(), archivo.length());
            }
        }
        System.out.println("[WATCHER] Archivos existentes registrados: " + archivosConocidos.size());
    }

    private boolean esExtensionSoportada(String nombre) {
        String lower = nombre.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".html");
    }

    /**
     * Escanea la carpeta buscando archivos nuevos o modificados.
     * Un archivo se considera listo cuando:
     * 1. Es .xlsx o .html y no empieza con ~$
     * 2. No estaba registrado O cambio de tamanio
     * 3. Su tamanio es estable (no cambio entre dos lecturas = ya termino de escribirse)
     *
     * Las paginas secundarias de un HTML multipagina ("NNNNNPáginaK.html") no
     * disparan procesamiento propio: se procesan y se mueven junto con su
     * archivo base ("NNNNN.html").
     */
    private void escanearCarpeta(File watchDir) {
        File[] archivos = watchDir.listFiles();
        if (archivos == null) return;

        for (File archivo : archivos) {
            String name = archivo.getName();

            // Ignorar extensiones no soportadas y archivos temporales de Excel
            if (!archivo.isFile()) continue;
            if (!esExtensionSoportada(name)) continue;
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
                if (HtmlFacturaParserService.esPaginaSecundaria(name)) {
                    // Se procesara junto con su archivo base
                    archivosConocidos.put(path, -1L);
                    continue;
                }
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
     * Procesa un archivo de factura (.xlsx o .html):
     *   1. Parsea el archivo de WorldOffice con el parser segun su extensión
     *   2. Orquestador OrdenGeneratorService:
     *      - Idempotencia (facturas_impresas)
     *      - Clasificación de ítems + novedades
     *      - INSERT facturas_cabeceras/detalles + UPDATE pendientes + movimientos (AUTOMATICO)
     *      - Registro en facturas_impresas / detalle_factura / novedades_facturas
     *      - Todo transaccional
     *   3. Mueve el archivo (y sus páginas, si es HTML multipágina) a procesados o errores
     *
     * Si el HTML es multipágina y aún faltan páginas en disco, se reintenta en
     * cada ciclo de polling hasta HTML_PAGINAS_TIMEOUT_MS antes de declararlo error.
     *
     * La aplicación ya NO imprime tirillas: el sistema aliado (control bodega)
     * notifica las órdenes generadas por su cuenta.
     */
    private void procesarArchivo(File archivo) {
        String nombreArchivo = archivo.getName();
        String path = archivo.getAbsolutePath();
        boolean esHtml = nombreArchivo.toLowerCase().endsWith(".html");

        // No repetir la notificación en cada reintento de un HTML que espera páginas
        if (!htmlEsperandoPaginas.containsKey(path)) {
            System.out.println("[WATCHER] Archivo detectado: " + nombreArchivo);
            notifyArchivoDetectado(nombreArchivo);
        }

        try {
            Factura factura = esHtml ? htmlParser.parsear(archivo) : parser.parsear(archivo);
            htmlEsperandoPaginas.remove(path);
            String claveFactura = factura.getNumeroCompleto();

            // Anti-duplicado en memoria (complementa al check persistente del orquestador)
            if (facturasProcesadas.contains(claveFactura)) {
                notifyInfo("Factura " + claveFactura + " ya procesada en esta sesión, ignorando");
                moverConjuntoODejarPendiente(archivo, config.getProcessedFolder());
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
                moverConjuntoODejarPendiente(archivo, config.getProcessedFolder());
                return;
            }

            if (resultado.getEstado() == ResultadoOrden.Estado.ERROR) {
                notifyError(nombreArchivo, "Falla orquestador: " + resultado.getMensajeError());
                moverConjuntoODejarPendiente(archivo, config.getErrorFolder());
                return;
            }

            notifyInfo("Factura " + claveFactura + " procesada: "
                    + resultado.getOrdenes().size() + " orden(es), "
                    + resultado.getNovedades().size() + " novedad(es)");
            moverConjuntoODejarPendiente(archivo, config.getProcessedFolder());

        } catch (HtmlFacturaParserService.PaginasIncompletasException e) {
            manejarPaginasIncompletas(archivo, e);

        } catch (Exception e) {
            htmlEsperandoPaginas.remove(path);
            System.err.println("[WATCHER] Error procesando " + nombreArchivo + ": " + e.getMessage());
            e.printStackTrace();
            notifyError(nombreArchivo, e.getMessage());
            moverConjuntoODejarPendiente(archivo, config.getErrorFolder());
        }
    }

    /**
     * Un HTML multipágina referencia páginas que aún no están en disco.
     * Se reintenta en cada ciclo de polling; si tras HTML_PAGINAS_TIMEOUT_MS
     * siguen faltando, el conjunto se mueve a errores.
     */
    private void manejarPaginasIncompletas(File archivo, HtmlFacturaParserService.PaginasIncompletasException e) {
        String path = archivo.getAbsolutePath();
        long ahora = System.currentTimeMillis();
        Long primerIntento = htmlEsperandoPaginas.get(path);

        if (primerIntento == null) {
            primerIntento = ahora;
            htmlEsperandoPaginas.put(path, ahora);
            notifyInfo("Factura HTML multipágina, esperando páginas: " + e.getMessage());
        }

        if (ahora - primerIntento >= HTML_PAGINAS_TIMEOUT_MS) {
            htmlEsperandoPaginas.remove(path);
            System.err.println("[WATCHER] Páginas incompletas (timeout): " + archivo.getName());
            notifyError(archivo.getName(), "Páginas incompletas tras "
                    + (HTML_PAGINAS_TIMEOUT_MS / 1000) + "s de espera: " + e.getMessage());
            moverConjuntoODejarPendiente(archivo, config.getErrorFolder());
        } else {
            // Re-armar el registro para que el próximo ciclo lo reintente
            archivosConocidos.put(path, archivo.length());
        }
    }

    private void moverODejarPendiente(File archivo, String destFolder) {
        if (!moverArchivo(archivo, destFolder)) agregarPendienteMover(archivo, destFolder);
    }

    /**
     * Mueve el archivo y, si es un HTML multipágina, también todas sus
     * páginas secundarias presentes en disco.
     */
    private void moverConjuntoODejarPendiente(File archivo, String destFolder) {
        if (archivo.getName().toLowerCase().endsWith(".html")) {
            for (File pagina : HtmlFacturaParserService.buscarPaginasSecundarias(archivo)) {
                moverODejarPendiente(pagina, destFolder);
            }
        }
        moverODejarPendiente(archivo, destFolder);
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
