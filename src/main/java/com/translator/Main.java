package com.translator;

import com.formdev.flatlaf.FlatDarkLaf;
import com.translator.core.EpubProcessor;
import com.translator.service.TranslateService; // Импортируем сервис

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

public class Main extends JFrame {

    private JTextArea logArea;
    private JLabel statusLabel;

    public Main() {
        setTitle("EPUB Translator (Google)");
        setSize(700, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Панель Drag and Drop
        JPanel dropPanel = new JPanel(new BorderLayout());
        dropPanel.setBorder(BorderFactory.createDashedBorder(Color.GRAY, 2, 5, 2, true));
        dropPanel.setBackground(new Color(45, 48, 50));

        statusLabel = new JLabel("Перетащи EPUB файл сюда", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        statusLabel.setForeground(new Color(180, 180, 180));
        dropPanel.add(statusLabel, BorderLayout.CENTER);

        // Лог
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(100, 255, 100));

        DefaultCaret caret = (DefaultCaret) logArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(600, 200));

        add(dropPanel, BorderLayout.CENTER);
        add(scrollPane, BorderLayout.SOUTH);

        setupDragAndDrop(dropPanel);
        redirectSystemOut();
    }

    private void setupDragAndDrop(JPanel panel) {
        new DropTarget(panel, new DropTargetListener() {
            public void dragEnter(DropTargetDragEvent dtde) {}
            public void dragOver(DropTargetDragEvent dtde) {}
            public void dropActionChanged(DropTargetDragEvent dtde) {}
            public void dragExit(DropTargetEvent dte) {}

            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> droppedFiles = (List<File>) dtde.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);

                    if (droppedFiles != null && !droppedFiles.isEmpty()) {
                        File file = droppedFiles.get(0);
                        if (file.getName().toLowerCase().endsWith(".epub")) {
                            startTranslation(file);
                        } else {
                            log("Ошибка: Это не EPUB файл!");
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    private void startTranslation(File inputFile) {
        new Thread(() -> {
            try {
                statusLabel.setText("Идет перевод...");
                log("--- Начало работы ---");
                log("Файл: " + inputFile.getName());

                String inputPath = inputFile.getAbsolutePath();
                String outputPath = inputPath.replace(".epub", "_RU.epub");

                // СОЗДАЕМ СЕРВИС И ПРОЦЕССОР
                TranslateService service = new TranslateService();
                EpubProcessor processor = new EpubProcessor();

                // ЗАПУСКАЕМ ПРОЦЕСС
                processor.process(inputPath, outputPath, service);

                log("--- Готово! ---");
                log("Сохранено: " + new File(outputPath).getName());
                statusLabel.setText("Перетащи следующий файл");
                JOptionPane.showMessageDialog(this, "Готово! Файл сохранен рядом с оригиналом.");

            } catch (Exception e) {
                log("Ошибка: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void log(String text) {
        System.out.println(text);
    }

    private void redirectSystemOut() {
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
                updateLog(String.valueOf((char) b));
            }
            @Override
            public void write(byte[] b, int off, int len) {
                updateLog(new String(b, off, len));
            }
        };
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(out, true));
    }

    private void updateLog(String text) {
        SwingUtilities.invokeLater(() -> logArea.append(text));
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception ex) { }
        SwingUtilities.invokeLater(() -> new Main().setVisible(true));
    }
}