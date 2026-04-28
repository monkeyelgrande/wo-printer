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
     * Detecta automaticamente el formato:
     * 
     * FORMATO CON IVA (34 columnas):
     *   X(24)=Iva, Y(25)=Monto_Monetario_U, Z(26)=ImpoConsumo,
     *   AA(27)=PorcImpoConsumo, AB(28)=Total
     * 
     * FORMATO SIN IVA (30 columnas):
     *   X(24)=Monto_Monetario_U, Y(25)=ImpoConsumo,
     *   Z(26)=PorcImpoConsumo, AA(27)=Total
     *   (No tiene columna Iva)
     * 
     * Columnas comunes (A-W):
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

            // Detectar formato por el header de la columna X (indice 23)
            Row headerRow = sheet.getRow(0);
            boolean tieneIva = false;
            if (headerRow != null) {
                String headerX = getStringValue(headerRow.getCell(23));
                tieneIva = "Iva".equalsIgnoreCase(headerX.trim());
            }

            System.out.println("[PARSER] Formato detectado: " + (tieneIva ? "CON IVA (34 cols)" : "SIN IVA (30 cols)"));

            // Indices de columnas segun formato
            int colIva;              // Solo existe en formato CON IVA
            int colPrecioUnitario;   // Monto_Monetario_U
            int colTotal;            // Total

            if (tieneIva) {
                // Formato CON IVA: X=Iva, Y=PrecioU, AB=Total
                colIva = 23;             // X
                colPrecioUnitario = 24;  // Y
                colTotal = 27;           // AB
            } else {
                // Formato SIN IVA: X=PrecioU, AA=Total (no hay columna Iva)
                colIva = -1;             // No existe
                colPrecioUnitario = 23;  // X
                colTotal = 26;           // AA
            }

            boolean headerLeido = false;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // Leer encabezado de factura solo de la primera fila de datos
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

                // IVA: solo existe en formato CON IVA
                if (colIva >= 0) {
                    item.setIva(getDoubleValue(row.getCell(colIva)));
                } else {
                    item.setIva(0.0);
                }

                // Precio unitario (sin IVA)
                item.setPrecioUnitario(getDoubleValue(row.getCell(colPrecioUnitario)));

                // Total de la linea (con IVA incluido si aplica)
                item.setTotalLinea(getDoubleValue(row.getCell(colTotal)));

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