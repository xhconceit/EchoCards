import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 知声卡（EchoCards）启动图标位图生成器。
 *
 * <p>自适应图标（API 26+）由 res/drawable/ic_launcher_{background,foreground,monochrome}.xml
 * 提供；本项目 minSdk 为 24，所以还需要为 API 24/25 生成各密度的传统位图图标。两者共用同一套
 * 108x108 画布坐标，改图标时请同时更新矢量 XML 与本文件，然后重新生成：
 *
 * <pre>
 *   java tools/icon/IconGenerator.java android/app/src/main/res tools/icon/preview/contact-sheet.png
 * </pre>
 *
 * <p>坐标约定：108x108 画布，启动器只显示中心 72x72（四周各 18 为视差预留）。
 */
public final class IconGenerator {

    private static final double VIEW = 108;
    private static final double INSET = 18;
    private static final double CENTER = VIEW / 2;

    private static final float[] BG_STOPS = { 0f, 0.55f, 1f };
    private static final Color[] BG_COLORS = {
        new Color(0x16A79C), new Color(0x0A7B79), new Color(0x04565A)
    };
    private static final Point2D BG_START = new Point2D.Double(8, 4);
    private static final Point2D BG_END = new Point2D.Double(100, 104);

    private static final Color CARD_COLOR = Color.WHITE;
    private static final Color CARD_LINE_COLOR = new Color(0x0F7F7C);

    private static final double CARD_CX = 45;
    private static final double CARD_CY = 54;
    private static final double CARD_W = 28;
    private static final double CARD_H = 38;
    private static final double CARD_R = 5;
    private static final double CARD_ROTATION = -8;

    private static final double ARC_CX = 58.5;
    private static final double ARC_CY = 54;
    private static final double ARC_SPAN = 50;
    private static final double ARC_STROKE = 4.6;
    private static final double[] ARC_RADII = { 8, 15.5, 23 };

    private static final String[] DENSITY_DIRS = {
        "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"
    };
    private static final int[] DENSITY_SIZES = { 48, 72, 96, 144, 192 };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java tools/icon/IconGenerator.java <res 目录> [预览图输出路径]");
            System.exit(2);
        }
        File resDir = new File(args[0]);
        for (int i = 0; i < DENSITY_SIZES.length; i++) {
            File dir = new File(resDir, DENSITY_DIRS[i]);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建目录: " + dir);
            }
            int size = DENSITY_SIZES[i];
            write(legacyIcon(size, false), new File(dir, "ic_launcher.png"));
            write(legacyIcon(size, true), new File(dir, "ic_launcher_round.png"));
            System.out.println("生成 " + DENSITY_DIRS[i] + " ic_launcher(_round).png @" + size + "px");
        }
        if (args.length > 1) {
            File preview = new File(args[1]);
            if (preview.getParentFile() != null) {
                preview.getParentFile().mkdirs();
            }
            write(contactSheet(), preview);
            System.out.println("生成预览 " + preview);
        }
    }

    /**
     * API 24/25 传统图标：位图自带轮廓。
     * 方形留出约 4% 边距和圆角（lint 的 IconLauncherShape 要求图标有独立轮廓，不能铺满整块画布），
     * 圆形变体则整块圆形；构图按自适应图标可见区域（中心 72x72）等比缩放。
     */
    private static BufferedImage legacyIcon(int size, boolean round) {
        double extent = round ? size : size * 0.92;
        double scale = extent / 72;
        double offset = (size - extent) / 2;

        BufferedImage content = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = content.createGraphics();
        quality(g);
        g.transform(viewTransform(scale, offset));
        g.setPaint(backgroundPaint());
        g.fill(new Rectangle2D.Double(-INSET, -INSET, VIEW + 2 * INSET, VIEW + 2 * INSET));
        drawForeground(g, false);
        g.dispose();

        Shape silhouette = round
            ? new Ellipse2D.Double(offset, offset, extent, extent)
            : new RoundRectangle2D.Double(offset, offset, extent, extent, extent * 0.22, extent * 0.22);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mask = image.createGraphics();
        quality(mask);
        mask.setColor(Color.WHITE);
        mask.fill(silhouette);
        mask.setComposite(AlphaComposite.SrcIn);
        mask.drawImage(content, 0, 0, null);
        mask.dispose();
        return image;
    }

    /** 模拟启动器渲染：背景层放大 1.5 倍，再按圆形或方圆（squircle）遮罩裁切。 */
    private static BufferedImage adaptivePreview(int px, boolean round) {
        BufferedImage image = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        quality(g);
        g.setClip(round ? new Ellipse2D.Double(0, 0, px, px) : squircle(px));

        AffineTransform view = viewTransform(px / 72.0, 0);
        AffineTransform background = new AffineTransform(view);
        background.concatenate(AffineTransform.getTranslateInstance(CENTER, CENTER));
        background.concatenate(AffineTransform.getScaleInstance(1.5, 1.5));
        background.concatenate(AffineTransform.getTranslateInstance(-CENTER, -CENTER));
        Graphics2D bg = (Graphics2D) g.create();
        bg.setTransform(background);
        bg.setPaint(backgroundPaint());
        bg.fill(new Rectangle2D.Double(-INSET, -INSET, VIEW + 2 * INSET, VIEW + 2 * INSET));
        bg.dispose();

        Graphics2D fg = (Graphics2D) g.create();
        fg.setTransform(view);
        drawForeground(fg, false);
        fg.dispose();

        g.dispose();
        return image;
    }

    private static void drawForeground(Graphics2D g, boolean monochrome) {
        AffineTransform rotation =
            AffineTransform.getRotateInstance(Math.toRadians(CARD_ROTATION), CARD_CX, CARD_CY);

        Shape card = new RoundRectangle2D.Double(
            CARD_CX - CARD_W / 2, CARD_CY - CARD_H / 2, CARD_W, CARD_H, CARD_R * 2, CARD_R * 2);
        g.setColor(CARD_COLOR);
        g.fill(rotation.createTransformedShape(card));

        if (!monochrome) {
            g.setColor(CARD_LINE_COLOR);
            g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(rotation.createTransformedShape(new Line2D.Double(38.5, 47.5, 51.5, 47.5)));
            g.draw(rotation.createTransformedShape(new Line2D.Double(38.5, 60.5, 47.5, 60.5)));
        }

        g.setColor(CARD_COLOR);
        g.setStroke(new BasicStroke((float) ARC_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double r : ARC_RADII) {
            g.draw(new Arc2D.Double(ARC_CX - r, ARC_CY - r, 2 * r, 2 * r,
                -ARC_SPAN, 2 * ARC_SPAN, Arc2D.OPEN));
        }
    }

    /** 供人工验收的对照图：遮罩效果 + 各密度实际像素 + 小尺寸放大检查。 */
    private static BufferedImage contactSheet() {
        BufferedImage sheet = new BufferedImage(1120, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        quality(g);
        g.setColor(new Color(0xF2F4F4));
        g.fillRect(0, 0, 1120, 700);
        g.setColor(new Color(0x101314));
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.drawString("知声卡 启动图标预览", 40, 44);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));

        label(g, "圆形遮罩（自适应 256px）", 40, 76);
        g.drawImage(adaptivePreview(256, true), 40, 88, null);
        label(g, "方圆遮罩（自适应 256px）", 340, 76);
        g.drawImage(adaptivePreview(256, false), 340, 88, null);
        label(g, "xxxhdpi 192px", 640, 76);
        g.drawImage(legacyIcon(192, false), 640, 88, null);
        label(g, "xxhdpi 144px", 856, 76);
        g.drawImage(legacyIcon(144, false), 856, 88, null);
        label(g, "圆形 192px", 640, 300);
        g.drawImage(legacyIcon(192, true), 640, 312, null);
        label(g, "xhdpi 96px", 856, 300);
        g.drawImage(legacyIcon(96, false), 856, 312, null);

        label(g, "hdpi 72px", 40, 380);
        g.drawImage(legacyIcon(72, false), 40, 392, null);
        label(g, "mdpi 48px", 160, 380);
        g.drawImage(legacyIcon(48, false), 160, 392, null);
        label(g, "48px 放大 4 倍（检查可辨识度）", 260, 380);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(legacyIcon(48, false), 260, 392, 192, 192, null);
        label(g, "48px 圆形 放大 4 倍", 480, 380);
        g.drawImage(legacyIcon(48, true), 480, 392, 192, 192, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        label(g, "单色层（Android 13+ 主题图标，取 alpha 通道）", 40, 620);
        Graphics2D mono = (Graphics2D) g.create();
        mono.setColor(new Color(0xDCE7E6));
        mono.fillRect(40, 632, 56, 56);
        mono.setColor(new Color(0x2A4A4A));
        mono.fill(new Ellipse2D.Double(40, 632, 56, 56));
        mono.translate(40, 632);
        mono.scale(56.0 / 72, 56.0 / 72);
        mono.translate(-INSET, -INSET);
        drawForeground(mono, true);
        mono.dispose();
        return sheet;
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        g.setColor(new Color(0x6B7376));
        g.drawString(text, x, y);
    }

    private static LinearGradientPaint backgroundPaint() {
        return new LinearGradientPaint(BG_START, BG_END, BG_STOPS, BG_COLORS);
    }

    /** 108 画布坐标 -> 位图坐标：只把可见的中心 72x72 映射到图标区域。 */
    private static AffineTransform viewTransform(double scale, double offset) {
        AffineTransform t = new AffineTransform();
        t.translate(offset, offset);
        t.scale(scale, scale);
        t.translate(-INSET, -INSET);
        return t;
    }

    private static Shape squircle(int px) {
        double arc = px * 0.42;
        return new RoundRectangle2D.Double(0, 0, px, px, arc, arc);
    }

    private static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private static void write(BufferedImage image, File file) throws Exception {
        ImageIO.write(image, "png", file);
    }
}
