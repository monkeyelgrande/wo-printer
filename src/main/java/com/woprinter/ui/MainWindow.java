package com.woprinter.ui;

import com.woprinter.config.AppConfig;
import com.woprinter.model.Factura;
import com.woprinter.model.Impresora;
import com.woprinter.model.NovedadRegistro;
import com.woprinter.model.PrintJob;
import com.woprinter.service.DatabaseService;
import com.woprinter.service.FileWatcherService;
import com.woprinter.service.PrinterService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
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
    private final PrinterService printerService;

    // Panel Monitor
    private JButton btnIniciar;
    private JButton btnDetener;
    private JLabel lblEstado;
    private JLabel lblCarpeta;
    private JTextArea txtLog;
    private DefaultTableModel colaTableModel;
    private JTable tablaCola;

    // Panel Impresoras
    private DefaultTableModel impTableModel;
    private JTable tablaImpresoras;
    private JTextField txtNombre;
    private JComboBox<String> cmbImpresorasWindows;
    private JComboBox<BodegaItem> cmbBodega;
    private JCheckBox chkTipoVenta;
    private JCheckBox chkTipoNotificaciones;

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

    /** Item del combo de bodegas; toString define el texto visible. */
    private static class BodegaItem {
        final Integer id;
        final String nombre;
        BodegaItem(Integer id, String nombre) { this.id = id; this.nombre = nombre; }
        @Override public String toString() {
            if (id == null) return "(Sin bodega)";
            return id + " - " + (nombre != null ? nombre : "");
        }
    }

    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

    public MainWindow() {
        super("WorldOffice Printer - Monitor de Impresion");
        this.watcherService = new FileWatcherService();
        this.dbService = DatabaseService.getInstance();
        this.printerService = new PrinterService();

        watcherService.addListener(this);

        initUI();
        cargarImpresoras();
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
        mainTabs.addTab("Impresoras", crearPanelImpresoras());
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

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.5);

        String[] colHeaders = {"Hora", "Factura", "Archivo", "Impresora", "Estado", "Error"};
        colaTableModel = new DefaultTableModel(colHeaders, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        tablaCola = new JTable(colaTableModel);
        tablaCola.getColumnModel().getColumn(0).setPreferredWidth(60);
        tablaCola.getColumnModel().getColumn(1).setPreferredWidth(100);
        tablaCola.getColumnModel().getColumn(2).setPreferredWidth(150);
        tablaCola.getColumnModel().getColumn(3).setPreferredWidth(120);
        tablaCola.getColumnModel().getColumn(4).setPreferredWidth(70);
        tablaCola.getColumnModel().getColumn(5).setPreferredWidth(200);

        JPanel colaPanel = new JPanel(new BorderLayout());
        colaPanel.setBorder(BorderFactory.createTitledBorder("Cola de Impresion"));
        colaPanel.add(new JScrollPane(tablaCola), BorderLayout.CENTER);

        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Log de Eventos"));
        logPanel.add(new JScrollPane(txtLog), BorderLayout.CENTER);

        splitPane.setTopComponent(colaPanel);
        splitPane.setBottomComponent(logPanel);
        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    // ============================================================
    // TAB: Impresoras
    // ============================================================

    private JPanel crearPanelImpresoras() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Tabla: ID, Nombre, Windows, Bodega, Venta, Notif, Activa
        String[] headers = {"ID", "Nombre", "Impresora Windows", "Bodega", "Venta", "Notif.", "Activa"};
        impTableModel = new DefaultTableModel(headers, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex >= 4) return Boolean.class;
                return String.class;
            }
        };
        tablaImpresoras = new JTable(impTableModel);
        tablaImpresoras.getColumnModel().getColumn(0).setPreferredWidth(40);
        tablaImpresoras.getColumnModel().getColumn(1).setPreferredWidth(140);
        tablaImpresoras.getColumnModel().getColumn(2).setPreferredWidth(150);
        tablaImpresoras.getColumnModel().getColumn(3).setPreferredWidth(140);
        tablaImpresoras.getColumnModel().getColumn(4).setPreferredWidth(50);
        tablaImpresoras.getColumnModel().getColumn(5).setPreferredWidth(60);
        tablaImpresoras.getColumnModel().getColumn(6).setPreferredWidth(50);

        panel.add(new JScrollPane(tablaImpresoras), BorderLayout.CENTER);

        // Panel de formulario
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createTitledBorder("Agregar / Editar Impresora"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Fila 1: Nombre + Combo impresoras + Detectar
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        formPanel.add(new JLabel("Nombre:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        txtNombre = new JTextField(15);
        formPanel.add(txtNombre, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        formPanel.add(new JLabel("Impresora Windows:"), gbc);
        gbc.gridx = 3; gbc.weightx = 1;
        cmbImpresorasWindows = new JComboBox<String>();
        formPanel.add(cmbImpresorasWindows, gbc);

        gbc.gridx = 4; gbc.weightx = 0;
        JButton btnDetectar = new JButton("Detectar");
        btnDetectar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { detectarImpresorasWindows(); }
        });
        formPanel.add(btnDetectar, gbc);

        // Fila 2: Bodega + tipos
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        formPanel.add(new JLabel("Bodega:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        cmbBodega = new JComboBox<BodegaItem>();
        cargarComboBodegas();
        formPanel.add(cmbBodega, gbc);

        gbc.gridx = 2; gbc.gridwidth = 3; gbc.weightx = 1;
        JPanel tipoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        chkTipoVenta = new JCheckBox("Venta (con precios)", false);
        chkTipoNotificaciones = new JCheckBox("Notificaciones", false);
        tipoPanel.add(chkTipoVenta);
        tipoPanel.add(chkTipoNotificaciones);
        formPanel.add(tipoPanel, gbc);
        gbc.gridwidth = 1;

        // Fila 3: Botones
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));

        JButton btnAgregar = new JButton("Agregar");
        btnAgregar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { agregarImpresora(); }
        });

        JButton btnEditar = new JButton("Editar");
        btnEditar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { editarImpresora(); }
        });

        JButton btnEliminar = new JButton("Eliminar");
        btnEliminar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { eliminarImpresora(); }
        });

        JButton btnToggle = new JButton("Activar/Desactivar");
        btnToggle.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { toggleImpresora(); }
        });

        JButton btnTest = new JButton("Probar");
        btnTest.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { probarConexion(); }
        });

        JButton btnRefresh = new JButton("Refrescar");
        btnRefresh.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) { cargarImpresoras(); }
        });

        btnPanel.add(btnAgregar);
        btnPanel.add(btnEditar);
        btnPanel.add(btnEliminar);
        btnPanel.add(btnToggle);
        btnPanel.add(btnTest);
        btnPanel.add(btnRefresh);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 5;
        formPanel.add(btnPanel, gbc);

        panel.add(formPanel, BorderLayout.SOUTH);

        // Al seleccionar fila, cargar en formulario
        tablaImpresoras.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = tablaImpresoras.getSelectedRow();
                if (row >= 0) {
                    txtNombre.setText((String) impTableModel.getValueAt(row, 1));
                    String winName = (String) impTableModel.getValueAt(row, 2);
                    boolean found = false;
                    for (int i = 0; i < cmbImpresorasWindows.getItemCount(); i++) {
                        if (cmbImpresorasWindows.getItemAt(i).equals(winName)) {
                            cmbImpresorasWindows.setSelectedIndex(i);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        cmbImpresorasWindows.addItem(winName);
                        cmbImpresorasWindows.setSelectedItem(winName);
                    }

                    // Cargar datos desde la BD (bodega, flags)
                    List<Impresora> todas = dbService.getAllImpresoras();
                    int id = Integer.parseInt((String) impTableModel.getValueAt(row, 0));
                    for (Impresora imp : todas) {
                        if (imp.getId() == id) {
                            chkTipoVenta.setSelected(imp.isTipoVenta());
                            chkTipoNotificaciones.setSelected(imp.isTipoNotificaciones());
                            seleccionarBodegaEnCombo(imp.getIdBodega());
                            break;
                        }
                    }
                }
            }
        });

        detectarImpresorasWindows();
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

        JLabel lblInfo = new JLabel("   Doble clic para reimprimir");
        lblInfo.setFont(lblInfo.getFont().deriveFont(Font.ITALIC, 11f));
        searchPanel.add(lblInfo);

        panel.add(searchPanel, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.4);

        String[] facHeaders = {"Numero", "Cliente", "Fecha Factura", "Fecha Impresion"};
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

        // Doble clic para reimprimir
        tablaFacturas.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    reimprimirFactura();
                }
            }
        });

        JPanel facPanel = new JPanel(new BorderLayout());
        facPanel.setBorder(BorderFactory.createTitledBorder("Facturas Impresas (doble clic para reimprimir)"));
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
        List<Impresora> activas = dbService.getImpresorasActivas();
        if (activas.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No hay impresoras activas configuradas.\nConfigure al menos una impresora antes de iniciar.",
                    "Sin impresoras", JOptionPane.WARNING_MESSAGE);
            return;
        }

        watcherService.iniciar();
        appendLog("Vigilancia iniciada - " + activas.size() + " impresora(s) activa(s)");
        for (Impresora imp : activas) {
            appendLog("  -> " + imp);
        }
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

    private void detectarImpresorasWindows() {
        cmbImpresorasWindows.removeAllItems();
        List<String> nombres = PrinterService.listarImpresorasWindows();
        for (String nombre : nombres) {
            cmbImpresorasWindows.addItem(nombre);
        }
        appendLog("Impresoras Windows detectadas: " + nombres.size());
    }

    private void cargarImpresoras() {
        impTableModel.setRowCount(0);
        List<Impresora> lista = dbService.getAllImpresoras();
        for (Impresora imp : lista) {
            String bodegaTxt = imp.getIdBodega() != null
                    ? (imp.getNombreBodega() != null ? imp.getNombreBodega() : ("Bodega " + imp.getIdBodega()))
                    : "";
            impTableModel.addRow(new Object[]{
                    String.valueOf(imp.getId()),
                    imp.getNombre(),
                    imp.getNombreWindows(),
                    bodegaTxt,
                    imp.isTipoVenta(),
                    imp.isTipoNotificaciones(),
                    imp.isActiva()
            });
        }
    }

    private void cargarComboBodegas() {
        cmbBodega.removeAllItems();
        cmbBodega.addItem(new BodegaItem(null, null));
        for (Object[] row : dbService.getBodegas()) {
            cmbBodega.addItem(new BodegaItem((Integer) row[0], (String) row[1]));
        }
    }

    private void seleccionarBodegaEnCombo(Integer idBodega) {
        for (int i = 0; i < cmbBodega.getItemCount(); i++) {
            BodegaItem it = cmbBodega.getItemAt(i);
            if ((idBodega == null && it.id == null)
                    || (idBodega != null && it.id != null && it.id.equals(idBodega))) {
                cmbBodega.setSelectedIndex(i);
                return;
            }
        }
        cmbBodega.setSelectedIndex(0);
    }

    private Integer getIdBodegaSeleccionado() {
        BodegaItem sel = (BodegaItem) cmbBodega.getSelectedItem();
        return sel != null ? sel.id : null;
    }

    private void agregarImpresora() {
        String nombre = txtNombre.getText().trim();
        String nombreWindows = (String) cmbImpresorasWindows.getSelectedItem();

        if (nombre.isEmpty()) {
            JOptionPane.showMessageDialog(this, "El nombre es obligatorio", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (nombreWindows == null || nombreWindows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Seleccione una impresora de Windows", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Integer idBodega = getIdBodegaSeleccionado();
        boolean tipoVenta = chkTipoVenta.isSelected();
        boolean tipoNotif = chkTipoNotificaciones.isSelected();
        if (idBodega == null && !tipoVenta && !tipoNotif) {
            JOptionPane.showMessageDialog(this,
                    "Asigne al menos una función: bodega, venta o notificaciones",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (dbService.agregarImpresora(nombre, nombreWindows, tipoVenta, tipoNotif, idBodega)) {
            cargarImpresoras();
            limpiarFormImpresora();
            appendLog("Impresora agregada: " + nombre + " [" + nombreWindows + "]");
        }
    }

    private void editarImpresora() {
        int row = tablaImpresoras.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Seleccione una impresora", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int id = Integer.parseInt((String) impTableModel.getValueAt(row, 0));
        String nombre = txtNombre.getText().trim();
        String nombreWindows = (String) cmbImpresorasWindows.getSelectedItem();
        boolean activa = (Boolean) impTableModel.getValueAt(row, 6);

        if (dbService.actualizarImpresora(id, nombre, nombreWindows, activa,
                chkTipoVenta.isSelected(), chkTipoNotificaciones.isSelected(), getIdBodegaSeleccionado())) {
            cargarImpresoras();
            appendLog("Impresora actualizada: " + nombre);
        }
    }

    private void eliminarImpresora() {
        int row = tablaImpresoras.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Seleccione una impresora", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int id = Integer.parseInt((String) impTableModel.getValueAt(row, 0));
        String nombre = (String) impTableModel.getValueAt(row, 1);

        int resp = JOptionPane.showConfirmDialog(this,
                "Eliminar la impresora '" + nombre + "'?",
                "Confirmar", JOptionPane.YES_NO_OPTION);

        if (resp == JOptionPane.YES_OPTION) {
            if (dbService.eliminarImpresora(id)) {
                cargarImpresoras();
                limpiarFormImpresora();
                appendLog("Impresora eliminada: " + nombre);
            }
        }
    }

    private void toggleImpresora() {
        int row = tablaImpresoras.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Seleccione una impresora", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int id = Integer.parseInt((String) impTableModel.getValueAt(row, 0));
        String nombre = (String) impTableModel.getValueAt(row, 1);
        boolean activa = (Boolean) impTableModel.getValueAt(row, 6);

        // Obtener datos actuales de la impresora
        List<Impresora> todas = dbService.getAllImpresoras();
        for (Impresora imp : todas) {
            if (imp.getId() == id) {
                if (dbService.actualizarImpresora(id, imp.getNombre(), imp.getNombreWindows(),
                        !activa, imp.isTipoVenta(), imp.isTipoNotificaciones(), imp.getIdBodega())) {
                    cargarImpresoras();
                    appendLog("Impresora " + nombre + " -> " + (!activa ? "ACTIVADA" : "DESACTIVADA"));
                }
                break;
            }
        }
    }

    private void probarConexion() {
        int row = tablaImpresoras.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Seleccione una impresora", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String nombre = (String) impTableModel.getValueAt(row, 1);
        String nombreWindows = (String) impTableModel.getValueAt(row, 2);
        String tipo = (String) impTableModel.getValueAt(row, 3);

        Impresora imp = new Impresora(0, nombre, nombreWindows, true, false, false, false, null, null);

        if (!printerService.testConexion(imp)) {
            appendLog("ERROR: Impresora NO encontrada en Windows: " + nombreWindows);
            JOptionPane.showMessageDialog(this,
                    "No se encontro la impresora '" + nombreWindows + "' en Windows.\n"
                    + "Verifique que este instalada y el nombre sea exacto.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        appendLog("Enviando prueba a: " + nombreWindows);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] testTicket = generarTicketPrueba(nombre, nombreWindows, tipo);
                    printerService.imprimir(imp, testTicket);

                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("Prueba enviada OK a: " + nombreWindows);
                            JOptionPane.showMessageDialog(MainWindow.this,
                                    "Prueba enviada a '" + nombreWindows + "'",
                                    "Test OK", JOptionPane.INFORMATION_MESSAGE);
                        }
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("ERROR prueba " + nombreWindows + ": " + e.getMessage());
                            JOptionPane.showMessageDialog(MainWindow.this,
                                    "Error enviando prueba: " + e.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    });
                }
            }
        }).start();
    }

    private byte[] generarTicketPrueba(String nombre, String nombreWindows, String tipo) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] INIT = {0x1B, 0x40};
        byte[] BOLD_ON = {0x1B, 0x45, 0x01};
        byte[] BOLD_OFF = {0x1B, 0x45, 0x00};
        byte[] CENTER = {0x1B, 0x61, 0x01};
        byte[] LEFT = {0x1B, 0x61, 0x00};
        byte[] DOUBLE = {0x1B, 0x21, 0x30};
        byte[] NORMAL = {0x1B, 0x21, 0x00};
        byte[] FEED = {0x1B, 0x64, 0x03};
        byte[] CUT = {0x1D, 0x56, 0x01};
        byte[] LF = {0x0A};

        String sep = "------------------------------------------------";
        java.text.SimpleDateFormat sdf2 = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

        out.write(INIT);
        out.write(CENTER);
        out.write(BOLD_ON);
        out.write(DOUBLE);
        out.write("PRUEBA".getBytes("CP437"));
        out.write(LF);
        out.write(NORMAL);
        out.write(BOLD_OFF);
        out.write(LF);

        out.write(LEFT);
        out.write(sep.getBytes("CP437"));
        out.write(LF);
        out.write(("Impresora: " + nombre).getBytes("CP437"));
        out.write(LF);
        out.write(("Windows:   " + nombreWindows).getBytes("CP437"));
        out.write(LF);
        out.write(("Tipo:      " + tipo).getBytes("CP437"));
        out.write(LF);
        out.write(("Fecha:     " + sdf2.format(new java.util.Date())).getBytes("CP437"));
        out.write(LF);
        out.write(sep.getBytes("CP437"));
        out.write(LF);

        out.write(CENTER);
        out.write("Impresora funcionando OK".getBytes("CP437"));
        out.write(LF);
        out.write(LF);

        out.write(FEED);
        out.write(CUT);

        return out.toByteArray();
    }

    private void limpiarFormImpresora() {
        txtNombre.setText("");
        if (cmbImpresorasWindows.getItemCount() > 0) {
            cmbImpresorasWindows.setSelectedIndex(0);
        }
        if (cmbBodega.getItemCount() > 0) {
            cmbBodega.setSelectedIndex(0);
        }
        chkTipoVenta.setSelected(false);
        chkTipoNotificaciones.setSelected(false);
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

    private void reimprimirFactura() {
        int row = tablaFacturas.getSelectedRow();
        if (row < 0) return;

        String numeroFactura = (String) facturasTableModel.getValueAt(row, 0);
        String cliente = (String) facturasTableModel.getValueAt(row, 1);

        int resp = JOptionPane.showConfirmDialog(this,
                "Reimprimir factura " + numeroFactura + "?\nCliente: " + cliente,
                "Confirmar reimpresion", JOptionPane.YES_NO_OPTION);

        if (resp != JOptionPane.YES_OPTION) return;

        // Reconstruir factura desde la BD
        com.woprinter.model.Factura factura = dbService.reconstruirFactura(numeroFactura);
        if (factura == null || factura.getItems().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo reconstruir la factura " + numeroFactura,
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Ejecutar reimpresion en hilo aparte para no bloquear UI
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.woprinter.service.TicketGeneratorService ticketGen = new com.woprinter.service.TicketGeneratorService();
                    com.woprinter.service.PrinterService printerSvc = new com.woprinter.service.PrinterService();

                    byte[] tirillaBodega = ticketGen.generarTirillaBodega(factura);
                    com.woprinter.model.ConfiguracionEmpresa configEmpresa = dbService.getConfiguracionEmpresa();
                    byte[] tirillaVenta = ticketGen.generarTirillaVenta(factura, configEmpresa);

                    List<Impresora> impresoras = dbService.getImpresorasActivas();
                    int enviados = 0;
                    int errores = 0;

                    for (Impresora imp : impresoras) {
                        if (imp.isTipoBodega()) {
                            try {
                                printerSvc.imprimir(imp, tirillaBodega);
                                enviados++;
                            } catch (Exception e) {
                                errores++;
                                System.err.println("[REPRINT] Error bodega " + imp + ": " + e.getMessage());
                            }
                        }
                        if (imp.isTipoVenta()) {
                            try {
                                printerSvc.imprimir(imp, tirillaVenta);
                                enviados++;
                            } catch (Exception e) {
                                errores++;
                                System.err.println("[REPRINT] Error venta " + imp + ": " + e.getMessage());
                            }
                        }
                    }

                    final int fEnviados = enviados;
                    final int fErrores = errores;
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("Reimpresion " + numeroFactura + ": " + fEnviados + " enviados"
                                    + (fErrores > 0 ? ", " + fErrores + " errores" : ""));
                            JOptionPane.showMessageDialog(MainWindow.this,
                                    "Reimpresion de " + numeroFactura + " completada\n"
                                    + fEnviados + " tirillas enviadas"
                                    + (fErrores > 0 ? "\n" + fErrores + " con errores" : ""),
                                    "Reimpresion", fErrores > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
                        }
                    });

                } catch (Exception e) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("ERROR reimprimiendo " + numeroFactura + ": " + e.getMessage());
                            JOptionPane.showMessageDialog(MainWindow.this,
                                    "Error reimprimiendo: " + e.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    });
                }
            }
        }, "Reprint-Thread").start();
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
                appendLog("Factura " + factura.getNumeroCompleto()
                        + " | Cliente: " + factura.getCliente()
                        + " | Items: " + factura.getItems().size());
            }
        });
    }

    @Override
    public void onImpresionEnviada(PrintJob job) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                colaTableModel.insertRow(0, new Object[]{
                        sdf.format(job.getFechaProcesado() != null ? job.getFechaProcesado() : job.getFechaCreacion()),
                        job.getNumeroFactura(),
                        job.getArchivoOrigen(),
                        job.getImpresora().getNombre(),
                        job.getEstado().name(),
                        job.getMensajeError() != null ? job.getMensajeError() : ""
                });

                String icon = job.getEstado() == PrintJob.Estado.IMPRESO ? "OK" : "ERROR";
                appendLog("  [" + icon + "] " + job.getImpresora().getNombre()
                        + (job.getMensajeError() != null ? " - " + job.getMensajeError() : ""));

                while (colaTableModel.getRowCount() > 200) {
                    colaTableModel.removeRow(colaTableModel.getRowCount() - 1);
                }
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