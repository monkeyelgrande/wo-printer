package com.woprinter;

import com.formdev.flatlaf.FlatLightLaf;
import com.woprinter.ui.MainWindow;

import javax.swing.*;
import java.awt.*;

public class App {

    public static void main(String[] args) {
        // Look & Feel moderno: FlatLaf Light. Si falla, cae al del SO.
        try {
            // Ajustes globales antes de instalar el LAF
            System.setProperty("flatlaf.useWindowDecorations", "false");
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("Button.arc", 8);
            UIManager.put("Component.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.thumbArc", 8);
            UIManager.put("Table.rowHeight", 26);

            FlatLightLaf.setup();

            // Fuente por defecto un poco más legible
            Font base = new Font(Font.DIALOG, Font.PLAIN, 13);
            UIManager.put("defaultFont", base);
        } catch (Exception e) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                MainWindow window = new MainWindow();
                window.setVisible(true);
            }
        });
    }
}
