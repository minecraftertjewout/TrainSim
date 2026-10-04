package test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class App {
    public String getGreeting() {
        return "Last Mile Freight Rail";
    }

    public String getTitle() {
        return "Last Mile | Freight Rail";
    }

    public RailwayGamePanel createGamePanel() {
        return new RailwayGamePanel();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            App app = new App();
            JFrame frame = new JFrame(app.getTitle());
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(true);
            frame.add(app.createGamePanel());
            frame.pack();
            frame.setSize(1440, 900);
            frame.setMinimumSize(new java.awt.Dimension(1100, 740));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
