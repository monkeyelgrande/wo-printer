package com.woprinter.service;

import com.woprinter.model.Factura;
import com.woprinter.model.ItemFactura;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsea facturas exportadas como HTML por WorldOffice y devuelve un objeto
 * Factura, con el mismo contrato que ExcelParserService.
 *
 * El HTML de WorldOffice representa cada fila visual como un &lt;TABLE&gt;
 * independiente cuyas celdas &lt;TD&gt; contienen el texto en &lt;FONT&gt;.
 * Los campos se ubican por sus etiquetas (CLIENTE, NIT, FECHA FACTURA, etc.),
 * no por posicion, para tolerar cambios menores de plantilla.
 *
 * A diferencia del Excel (columnas A..W), el HTML si trae el NIT del cliente
 * y los precios por item (valor unitario, % IVA, valor IVA, total de linea).
 *
 * Multipagina: una factura extensa genera "NNNNN.html" (pagina 1) mas
 * "NNNNNPáginaK.html" (K=2..N). Cada pagina termina con enlaces de navegacion
 * Primero/Anterior/Siguiente/Último; el parser sigue la cadena "Siguiente"
 * desde el archivo base y combina los items de todas las paginas. Cada pagina
 * repite el numero de factura y el "Total líneas o ítems: N", que se usa para
 * validar que no falte ninguna pagina.
 */
public class HtmlFacturaParserService {

    private static final String CHARSET = "windows-1252";

    /** Nombre de pagina secundaria generada por WorldOffice: "<base>PáginaN.html" */
    private static final Pattern PATRON_PAGINA_SECUNDARIA =
            Pattern.compile("(?i)^(.+)p[aá]gina(\\d+)\\.html?$");

    private static final Pattern PATRON_TOTAL_ITEMS =
            Pattern.compile("(?i)total\\s+líneas\\s+o\\s+ítems\\s*:\\s*(\\d+)");

    /**
     * Se lanza cuando el archivo base referencia paginas (enlaces Siguiente/Último)
     * que aun no existen en disco: WorldOffice no ha terminado de escribirlas.
     * El watcher reintenta durante un tiempo antes de declarar error.
     */
    public static class PaginasIncompletasException extends IOException {
        public PaginasIncompletasException(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * Indica si el archivo es una pagina secundaria de una factura HTML
     * multipagina (no debe procesarse por si solo; se procesa via su base).
     */
    public static boolean esPaginaSecundaria(String nombreArchivo) {
        return PATRON_PAGINA_SECUNDARIA.matcher(nombreArchivo).matches();
    }

    /**
     * Paginas secundarias presentes en disco para un archivo base
     * (ej. para 83977.html devuelve 83977Página2.html .. 83977Página8.html).
     * Se usa para mover el conjunto completo a procesados/errores.
     */
    public static List<File> buscarPaginasSecundarias(File archivoBase) {
        List<File> paginas = new ArrayList<File>();
        File dir = archivoBase.getParentFile();
        if (dir == null) return paginas;

        String nombre = archivoBase.getName();
        int punto = nombre.lastIndexOf('.');
        final String stem = (punto > 0 ? nombre.substring(0, punto) : nombre).toLowerCase();

        File[] encontrados = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                Matcher m = PATRON_PAGINA_SECUNDARIA.matcher(name);
                return m.matches() && m.group(1).toLowerCase().equals(stem);
            }
        });
        if (encontrados != null) {
            for (File f : encontrados) paginas.add(f);
        }
        return paginas;
    }

    /**
     * Parsea la factura completa a partir del archivo base, siguiendo la cadena
     * de paginas si la factura es multipagina.
     *
     * @throws PaginasIncompletasException si falta alguna pagina en disco
     * @throws IOException si el HTML no tiene la estructura esperada o el
     *         conteo de items no coincide con el total declarado
     */
    public Factura parsear(File archivoBase) throws IOException {
        Factura factura = new Factura();
        factura.setTipoDocumento("Factura Electrónica De Venta");

        // numero de item -> item (dedupe entre paginas, conserva el orden)
        Map<Integer, ItemFactura> itemsPorNumero = new LinkedHashMap<Integer, ItemFactura>();
        int totalDeclarado = -1;
        boolean encabezadoLeido = false;

        Set<String> visitados = new HashSet<String>();
        File actual = archivoBase;

        while (actual != null) {
            if (!visitados.add(actual.getName().toLowerCase())) break; // evita ciclos

            Document doc = Jsoup.parse(actual, CHARSET);
            List<List<String>> filas = extraerFilas(doc);

            if (!encabezadoLeido) {
                parsearEncabezado(filas, factura);
                encabezadoLeido = true;
            }

            int total = parsearItems(filas, itemsPorNumero);
            if (total >= 0) totalDeclarado = total;

            String siguiente = extraerEnlace(doc, "Siguiente");
            if (siguiente == null || siguiente.isEmpty() || "#".equals(siguiente)) {
                actual = null;
            } else {
                File pagina = new File(archivoBase.getParentFile(), siguiente);
                if (!pagina.exists()) {
                    throw new PaginasIncompletasException(
                            "Falta la pagina '" + siguiente + "' de la factura " + archivoBase.getName());
                }
                actual = pagina;
            }
        }

        if (factura.getNumero() == 0) {
            throw new IOException("No se encontro el numero de factura en " + archivoBase.getName());
        }

        for (ItemFactura item : itemsPorNumero.values()) {
            factura.addItem(item);
        }

        if (totalDeclarado >= 0 && factura.getItems().size() != totalDeclarado) {
            throw new IOException("Factura " + factura.getNumeroCompleto() + " incompleta: el HTML declara "
                    + totalDeclarado + " items pero se leyeron " + factura.getItems().size());
        }
        if (factura.getItems().isEmpty()) {
            throw new IOException("La factura " + factura.getNumeroCompleto()
                    + " no contiene items en " + archivoBase.getName());
        }

        return factura;
    }

    // ------------------------------------------------------------------------
    // Extraccion de filas (cada <TABLE> de WorldOffice es una fila visual)
    // ------------------------------------------------------------------------

    private List<List<String>> extraerFilas(Document doc) {
        List<List<String>> filas = new ArrayList<List<String>>();
        for (Element tr : doc.select("tr")) {
            List<String> celdas = new ArrayList<String>();
            for (Element td : tr.select("td")) {
                String texto = limpiar(td.text());
                if (!texto.isEmpty()) celdas.add(texto); // descarta celdas espaciadoras
            }
            if (!celdas.isEmpty()) filas.add(celdas);
        }
        return filas;
    }

    private static String limpiar(String s) {
        if (s == null) return "";
        return s.replace('\u00a0', ' ').trim();
    }

    // ------------------------------------------------------------------------
    // Encabezado: campos ubicados por etiqueta
    // ------------------------------------------------------------------------

    private void parsearEncabezado(List<List<String>> filas, Factura factura) {
        for (int i = 0; i < filas.size(); i++) {
            List<String> fila = filas.get(i);
            String primera = fila.get(0);

            // Numero: fila con celdas [".", "FVE", "No.", "84026"]
            if (factura.getNumero() == 0) {
                int idxNo = fila.indexOf("No.");
                if (idxNo > 0 && idxNo + 1 < fila.size()) {
                    Integer numero = parseEnteroONull(fila.get(idxNo + 1));
                    if (numero != null) {
                        factura.setPrefijo(fila.get(idxNo - 1));
                        factura.setNumero(numero);
                    }
                }
            }

            // Empresa: la fila anterior a la fila cuyo primer texto es "Nit"
            if (primera.equals("Nit") && factura.getEmpresa() == null && i > 0) {
                factura.setEmpresa(filas.get(i - 1).get(0));

            } else if (primera.equals("CLIENTE") && fila.size() > 1) {
                factura.setCliente(fila.get(1));

            } else if (primera.equals("NIT")) {
                if (fila.size() > 1) factura.setNitCliente(fila.get(1));
                if (fila.size() > 2) factura.setConcepto(fila.get(2));

            } else if (primera.startsWith("DIRECCI") && i + 1 < filas.size()) {
                List<String> valores = filas.get(i + 1);
                if (valores.size() > 0) factura.setDireccion(valores.get(0));
                if (valores.size() > 1) factura.setCiudad(valores.get(1));
                if (valores.size() > 2) factura.setTelefono(valores.get(2));

            } else if (primera.equals("FECHA FACTURA") && i + 1 < filas.size()) {
                List<String> valores = filas.get(i + 1);
                if (valores.size() > 0) factura.setFecha(parseFecha(valores.get(0)));
                if (valores.size() > 2) factura.setVendedor(valores.get(2));
                if (valores.size() > 3) factura.setFormaPago(valores.get(3));

            } else if (primera.equals("Item")) {
                // Empieza la tabla de items: el encabezado ya quedo completo
                break;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Items: filas de 9 celdas despues del encabezado de la tabla
    // ------------------------------------------------------------------------

    /**
     * Agrega los items de la pagina al mapa y devuelve el "Total líneas o ítems"
     * declarado en la pagina (-1 si no aparece).
     */
    private int parsearItems(List<List<String>> filas, Map<Integer, ItemFactura> itemsPorNumero) {
        int totalDeclarado = -1;
        boolean enItems = false;

        for (List<String> fila : filas) {
            if (!enItems) {
                if (fila.get(0).equals("Item") && fila.contains("Código")) {
                    enItems = true;
                }
                continue;
            }

            Matcher total = PATRON_TOTAL_ITEMS.matcher(fila.get(0));
            if (total.find()) {
                totalDeclarado = Integer.parseInt(total.group(1));
                break; // fin de la tabla de items de esta pagina
            }

            Integer numeroItem = parseEnteroONull(fila.get(0));
            if (numeroItem == null || fila.size() < 9) continue;
            if (itemsPorNumero.containsKey(numeroItem)) continue; // repetido entre paginas

            ItemFactura item = new ItemFactura();
            item.setCodigo(fila.get(1));
            item.setDescripcion(fila.get(2));
            item.setCantidad(parseNumero(fila.get(3)));
            item.setMedida(fila.get(4));
            item.setBodega(""); // el HTML no trae bodega; la asignacion real es por stock
            item.setValorUnitario(parseNumero(fila.get(5)));
            item.setPorcentajeIva(parseNumero(fila.get(6)));
            item.setValorIva(parseNumero(fila.get(7)));
            item.setTotalLinea(parseNumero(fila.get(8)));

            itemsPorNumero.put(numeroItem, item);
        }
        return totalDeclarado;
    }

    // ------------------------------------------------------------------------
    // Navegacion multipagina
    // ------------------------------------------------------------------------

    /** href del enlace de navegacion con el texto dado (Siguiente, Último...), o null. */
    private String extraerEnlace(Document doc, String texto) {
        for (Element a : doc.select("a[href]")) {
            if (limpiar(a.text()).equalsIgnoreCase(texto)) {
                return a.attr("href").trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Utilidades numericas (formato colombiano: punto = miles, coma = decimal)
    // ------------------------------------------------------------------------

    private double parseNumero(String s) {
        if (s == null) return 0.0;
        s = s.replace("%", "").replace(" ", "").trim();
        if (s.isEmpty()) return 0.0;
        s = s.replace(".", "").replace(",", ".");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private Integer parseEnteroONull(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Date parseFecha(String s) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
            sdf.setLenient(false);
            return sdf.parse(s.trim());
        } catch (ParseException e) {
            return null;
        }
    }
}
