package com.woprinter.service;

import com.woprinter.model.Factura;
import com.woprinter.model.ItemFactura;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class ExcelParserService {

    /**
     * Parsea un archivo Excel exportado por WorldOffice y devuelve un objeto Factura.
     *
     * Solo se leen las columnas A..W, que son comunes a todas las plantillas que
     * maneja el negocio. Cualquier columna desde X en adelante (Iva, totales,
     * descuentos, centros de costo, lote, etc.) se ignora porque varia entre
     * configuraciones y no se utiliza para generar las ordenes de despacho.
     *
     * Columnas leidas:
     *   A=Documento, B=Prefijo, C=DocumentoNumero, D=Fecha, E=Empresa,
     *   F=Vendedor, G=Cliente, I=Direccion, K=Concepto, M=Telefono,
     *   N=Ciudad, O=Forma_Pago, T=Inventario(codigo+desc), U=Bodega,
     *   V=Medida, W=Cantidad
     */
    public Factura parsear(File archivo) throws IOException {
        Factura factura = new Factura();

        FileInputStream fis = new FileInputStream(archivo);
        Workbook workbook = new XSSFWorkbook(fis);

        try {
            Sheet sheet = workbook.getSheetAt(0);

            boolean headerLeido = false;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                if (!headerLeido) {
                    factura.setTipoDocumento(getStringValue(row.getCell(0)));   // A
                    factura.setPrefijo(getStringValue(row.getCell(1)));         // B
                    factura.setNumero(getIntValue(row.getCell(2)));             // C
                    factura.setFecha(getDateValue(row.getCell(3)));             // D
                    factura.setEmpresa(getStringValue(row.getCell(4)));         // E
                    factura.setVendedor(getStringValue(row.getCell(5)));        // F
                    factura.setCliente(getStringValue(row.getCell(6)));         // G
                    factura.setDireccion(getStringValue(row.getCell(8)));       // I
                    factura.setConcepto(getStringValue(row.getCell(10)));       // K
                    factura.setTelefono(getStringValue(row.getCell(12)));       // M
                    factura.setCiudad(getStringValue(row.getCell(13)));         // N
                    factura.setFormaPago(getStringValue(row.getCell(14)));      // O
                    headerLeido = true;
                }

                // Columna T: "A5939 ALUM UNION DE 1-1/2"" -> codigo + descripcion
                String inventario = getStringValue(row.getCell(19)); // T

                // Ignorar filas vacias (Excel a veces reporta filas fantasma)
                if (inventario == null || inventario.trim().isEmpty()) {
                    continue;
                }

                ItemFactura item = new ItemFactura();

                int espacioIdx = inventario.indexOf(' ');
                if (espacioIdx > 0) {
                    item.setCodigo(inventario.substring(0, espacioIdx).trim());
                    item.setDescripcion(inventario.substring(espacioIdx + 1).trim());
                } else {
                    item.setCodigo(inventario.trim());
                    item.setDescripcion("");
                }

                item.setBodega(getStringValue(row.getCell(20)));              // U
                item.setMedida(getStringValue(row.getCell(21)));              // V
                item.setCantidad(getDoubleValue(row.getCell(22)));            // W

                factura.addItem(item);
            }
        } finally {
            workbook.close();
            fis.close();
        }

        return factura;
    }

    // --- Utilidades de lectura de celdas ---

    private String getStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val)) {
                    return String.valueOf((long) val);
                }
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    private int getIntValue(Cell cell) {
        if (cell == null) return 0;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return (int) cell.getNumericCellValue();
            }
            return Integer.parseInt(cell.getStringCellValue().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private double getDoubleValue(Cell cell) {
        if (cell == null) return 0.0;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return cell.getNumericCellValue();
            }
            return Double.parseDouble(cell.getStringCellValue().trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private java.util.Date getDateValue(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue();
            }
        } catch (Exception e) {
            // ignorar
        }
        return null;
    }
}
