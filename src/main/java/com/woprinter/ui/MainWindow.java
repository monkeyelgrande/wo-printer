package com.woprinter.ui;

import com.woprinter.config.AppConfig;
import com.woprinter.model.Factura;
import com.woprinter.model.NovedadRegistro;
import com.woprinter.service.DatabaseService;
import com.woprinter.service.FileWatcherService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.List;

public class MainWindow extends JFrame implements FileWatcherService.WatcherListener {

    private final FileWatcherService watcherService;
    private final DatabaseService dbService;

    // Panel Monitor
    private JButton btnIniciar;
    private JButton btnDetener;
    private JLabel lblEstado;
    private JLabel lblCarpeta;
    private JTextArea txtLog;

    // Panel Facturas
    private JTextField txtBuscarFactura;
    private DefaultTableModel facturasTableModel;
    private JTable tablaFacturas;
    private DefaultTableModel productosTableModel;
    private JTable tablaProductos;

    // Panel Órdenes Generadas
    private JTextField txtBuscarOrden;
    private DefaultTableModel ordenesTableModel;
    private JTable tablaOrdenes;
    private DefaultTableModel ordenDetalleTableModel;
    private JTable tablaOrdenDetalle;
    private DefaultTableModel ordenNovedadesTableModel;
    private JTable tablaOrdenNovedades;

    // Panel Novedades
    private JComboBox<String> cmbFiltroTipo;
    private JComboBox<String> cmbFiltroEstado;
    private JTextField txtFiltroCodigo;
    private JTextField txtFiltroFactura;
    private DefaultTableModel novedadesTableModel;
    private JTable tablaNovedades;
    private JTabbedPane mainTabs;
    private int tabIndexNovedades = -1;

    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

    public MainWindow() {
        super("WorldOffice Printer - Monitor de Órdenes");
        this.watcherService = new FileWatcherService();
        this.dbService = DatabaseService.getInstance();

        watcherService.addListener(this);

        initUI();
        verificarConexionDB();
    }

    private void initUI() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int resp = JOptionPane.showConfirmDialog(
                        MainWindow.this,
                        "Desea cerrar la aplicacion?\nSe detendra la vigilancia de archivos.",
                        "Confirmar salida", JOptionPane.YES_NO_OPTION);
                if (resp == JOptionPane.YES_OPTION) {
                    watcherService.detener();
                    dispose();
                    System.exit(0);
                }
            }
        });

        setSize(950, 680);
        setMinimumSize(new Dimension(800, 550));
        setLocationRelativeTo(null);

        mainTabs = new JTabbedPane();
        mainTabs.addTab("Monitor", crearPanelMonitor());
        mainTabs.addTab("Facturas", crearPanelFacturas());
        mainTabs.addTab("Órdenes Generadas", crearPanelOrdenes());
        mainTabs.addTab("Novedades", crearPanelNovedades());
        tabIndexNovedades = mainTabs.getTabCount() - 1;
        actualizarContadorNovedades();

        add(mainTabs);
    }

    // ============================================================
    // TAB: Monitor
    // ============================================================

    private JPanel crearPanelMonitor() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel topPanel = new JPanel(new BorderLayout(10, 5));

        JPanel statusPanel = new JPanel(new GridLayout(2, 1, 2, 2));
        lblEstado = new JLabel("  DETENIDO  ");
        lblEstado.setOpaque(true);
        lblEstado.setBackground(Color.LIGHT_GRAY);
        lblEstado.setForeground(Color.DARK_GRAY);
        lblEstado.setHorizontalAlignment(SwingConstants.CENTER);
        lblEstado.setFont(lblEstado.getFont().deriveFont(Font.BOLD, 14f));

        lblCarpeta = new JLabel("Carpeta: " + AppConfig.getInstance().getWatchFolder());
        lblCarpeta.setFont(lblCarpeta.getFont().deriveFont(Font.PLAIN, 11f));

        statusPanel.add(lblEstado);
        statusPanel.add(lblCarpeta);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnIniciar = new JButton("Iniciar Vigilancia");
        btnDetener = new JButton("Detener");
        btnDetener.setEnabled(false);

        btnIniciar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { iniciarVigilancia(); }
        });
        btnDetener.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { detenerVigilancia(); }
        });

        btnPanel.add(btnIniciar);
        btnPanel.add(btnDetener);

        topPanel.add(statusPanel, BorderLayout.CENTER);
        topPanel.add(btnPanel, BorderLayout.EAST);
        panel.add(topPanel, BorderLayout.NORTH);

        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Log de Eventos"));
        logPanel.add(new JScrollPane(txtLog), BorderLayout.CENTER);

        panel.add(logPanel, BorderLayout.CENTER);

        return panel;
    }

    // ============================================================
    // TAB: Facturas
    // ============================================================

    private JPanel crearPanelFacturas() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        searchPanel.add(new JLabel("Buscar factura:"));
        txtBuscarFactura = new JTextField(20);
        searchPanel.add(txtBuscarFactura);

        JButton btnBuscar = new JButton("Buscar");
        btnBuscar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { buscarFacturas(); }
        });
        searchPanel.add(btnBuscar);
        txtBuscarFactura.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { buscarFacturas(); }
        });

        JButton btnHoy = new JButton("Facturas de Hoy");
        btnHoy.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { cargarFacturasDeHoy(); }
        });
        searchPanel.add(btnHoy);

        panel.add(searchPanel, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.4);

        String[] facHeaders = {"Numero", "Cliente", "Fecha Factura", "Fecha Procesado"};
        facturasTableModel = new DefaultTableModel(facHeaders, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        tablaFacturas = new JTable(facturasTableModel);
        tablaFacturas.getColumnModel().getColumn(0).setPreferredWidth(100);
        tablaFacturas.getColumnModel().getColumn(1).setPreferredWidth(250);
        tablaFacturas.getColumnModel().getColumn(2).setPreferredWidth(100);
        tablaFacturas.getColumnModel().getColumn(3).setPreferredWidth(140);

        tablaFacturas.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = tablaFacturas.getSelectedRow();
                if (row >= 0) {
                    String numero = (String) facturasTableModel.getValueAt(row, 0);
                    cargarProductosFactura(numero);
                }
            }
        });

        JPanel facPanel = new JPanel(new BorderLayout());
        facPanel.setBorder(BorderFactory.createTitledBorder("Facturas Procesadas"));
        facPanel.add(new JScrollPane(tablaFacturas), BorderLayout.CENTER);

        String[] prodHeaders = {"Codigo", "Descripcion", "Cantidad"};
        productosTableModel = new DefaultTableModel(prodHeaders, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        tablaProductos = new JTable(productosTableModel);
        tablaProductos.getColumnModel().getColumn(0).setPreferredWidth(100);
        tablaProductos.getColumnModel().getColumn(1).setPreferredWidth(400);
        tablaProductos.getColumnModel().getColumn(2).setPreferredWidth(80);

        JPanel prodPanel = new JPanel(new BorderLayout());
        prodPanel.setBorder(BorderFactory.createTitledBorder("Productos de la Factura"));
        prodPanel.add(new JScrollPane(tablaProductos), BorderLayout.CENTER);

        splitPane.setTopComponent(facPanel);
        splitPane.setBottomComponent(prodPanel);
        panel.add(splitPane, BorderLayout.CENTER);

        // Cargar facturas del dia al crear el tab
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() { cargarFacturasDeHoy(); }
        });

        return panel;
    }

    // ============================================================
    // Acciones
    // ============================================================

    private void iniciarVigilancia() {
        watcherService.iniciar();
        appendLog("Vigilancia iniciada");
    }

    private void detenerVigilancia() {
        watcherService.detener();
        appendLog("Vigilancia detenida");
    }

    private void verificarConexionDB() {
        boolean ok = dbService.testConnection();
        if (ok) {
            appendLog("Conexion a BD: OK");
        } else {
            appendLog("ERROR: No se pudo conectar a la base de datos");
            appendLog("Verifique config.properties (db.url, db.user, db.password)");
        }
    }

    // --- Acciones tab Facturas ---

    private void cargarFacturasDeHoy() {
        facturasTableModel.setRowCount(0);
        productosTableModel.setRowCount(0);

        List<String[]> resultados = dbService.getFacturasDeHoy();
        for (String[] fila : resultados) {
            facturasTableModel.addRow(fila);
        }
        appendLog("Facturas de hoy cargadas: " + resultados.size());
    }

    private void buscarFacturas() {
        String busqueda = txtBuscarFactura.getText().trim();
        if (busqueda.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Ingrese un numero de factura para buscar",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        facturasTableModel.setRowCount(0);
        productosTableModel.setRowCount(0);

        List<String[]> resultados = dbService.buscarFacturas(busqueda);
        for (String[] fila : resultados) {
            facturasTableModel.addRow(fila);
        }

        if (resultados.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No se encontraron facturas con '" + busqueda + "'",
                    "Sin resultados", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void cargarProductosFactura(String numeroFactura) {
        productosTableModel.setRowCount(0);
        List<String[]> productos = dbService.obtenerProductosFactura(numeroFactura);
        for (String[] fila : productos) {
            productosTableModel.addRow(fila);
        }
    }

    // ============================================================
    // Listener del FileWatcher
    // ============================================================

    @Override
    public void onArchivoDetectado(String archivo) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() { appendLog("Archivo detectado: " + archivo); }
        });
    }

    @Override
    public void onFacturaProcesada(String archivo, Factura factura) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                String nit = factura.getNitCliente();
                appendLog("Factura " + factura.getNumeroCompleto()
                        + " | Cliente: " + factura.getCliente()
                        + (nit != null && !nit.trim().isEmpty() ? " | NIT: " + nit : "")
                        + " | Items: " + factura.getItems().size());
            }
        });
    }

    @Override
    public void onError(String archivo, String mensaje) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() { appendLog("ERROR [" + archivo + "]: " + mensaje); }
        });
    }

    @Override
    public void onInfo(String mensaje) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() { appendLog("[INFO] " + mensaje); }
        });
    }

    @Override
    public void onEstadoCambiado(boolean vigilando) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (vigilando) {
                    lblEstado.setText("  VIGILANDO  ");
                    lblEstado.setBackground(new Color(46, 139, 87));
                    lblEstado.setForeground(Color.WHITE);
                    btnIniciar.setEnabled(false);
                    btnDetener.setEnabled(true);
                } else {
                    lblEstado.setText("  DETENIDO  ");
                    lblEstado.setBackground(Color.LIGHT_GRAY);
                    lblEstado.setForeground(Color.DARK_GRAY);
                    btnIniciar.setEnabled(true);
                    btnDetener.setEnabled(false);
                }
            }
        });
    }

    // ============================================================
    // TAB: Órdenes Generadas
    // ============================================================

    private JPanel crearPanelOrdenes() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Barra de búsqueda
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Número factura:"));
        txtBuscarOrden = new JTextField(15);
        topPanel.add(txtBuscarOrden);
        JButton btnBuscarOrden = new JButton("Buscar");
        btnBuscarOrden.addActionListener(e -> cargarOrdenes(txtBuscarOrden.getText()));
        topPanel.add(btnBuscarOrden);
        JButton btnRefrescarOrden = new JButton("Refrescar");
        btnRefrescarOrden.addActionListener(e -> { txtBuscarOrden.setText(""); cargarOrdenes(""); });
        topPanel.add(btnRefrescarOrden);
        panel.add(topPanel, BorderLayout.NORTH);

        // Tabla órdenes
        String[] headersOrd = {"ID", "Código", "Fecha", "Hora", "Bodega", "Usuario", "Items"};
        ordenesTableModel = new DefaultTableModel(headersOrd, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tablaOrdenes = new JTable(ordenesTableModel);
        tablaOrdenes.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] wOrd = {50, 100, 90, 70, 130, 110, 50};
        for (int i = 0; i < wOrd.length; i++) tablaOrdenes.getColumnModel().getColumn(i).setPreferredWidth(wOrd[i]);

        // Tabla detalle
        String[] headersDet = {"Código", "Descripción", "Cantidad"};
        ordenDetalleTableModel = new DefaultTableModel(headersDet, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tablaOrdenDetalle = new JTable(ordenDetalleTableModel);
        tablaOrdenDetalle.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tablaOrdenDetalle.getColumnModel().getColumn(0).setPreferredWidth(100);
        tablaOrdenDetalle.getColumnModel().getColumn(1).setPreferredWidth(380);
        tablaOrdenDetalle.getColumnModel().getColumn(2).setPreferredWidth(80);

        // Tabla novedades
        String[] headersNov = {"Código", "Descripción", "Cantidad", "Motivo"};
        ordenNovedadesTableModel = new DefaultTableModel(headersNov, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tablaOrdenNovedades = new JTable(ordenNovedadesTableModel);
        tablaOrdenNovedades.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        tablaOrdenNovedades.getColumnModel().getColumn(0).setPreferredWidth(100);
        tablaOrdenNovedades.getColumnModel().getColumn(1).setPreferredWidth(260);
        tablaOrdenNovedades.getColumnModel().getColumn(2).setPreferredWidth(80);
        tablaOrdenNovedades.getColumnModel().getColumn(3).setPreferredWidth(200);

        // Tabs detalle / novedades
        JTabbedPane inner = new JTabbedPane();
        inner.addTab("Detalle de la orden", new JScrollPane(tablaOrdenDetalle));
        inner.addTab("Novedades de la factura", new JScrollPane(tablaOrdenNovedades));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(tablaOrdenes), inner);
        split.setDividerLocation(280);
        split.setResizeWeight(0.5);
        panel.add(split, BorderLayout.CENTER);

        tablaOrdenes.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = tablaOrdenes.getSelectedRow();
            if (row < 0) {
                ordenDetalleTableModel.setRowCount(0);
                ordenNovedadesTableModel.setRowCount(0);
                return;
            }
            int idCab = (Integer) ordenesTableModel.getValueAt(row, 0);
            String codigo = (String) ordenesTableModel.getValueAt(row, 1);
            cargarDetalleOrden(idCab);
            cargarNovedadesDeFactura(codigo);
        });

        cargarOrdenes("");
        return panel;
    }

    private void cargarOrdenes(String filtro) {
        ordenesTableModel.setRowCount(0);
        ordenDetalleTableModel.setRowCount(0);
        ordenNovedadesTableModel.setRowCount(0);
        List<Object[]> lista = dbService.getOrdenesGeneradas(filtro, 300);
        for (Object[] row : lista) ordenesTableModel.addRow(row);
    }

    private void cargarDetalleOrden(int idCabecera) {
        ordenDetalleTableModel.setRowCount(0);
        for (Object[] row : dbService.getDetalleOrden(idCabecera)) ordenDetalleTableModel.addRow(row);
    }

    private void cargarNovedadesDeFactura(String numeroFactura) {
        ordenNovedadesTableModel.setRowCount(0);
        if (numeroFactura == null || numeroFactura.isEmpty()) return;
        for (Object[] row : dbService.getNovedadesFactura(numeroFactura)) ordenNovedadesTableModel.addRow(row);
    }

    // ============================================================
    // TAB: Novedades (operacional)
    // ============================================================

    private JPanel crearPanelNovedades() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Barra de filtros
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        topPanel.add(new JLabel("Tipo:"));
        cmbFiltroTipo = new JComboBox<String>();
        cmbFiltroTipo.addItem("(Todos)");
        for (String t : DatabaseService.TIPOS_NOVEDAD) cmbFiltroTipo.addItem(t);
        topPanel.add(cmbFiltroTipo);

        topPanel.add(new JLabel("Estado:"));
        cmbFiltroEstado = new JComboBox<String>();
        cmbFiltroEstado.addItem("(Todos)");
        for (String e : DatabaseService.ESTADOS_NOVEDAD) cmbFiltroEstado.addItem(e);
        cmbFiltroEstado.setSelectedItem("PENDIENTE");
        topPanel.add(cmbFiltroEstado);

        topPanel.add(new JLabel("Código:"));
        txtFiltroCodigo = new JTextField(8);
        topPanel.add(txtFiltroCodigo);

        topPanel.add(new JLabel("Factura:"));
        txtFiltroFactura = new JTextField(10);
        topPanel.add(txtFiltroFactura);

        JButton btnBuscar = new JButton("Buscar");
        btnBuscar.addActionListener(e -> cargarNovedades());
        topPanel.add(btnBuscar);

        JButton btnLimpiar = new JButton("Limpiar");
        btnLimpiar.addActionListener(e -> {
            cmbFiltroTipo.setSelectedIndex(0);
            cmbFiltroEstado.setSelectedItem("PENDIENTE");
            txtFiltroCodigo.setText("");
            txtFiltroFactura.setText("");
            cargarNovedades();
        });
        topPanel.add(btnLimpiar);

        panel.add(topPanel, BorderLayout.NORTH);

        // Tabla
        String[] headers = {"ID", "Fecha", "Factura", "Tipo", "Código", "Descripción",
                            "Cant.", "Motivo", "Estado", "Prod. asociado", "Revisado por", "Obs. revisión"};
        novedadesTableModel = new DefaultTableModel(headers, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tablaNovedades = new JTable(novedadesTableModel);
        tablaNovedades.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] w = {50, 130, 100, 100, 100, 260, 50, 200, 90, 100, 100, 220};
        for (int i = 0; i < w.length; i++) tablaNovedades.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        panel.add(new JScrollPane(tablaNovedades), BorderLayout.CENTER);

        // Acciones
        JPanel botPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        JButton btnRevisar  = new JButton("Marcar REVISADA");
        JButton btnResolver = new JButton("Marcar RESUELTA (asignar producto)");
        JButton btnIgnorar  = new JButton("Marcar IGNORADA");
        JButton btnReabrir  = new JButton("Reabrir (PENDIENTE)");
        btnRevisar.addActionListener(e -> cambiarEstadoNovedad("REVISADO", false));
        btnResolver.addActionListener(e -> cambiarEstadoNovedad("RESUELTO", true));
        btnIgnorar.addActionListener(e -> cambiarEstadoNovedad("IGNORADO", false));
        btnReabrir.addActionListener(e -> cambiarEstadoNovedad("PENDIENTE", false));
        botPanel.add(btnRevisar);
        botPanel.add(btnResolver);
        botPanel.add(btnIgnorar);
        botPanel.add(btnReabrir);
        panel.add(botPanel, BorderLayout.SOUTH);

        cargarNovedades();
        return panel;
    }

    private void cargarNovedades() {
        novedadesTableModel.setRowCount(0);
        String tipo = itemOrNull(cmbFiltroTipo);
        String estado = itemOrNull(cmbFiltroEstado);
        String codigo = txtFiltroCodigo.getText().trim();
        String factura = txtFiltroFactura.getText().trim();

        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        java.text.DecimalFormat fmtQty = new java.text.DecimalFormat("#,##0.##");

        for (NovedadRegistro n : dbService.getNovedades(tipo, estado, codigo, factura, 500)) {
            novedadesTableModel.addRow(new Object[]{
                    n.getId(),
                    n.getFechaDeteccion() != null ? fmt.format(n.getFechaDeteccion()) : "",
                    n.getNumeroFactura() != null ? n.getNumeroFactura() : "",
                    n.getTipo(),
                    n.getCodigoOriginal() != null ? n.getCodigoOriginal() : "",
                    n.getDescripcion() != null ? n.getDescripcion() : "",
                    fmtQty.format(n.getCantidad()),
                    n.getMotivo() != null ? n.getMotivo() : "",
                    n.getEstadoRevision(),
                    n.getCodigoProductoAsociado() != null ? n.getCodigoProductoAsociado() : "",
                    n.getRevisadoPorNombre() != null ? n.getRevisadoPorNombre() : "",
                    n.getObservacionRevision() != null ? n.getObservacionRevision() : ""
            });
        }
        actualizarContadorNovedades();
    }

    private String itemOrNull(JComboBox<String> c) {
        String v = (String) c.getSelectedItem();
        return (v == null || v.startsWith("(")) ? null : v;
    }

    private void cambiarEstadoNovedad(String nuevoEstado, boolean pedirProducto) {
        int row = tablaNovedades.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Seleccione una novedad", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int idNovedad = (Integer) novedadesTableModel.getValueAt(row, 0);

        Integer idProducto = null;
        if (pedirProducto) {
            String codigo = JOptionPane.showInputDialog(this,
                    "Código del producto ya creado/existente (codigo_barras):",
                    "Asociar producto", JOptionPane.PLAIN_MESSAGE);
            if (codigo == null) return;  // cancelado
            idProducto = dbService.buscarProductoIdPorCodigo(codigo);
            if (idProducto == null) {
                JOptionPane.showMessageDialog(this,
                        "No se encontró ningún producto con ese código.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        String observacion = JOptionPane.showInputDialog(this,
                "Observación (opcional):", "Revisión de novedad", JOptionPane.PLAIN_MESSAGE);
        if (observacion == null) return;

        int idUser = new com.woprinter.service.UsuarioSistemaService().getIdUsuarioSistema();
        if (dbService.actualizarNovedad(idNovedad, nuevoEstado, idProducto, observacion, idUser)) {
            appendLog("Novedad " + idNovedad + " -> " + nuevoEstado);
            cargarNovedades();
        } else {
            JOptionPane.showMessageDialog(this, "No se pudo actualizar", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actualizarContadorNovedades() {
        if (mainTabs == null || tabIndexNovedades < 0) return;
        int pend = dbService.contarNovedadesPendientes();
        String titulo = pend > 0 ? ("Novedades (" + pend + ")") : "Novedades";
        mainTabs.setTitleAt(tabIndexNovedades, titulo);
    }

    // ============================================================
    // Utilidades
    // ============================================================

    private void appendLog(String mensaje) {
        String timestamp = sdf.format(new java.util.Date());
        txtLog.append("[" + timestamp + "] " + mensaje + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }
}
