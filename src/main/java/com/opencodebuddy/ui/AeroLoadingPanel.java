package com.opencodebuddy.ui;

import com.opencodebuddy.i18n.OpenCodeBuddyBundle;
import com.opencodebuddy.util.ThemeConfigService;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * Modern Aero-style loading panel implemented in pure Java2D/Swing.
 * Guarantees zero white flash, instant 0ms mount, and seamless dark-mode consistency.
 */
public class AeroLoadingPanel extends JPanel {

    private final String statusText;

    public AeroLoadingPanel() {
        this("");
    }

    public AeroLoadingPanel(String statusText) {
        this.statusText = statusText != null ? statusText.trim() : "";
        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(ThemeConfigService.getBackgroundColor());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        boolean isDark = true;
        try {
            isDark = ThemeConfigService.getIdeThemeConfig().get("isDark").getAsBoolean();
        } catch (Exception ignored) {
        }

        // 1. Subtle radial background gradient
        Color baseBg = ThemeConfigService.getBackgroundColor();
        g2.setColor(baseBg);
        g2.fillRect(0, 0, w, h);

        int centerX = w / 2;
        int centerY = Math.max(120, h / 2 - 20);

        Color ambientGlow = isDark ? new Color(14, 165, 233, 24) : new Color(186, 230, 253, 55);
        int glowRadius = Math.max(160, Math.min(w, h));
        try {
            RadialGradientPaint bgPaint = new RadialGradientPaint(
                    centerX, centerY, glowRadius,
                    new float[]{0f, 0.45f, 1f},
                    new Color[]{
                            ambientGlow,
                            new Color(ambientGlow.getRed(), ambientGlow.getGreen(), ambientGlow.getBlue(), 0),
                            new Color(0, 0, 0, 0)
                    }
            );
            g2.setPaint(bgPaint);
            g2.fillRect(0, 0, w, h);
        } catch (Exception ignored) {
        }

        // 2. Draw Aero Badge (72x72)
        int badgeSize = 72;
        int badgeX = centerX - badgeSize / 2;
        int badgeY = centerY - badgeSize / 2 - 24;

        // Ambient halo behind badge
        try {
            RadialGradientPaint haloPaint = new RadialGradientPaint(
                    centerX, badgeY + badgeSize / 2, 54f,
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{
                            isDark ? new Color(0, 242, 254, 70) : new Color(14, 165, 233, 50),
                            isDark ? new Color(99, 102, 241, 35) : new Color(99, 102, 241, 20),
                            new Color(0, 0, 0, 0)
                    }
            );
            g2.setPaint(haloPaint);
            g2.fillOval(centerX - 54, badgeY + badgeSize / 2 - 54, 108, 108);
        } catch (Exception ignored) {
        }

        // Glass badge card
        RoundRectangle2D.Float badgeShape = new RoundRectangle2D.Float(badgeX, badgeY, badgeSize, badgeSize, 20, 20);
        GradientPaint cardGrad = new GradientPaint(
                badgeX, badgeY,
                isDark ? new Color(255, 255, 255, 35) : new Color(255, 255, 255, 240),
                badgeX + badgeSize, badgeY + badgeSize,
                isDark ? new Color(255, 255, 255, 10) : new Color(240, 245, 255, 180)
        );
        g2.setPaint(cardGrad);
        g2.fill(badgeShape);

        // Badge border
        GradientPaint borderGrad = new GradientPaint(
                badgeX, badgeY,
                new Color(56, 189, 248, 180),
                badgeX + badgeSize, badgeY + badgeSize,
                new Color(168, 85, 247, 100)
        );
        g2.setPaint(borderGrad);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(badgeShape);

        // 3. Draw OpenCode Authentic Logo geometry (Holographic Core Concept 3)
        int markW = 32;
        int markH = 40;
        int markX = centerX - markW / 2;
        int markY = badgeY + (badgeSize - markH) / 2;

        // Outer Titanium Frame
        RoundRectangle2D.Float outerFrame = new RoundRectangle2D.Float(markX, markY, markW, markH, 6, 6);
        g2.setColor(isDark ? new Color(248, 250, 252) : new Color(30, 41, 59));
        g2.fill(outerFrame);

        // Frame Luminescent Edge
        GradientPaint edgeGrad = new GradientPaint(
                markX, markY, new Color(0, 242, 254, 180),
                markX + markW, markY + markH, new Color(99, 102, 241, 80)
        );
        g2.setPaint(edgeGrad);
        g2.setStroke(new BasicStroke(1.0f));
        g2.draw(outerFrame);

        // Inner Void Cutout
        int voidW = 16;
        int voidH = 24;
        int voidX = markX + (markW - voidW) / 2;
        int voidY = markY + (markH - voidH) / 2;
        RoundRectangle2D.Float innerVoid = new RoundRectangle2D.Float(voidX, voidY, voidW, voidH, 3, 3);
        g2.setColor(isDark ? new Color(8, 9, 14) : new Color(255, 255, 255));
        g2.fill(innerVoid);

        // Multi-Spectrum Holographic Core
        int coreH = 16;
        int coreY = voidY + (voidH - coreH);
        RoundRectangle2D.Float holoCore = new RoundRectangle2D.Float(voidX, coreY, voidW, coreH, 3, 3);
        GradientPaint holoPaint = new GradientPaint(
                voidX, coreY, new Color(56, 189, 248),
                voidX + voidW, coreY + coreH, new Color(192, 132, 252)
        );
        g2.setPaint(holoPaint);
        g2.fill(holoCore);

        // 4. Title "OpenCode Buddy"
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 15);
        g2.setFont(titleFont);
        g2.setColor(isDark ? new Color(245, 247, 250) : new Color(15, 23, 42));
        FontMetrics fmTitle = g2.getFontMetrics();
        String titleStr = "OpenCode Buddy";
        int titleX = centerX - fmTitle.stringWidth(titleStr) / 2;
        int titleY = badgeY + badgeSize + 30;
        g2.drawString(titleStr, titleX, titleY);

        // 5. Status text (only rendered if explicitly provided, default clean & silent)
        if (!statusText.isEmpty()) {
            Font statusFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            g2.setFont(statusFont);
            FontMetrics fmStatus = g2.getFontMetrics();
            int textW = fmStatus.stringWidth(statusText);
            int totalStatusW = 12 + textW;
            int statusStartX = centerX - totalStatusW / 2;
            int statusY = titleY + 22;

            g2.setColor(new Color(6, 182, 212));
            g2.fillOval(statusStartX, statusY - 7, 6, 6);

            g2.setColor(isDark ? new Color(148, 163, 184) : new Color(100, 116, 139));
            g2.drawString(statusText, statusStartX + 12, statusY);
        }

        g2.dispose();
    }
}
