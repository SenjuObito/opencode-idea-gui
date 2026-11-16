package com.opencodebuddy.ui;

import com.opencodebuddy.i18n.OpenCodeBuddyBundle;
import com.opencodebuddy.util.ThemeConfigService;

import javax.swing.JPanel;
import javax.swing.Timer;
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
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * Modern Aero-style loading panel implemented in pure Java2D/Swing.
 * Guarantees zero white flash, instant 0ms mount, and seamless dynamic loading animation.
 * Features Option 1: Prismatic Liquid Flow with specular sheen and breathing aura.
 */
public class AeroLoadingPanel extends JPanel {

    private final String statusText;
    private final long startMillis;
    private Timer animationTimer;

    public AeroLoadingPanel() {
        this("");
    }

    public AeroLoadingPanel(String statusText) {
        this.statusText = statusText != null ? statusText.trim() : "";
        this.startMillis = System.currentTimeMillis();
        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(ThemeConfigService.getBackgroundColor());
    }

    @Override
    public void addNotify() {
        super.addNotify();
        startAnimation();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        stopAnimation();
    }

    private void startAnimation() {
        if (animationTimer == null) {
            animationTimer = new Timer(33, e -> repaint()); // ~30 FPS, ultra-low CPU footprint
            animationTimer.start();
        }
    }

    private void stopAnimation() {
        if (animationTimer != null) {
            animationTimer.stop();
            animationTimer = null;
        }
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

        double elapsed = (System.currentTimeMillis() - startMillis) / 1000.0;

        // 1. Subtle radial background gradient with gentle aura breathing
        Color baseBg = ThemeConfigService.getBackgroundColor();
        g2.setColor(baseBg);
        g2.fillRect(0, 0, w, h);

        int centerX = w / 2;
        int centerY = Math.max(120, h / 2 - 20);

        float breath = (float) (0.5 + 0.5 * Math.sin(elapsed * 1.7));
        int glowAlpha = isDark ? (int) (20 + 15 * breath) : (int) (40 + 20 * breath);
        Color ambientGlow = isDark ? new Color(14, 165, 233, glowAlpha) : new Color(186, 230, 253, glowAlpha);
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

        // 2. Draw Aero Badge (76x76)
        int badgeSize = 76;
        int badgeX = centerX - badgeSize / 2;
        int badgeY = centerY - badgeSize / 2 - 24;

        // Ambient halo behind badge with breathing scale
        float haloRadius = 50f + 8f * breath;
        try {
            RadialGradientPaint haloPaint = new RadialGradientPaint(
                    centerX, badgeY + badgeSize / 2, Math.max(10f, haloRadius),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{
                            isDark ? new Color(0, 242, 254, (int) (50 + 30 * breath)) : new Color(14, 165, 233, 40),
                            isDark ? new Color(99, 102, 241, (int) (25 + 20 * breath)) : new Color(99, 102, 241, 15),
                            new Color(0, 0, 0, 0)
                    }
            );
            g2.setPaint(haloPaint);
            g2.fillOval(centerX - (int) haloRadius, (badgeY + badgeSize / 2) - (int) haloRadius, (int) haloRadius * 2, (int) haloRadius * 2);
        } catch (Exception ignored) {
        }

        // Glass badge card
        RoundRectangle2D.Float badgeShape = new RoundRectangle2D.Float(badgeX, badgeY, badgeSize, badgeSize, 22, 22);
        GradientPaint cardGrad = new GradientPaint(
                badgeX, badgeY,
                isDark ? new Color(255, 255, 255, 32) : new Color(255, 255, 255, 240),
                badgeX + badgeSize, badgeY + badgeSize,
                isDark ? new Color(255, 255, 255, 8) : new Color(240, 245, 255, 180)
        );
        g2.setPaint(cardGrad);
        g2.fill(badgeShape);

        // Badge border
        GradientPaint borderGrad = new GradientPaint(
                badgeX, badgeY,
                new Color(56, 189, 248, 160),
                badgeX + badgeSize, badgeY + badgeSize,
                new Color(168, 85, 247, 90)
        );
        g2.setPaint(borderGrad);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(badgeShape);

        // Specular sheen sweep effect across glass badge
        Shape prevClip = g2.getClip();
        g2.clip(badgeShape);
        double sheenPhase = (elapsed % 3.2) / 3.2; // 0.0 -> 1.0 every 3.2s
        float sheenCenter = (float) (badgeX - badgeSize + sheenPhase * badgeSize * 3);
        GradientPaint sheenPaint = new GradientPaint(
                sheenCenter - 25, badgeY,
                new Color(255, 255, 255, 0),
                sheenCenter + 25, badgeY + badgeSize,
                new Color(255, 255, 255, isDark ? 40 : 65)
        );
        g2.setPaint(sheenPaint);
        g2.fill(badgeShape);
        g2.setClip(prevClip);

        // 3. Draw OpenCode Authentic Logo geometry
        int markW = 34;
        int markH = 42;
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
        int voidW = 17;
        int voidH = 26;
        int voidX = markX + (markW - voidW) / 2;
        int voidY = markY + (markH - voidH) / 2;
        RoundRectangle2D.Float innerVoid = new RoundRectangle2D.Float(voidX, voidY, voidW, voidH, 3, 3);
        g2.setColor(isDark ? new Color(8, 9, 14) : new Color(255, 255, 255));
        g2.fill(innerVoid);

        // Multi-Spectrum Holographic Core with smooth dynamic liquid flow
        int coreH = 18;
        int coreY = voidY + (voidH - coreH);
        RoundRectangle2D.Float holoCore = new RoundRectangle2D.Float(voidX, coreY, voidW, coreH, 3, 3);

        Color c1 = interpolateHoloColor(elapsed, 0.0);
        Color c2 = interpolateHoloColor(elapsed, 0.5);
        GradientPaint holoPaint = new GradientPaint(voidX, coreY, c1, voidX + voidW, coreY + coreH, c2);
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

        // 5. Status capsule & pulsing loading dot
        String resolvedStatus = !statusText.isEmpty() ? statusText : OpenCodeBuddyBundle.message("toolwindow.preparingBridge");
        Font statusFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        g2.setFont(statusFont);
        FontMetrics fmStatus = g2.getFontMetrics();
        int textW = fmStatus.stringWidth(resolvedStatus);
        int totalStatusW = 14 + textW;
        int statusStartX = centerX - totalStatusW / 2;
        int statusY = titleY + 24;

        // Loading dot with cyan glow
        int dotAlpha = (int) (140 + 115 * breath);
        g2.setColor(new Color(6, 182, 212, dotAlpha));
        g2.fillOval(statusStartX, statusY - 7, 7, 7);

        g2.setColor(isDark ? new Color(148, 163, 184) : new Color(100, 116, 139));
        g2.drawString(resolvedStatus, statusStartX + 14, statusY);

        g2.dispose();
    }

    /**
     * Interpolates smooth cyclic colors between cyan (#38bdf8), indigo (#818cf8), and purple (#c084fc).
     */
    private static Color interpolateHoloColor(double elapsed, double phaseOffset) {
        double cycle = ((elapsed * 0.25 + phaseOffset) % 1.0 + 1.0) % 1.0;
        Color[] keyColors = new Color[]{
                new Color(56, 189, 248),  // #38bdf8
                new Color(129, 140, 248), // #818cf8
                new Color(192, 132, 252)  // #c084fc
        };
        double scaled = cycle * keyColors.length;
        int idx1 = (int) Math.floor(scaled) % keyColors.length;
        int idx2 = (idx1 + 1) % keyColors.length;
        float factor = (float) (scaled - Math.floor(scaled));

        Color color1 = keyColors[idx1];
        Color color2 = keyColors[idx2];

        int r = (int) (color1.getRed() + (color2.getRed() - color1.getRed()) * factor);
        int g = (int) (color1.getGreen() + (color2.getGreen() - color1.getGreen()) * factor);
        int b = (int) (color1.getBlue() + (color2.getBlue() - color1.getBlue()) * factor);

        return new Color(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
    }
}
