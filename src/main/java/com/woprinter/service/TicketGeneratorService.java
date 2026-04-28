package com.woprinter.service;

import com.woprinter.config.AppConfig;
import com.woprinter.model.Factura;
import com.woprinter.model.ItemFactura;
import com.woprinter.model.Novedad;
import com.woprinter.model.OrdenPorBodega;
import com.woprinter.model.OrdenPorBodega.ItemOrden;
import com.woprinter.model.AsignacionBodega;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class TicketGeneratorService {

    // Comandos ESC/POS
    private static final byte[] ESC_INIT       = {0x1B, 0x40};
    private static final byte[] ESC_BOLD_ON    = {0x1B, 0x45, 0x01};
    private static final byte[] ESC_BOLD_OFF   = {0x1B, 0x45, 0x00};
    private static final byte[] ESC_CENTER     = {0x1B, 0x61, 0x01};
    private static final byte[] ESC_LEFT       = {0x1B, 0x61, 0x00};
    private static final byte[] ESC_DOUBLE_H   = {0x1B, 0x21, 0x10};
    private static final byte[] ESC_DOUBLE_WH  = {0x1B, 0x21, 0x30};
    private static final byte[] ESC_NORMAL     = {0x1B, 0x21, 0x00};
    private static final byte[] ESC_PARTIAL_CUT = {0x1D, 0x56, 0x01};
    private static final byte[] ESC_FEED_3     = {0x1B, 0x64, 0x03};
    private static final byte[] LINE_FEED      = {0x0A};

    private static final int COL_CODIGO = 10;
    private static final int COL_CANT = 8;

    private final int charWidth;
    private final DecimalFormat dfQty;
    private final SimpleDateFormat sdfDate;
    private final SimpleDateFormat sdfImpresion;

    public TicketGeneratorService() {
        this.charWidth = AppConfig.getInstance().getPrinterCharWidth();
        this.dfQty = new DecimalFormat("#,##0.##");
        this.sdfDate = new SimpleDateFormat("dd/MM/yyyy");
        this.sdfImpresion = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    }

    // ================================================================
    // TIRILLA BODEGA (sin precios - Orden de Despacho)
    // ================================================================

    public byte[] generarTirillaBodega(Factura factura) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ESC_INIT);

        // Encabezado empresa
        out.write(ESC_CENTER);
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        writeLine(out, factura.getEmpresa() != null ? factura.getEmpresa() : "");
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);
        writeLine(out, "");

        out.write(ESC_CENTER);
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        writeLine(out, "ORDEN DE DESPACHO");
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);
        writeLine(out, "");

        // Datos factura
        out.write(ESC_LEFT);
        writeLine(out, separador());
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        writeLine(out, "Concepto: " + (factura.getConcepto() != null ? factura.getConcepto() : ""));
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);
        writeLine(out, "Factura:  " + factura.getNumeroCompleto());
        if (factura.getFecha() != null) {
            writeLine(out, "Fecha:    " + sdfDate.format(factura.getFecha()));
        }
        writeLine(out, "Impreso:  " + sdfImpresion.format(new Date()));
        writeLine(out, "Vendedor: " + crop(factura.getVendedor(), charWidth - 10));
        writeLine(out, "F.Pago:   " + (factura.getFormaPago() != null ? factura.getFormaPago() : ""));

        // Cliente grande
        writeClienteGrande(out, factura);

        // Items sin precio
        out.write(ESC_BOLD_ON);
        int descW = charWidth - COL_CODIGO - COL_CANT;
        writeLine(out, padRight("CODIGO", COL_CODIGO) + padRight("DESCRIPCION", descW) + padLeft("CANT", COL_CANT));
        out.write(ESC_BOLD_OFF);
        writeLine(out, separador());

        for (ItemFactura item : factura.getItems()) {
            writeItemBodega(out, item);
        }

        // Total items
        writeLine(out, separador());
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        out.write(ESC_CENTER);
        writeLine(out, "TOTAL ITEMS: " + factura.getItems().size());
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);

        writeLine(out, "");
        out.write(ESC_CENTER);
        writeLine(out, "** ORDEN DE DESPACHO **");
        writeLine(out, "");
        out.write(ESC_FEED_3);
        out.write(ESC_PARTIAL_CUT);

        return out.toByteArray();
    }

    // ================================================================
    // TIRILLA ORDEN POR BODEGA (wo-printer auto - sin precios)
    // ================================================================

    /**
     * Ticket de despacho para una bodega específica, con los ítems que le fueron
     * asignados por el algoritmo de {@code BodegaAsignacionService}.
     */
    public byte[] generarTirillaOrden(Factura factura, OrdenPorBodega orden) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ESC_INIT);

        // Encabezado
        out.write(ESC_CENTER);
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        writeLine(out, factura.getEmpresa() != null ? factura.getEmpresa() : "");
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);
        writeLine(out, "");

        // Titulo ORDEN DE DESPACHO con el nombre de la bodega destino
        out.write(ESC_CENTER);
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        writeLine(out, "ORDEN DE DESPACHO");
        writeLine(out, crop(orden.getNombreBodega(), charWidth / 2));
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);
        writeLine(out, "");

        // Datos factura
        out.write(ESC_LEFT);
        writeLine(out, separador());
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        writeLine(out, "Concepto: " + (factura.getConcepto() != null ? factura.getConcepto() : ""));
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);
        writeLine(out, "Factura:  " + factura.getNumeroCompleto());
        if (orden.getIdCabeceraGenerada() > 0) {
            writeLine(out, "Orden #:  " + orden.getIdCabeceraGenerada());
        }
        if (factura.getFecha() != null) {
            writeLine(out, "Fecha:    " + sdfDate.format(factura.getFecha()));
        }
        writeLine(out, "Impreso:  " + sdfImpresion.format(new Date()));
        writeLine(out, "Vendedor: " + crop(factura.getVendedor(), charWidth - 10));

        // Cliente grande
        writeClienteGrande(out, factura);

        // Header items
        out.write(ESC_BOLD_ON);
        int descW = charWidth - COL_CODIGO - COL_CANT;
        writeLine(out, padRight("CODIGO", COL_CODIGO) + padRight("DESCRIPCION", descW) + padLeft("CANT", COL_CANT));
        out.write(ESC_BOLD_OFF);
        writeLine(out, separador());

        // Items asignados + marcar los que vienen forzados en negativo
        for (ItemOrden it : orden.getItems()) {
            writeItemOrden(out, it);
        }

        writeLine(out, separador());
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        out.write(ESC_CENTER);
        writeLine(out, "TOTAL ITEMS: " + orden.getItems().size());
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);

        writeLine(out, "");
        out.write(ESC_CENTER);
        writeLine(out, "** ORDEN AUTO WO-PRINTER **");
        writeLine(out, "");
        out.write(ESC_FEED_3);
        out.write(ESC_PARTIAL_CUT);

        return out.toByteArray();
    }

    private void writeItemOrden(ByteArrayOutputStream out, ItemOrden item) throws IOException {
        String codigoStr = item.getCodigo() != null ? item.getCodigo() : "";
        String cantStr = dfQty.format(item.getCantidad());
        int descW = charWidth - COL_CODIGO - COL_CANT;

        String descFull = item.getDescripcion() != null ? item.getDescripcion() : "";
        if (item.getFuente() == AsignacionBodega.Fuente.STOCK_NEGATIVO_FORZADO) {
            descFull = "[NEG] " + descFull;
        } else if (item.getFuente() == AsignacionBodega.Fuente.SIN_STOCK_ULTIMO_MOVIMIENTO
                || item.getFuente() == AsignacionBodega.Fuente.SIN_STOCK_ULTIMO_INGRESO) {
            descFull = "[SIN STK] " + descFull;
        } else if (item.getFuente() == AsignacionBodega.Fuente.SIN_HISTORICO_FALLBACK) {
            descFull = "[SIN HIST] " + descFull;
        }
        String[] descLines = wrap(descFull, descW);

        String lineStart = padRight(codigoStr, COL_CODIGO) + padRight(descLines[0], descW);
        writeRaw(out, lineStart);
        out.write(ESC_BOLD_ON);
        writeLine(out, padLeft(cantStr, COL_CANT));
        out.write(ESC_BOLD_OFF);

        for (int i = 1; i < descLines.length; i++) {
            writeLine(out, padRight("", COL_CODIGO) + descLines[i]);
        }
        out.write(LINE_FEED);
    }

    // ================================================================
    // TIRILLA NOVEDADES (productos que NO generaron orden)
    // ================================================================

    public byte[] generarTirillaNovedades(Factura factura, List<Novedad> novedades) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ESC_INIT);

        out.write(ESC_CENTER);
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_H);
        writeLine(out, "!! NOVEDADES !!");
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);
        writeLine(out, "");

        out.write(ESC_LEFT);
        writeLine(out, separador());
        out.write(ESC_BOLD_ON);
        writeLine(out, "Factura:  " + factura.getNumeroCompleto());
        out.write(ESC_BOLD_OFF);
        if (factura.getFecha() != null) writeLine(out, "Fecha:    " + sdfDate.format(factura.getFecha()));
        writeLine(out, "Impreso:  " + sdfImpresion.format(new Date()));
        writeLine(out, "Cliente:  " + crop(factura.getCliente(), charWidth - 10));
        writeLine(out, separador());

        writeLine(out, "Items que NO generaron orden:");
        writeLine(out, "");

        for (Novedad n : novedades) {
            out.write(ESC_BOLD_ON);
            writeLine(out, "> " + n.getTipo().name());
            out.write(ESC_BOLD_OFF);
            String cod = n.getCodigoOriginal() != null ? n.getCodigoOriginal() : "";
            String desc = n.getDescripcion() != null ? n.getDescripcion() : "";
            writeLine(out, "  Cod: " + cod);
            if (!desc.isEmpty()) {
                for (String l : wrap("  Desc: " + desc, charWidth)) writeLine(out, l);
            }
            writeLine(out, "  Cant: " + dfQty.format(n.getCantidad()));
            if (n.getMotivo() != null && !n.getMotivo().isEmpty()) {
                for (String l : wrap("  Motivo: " + n.getMotivo(), charWidth)) writeLine(out, l);
            }
            writeLine(out, "");
        }

        writeLine(out, separador());
        out.write(ESC_CENTER);
        out.write(ESC_BOLD_ON);
        writeLine(out, "Total novedades: " + novedades.size());
        out.write(ESC_BOLD_OFF);
        writeLine(out, "");
        writeLine(out, "** REQUIERE REVISION **");
        writeLine(out, "");
        out.write(ESC_FEED_3);
        out.write(ESC_PARTIAL_CUT);

        return out.toByteArray();
    }

    // ================================================================
    // Metodo legacy (compatibilidad) -> llama a tirilla bodega
    // ================================================================

    public byte[] generarTirilla(Factura factura) throws IOException {
        return generarTirillaBodega(factura);
    }

    // ================================================================
    // Escritura de items
    // ================================================================

    private void writeItemBodega(ByteArrayOutputStream out, ItemFactura item) throws IOException {
        String codigoStr = item.getCodigo() != null ? item.getCodigo() : "";
        String cantStr = dfQty.format(item.getCantidad());
        int descW = charWidth - COL_CODIGO - COL_CANT;

        String descFull = item.getDescripcion() != null ? item.getDescripcion() : "";
        String[] descLines = wrap(descFull, descW);

        String lineStart = padRight(codigoStr, COL_CODIGO) + padRight(descLines[0], descW);
        writeRaw(out, lineStart);
        out.write(ESC_BOLD_ON);
        writeLine(out, padLeft(cantStr, COL_CANT));
        out.write(ESC_BOLD_OFF);

        for (int i = 1; i < descLines.length; i++) {
            writeLine(out, padRight("", COL_CODIGO) + descLines[i]);
        }
        out.write(LINE_FEED);
    }

    // ================================================================
    // Bloques compartidos
    // ================================================================

    private void writeClienteGrande(ByteArrayOutputStream out, Factura factura) throws IOException {
        writeLine(out, separador());
        out.write(ESC_CENTER);
        out.write(ESC_BOLD_ON);
        out.write(ESC_DOUBLE_WH);
        int doubleCharWidth = charWidth / 2;
        String clienteText = factura.getCliente() != null ? factura.getCliente() : "";
        String[] clienteLines = wrap(clienteText, doubleCharWidth);
        for (String line : clienteLines) {
            writeLine(out, line);
        }
        out.write(ESC_NORMAL);
        out.write(ESC_BOLD_OFF);
        out.write(ESC_LEFT);
        writeLine(out, separador());
    }

    // ================================================================
    // Utilidades de formateo
    // ================================================================

    private String separador() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < charWidth; i++) sb.append('-');
        return sb.toString();
    }

    private String padRight(String text, int length) {
        if (text == null) text = "";
        if (text.length() >= length) return text.substring(0, length);
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < length) sb.append(' ');
        return sb.toString();
    }

    private String padLeft(String text, int length) {
        if (text == null) text = "";
        if (text.length() >= length) return text.substring(0, length);
        StringBuilder sb = new StringBuilder();
        while (sb.length() + text.length() < length) sb.append(' ');
        sb.append(text);
        return sb.toString();
    }

    private String padBetween(String left, String right, int width) {
        int spaces = width - left.length() - right.length();
        if (spaces < 1) spaces = 1;
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < spaces; i++) sb.append(' ');
        sb.append(right);
        return sb.toString();
    }

    private String crop(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength);
    }

    private String[] wrap(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return new String[]{""};
        if (text.length() <= maxWidth) return new String[]{text};
        java.util.List<String> lines = new java.util.ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxWidth, text.length());
            lines.add(text.substring(start, end));
            start = end;
        }
        return lines.toArray(new String[0]);
    }

    private void writeRaw(ByteArrayOutputStream out, String text) throws IOException {
        if (text != null) out.write(text.getBytes("CP437"));
    }

    private void writeLine(ByteArrayOutputStream out, String text) throws IOException {
        if (text != null) out.write(text.getBytes("CP437"));
        out.write(LINE_FEED);
    }
}