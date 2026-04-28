package com.woprinter.service;

import com.woprinter.model.Impresora;

import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PrinterService {

    public PrinterService() {}

    /**
     * Envia bytes ESC/POS a una impresora instalada en Windows por su nombre.
     */
    public void imprimir(Impresora impresora, byte[] data) throws IOException {
        PrintService printService = buscarImpresora(impresora.getNombreWindows());
        if (printService == null) {
            throw new IOException("Impresora no encontrada en Windows: " + impresora.getNombreWindows());
        }

        try {
            DocPrintJob job = printService.createPrintJob();
            DocFlavor flavor = DocFlavor.INPUT_STREAM.AUTOSENSE;
            Doc doc = new SimpleDoc(new ByteArrayInputStream(data), flavor, null);
            PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
            job.print(doc, attrs);

            System.out.println("[PRINTER] Enviado a " + impresora.getNombreWindows()
                    + " - " + data.length + " bytes");
        } catch (PrintException e) {
            throw new IOException("Error imprimiendo en " + impresora.getNombreWindows() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Verifica si una impresora existe en Windows.
     */
    public boolean testConexion(Impresora impresora) {
        PrintService ps = buscarImpresora(impresora.getNombreWindows());
        return ps != null;
    }

    /**
     * Busca una impresora instalada en Windows por su nombre exacto.
     */
    private PrintService buscarImpresora(String nombreWindows) {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        for (PrintService service : services) {
            if (service.getName().equalsIgnoreCase(nombreWindows)) {
                return service;
            }
        }
        return null;
    }

    /**
     * Lista todas las impresoras instaladas en Windows.
     * Util para mostrar al usuario los nombres disponibles.
     */
    public static List<String> listarImpresorasWindows() {
        List<String> nombres = new ArrayList<String>();
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        for (PrintService service : services) {
            nombres.add(service.getName());
        }
        return nombres;
    }
}
