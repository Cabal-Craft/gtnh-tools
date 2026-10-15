package dev.recipedump;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Renders the icons listed in recipedump/icons_wanted.txt through the normal GUI item pipeline (same as inventories),
 * one screen-full batch per frame, each batch drawn on black and on white so alpha can be recovered offline.
 * Output: recipedump/icons/b{N}_{black|white}.png + manifest.txt (cols rows icon).
 */
public class GuiIconDump extends GuiScreen {

    static final int ICON = 32;
    private final GuiScreen parent;
    private final File dir;
    private final List<Object> todo = new ArrayList<>();
    private int batch, cols, rows, failed;
    private static Field isDrawing;

    public GuiIconDump(GuiScreen parent, File dir) {
        this.parent = parent;
        this.dir = dir;
        try {
            for (String line : Files.readAllLines(new File(dir, "icons_wanted.txt").toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) continue;
                todo.add(resolve(line));
            }
        } catch (Exception e) {
            RecipeDump.LOG.error("cannot read icons_wanted.txt", e);
        }
        try {
            isDrawing = Tessellator.class.getDeclaredField("field_78415_z");
            isDrawing.setAccessible(true);
        } catch (Throwable t) {
            try {
                isDrawing = Tessellator.class.getDeclaredField("isDrawing");
                isDrawing.setAccessible(true);
            } catch (Throwable ignored) {}
        }
    }

    private static Object resolve(String k) {
        if (k.startsWith("fluid:")) return FluidRegistry.getFluid(k.substring(6));
        int c = k.lastIndexOf(':');
        Item it = (Item) Item.itemRegistry.getObject(k.substring(0, c));
        if (it == null) return null;
        int meta = Integer.parseInt(k.substring(c + 1));
        if (meta == 32767) meta = 0;
        return new ItemStack(it, 1, meta);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        int w = mc.displayWidth, h = mc.displayHeight;
        cols = w / ICON;
        rows = h / ICON;
        int per = cols * rows;
        if (batch * per >= todo.size()) {
            finish(per);
            return;
        }
        BufferedImage black = pass(0f, w, h, per), white = pass(1f, w, h, per);
        try {
            File out = new File(dir, "icons");
            out.mkdirs();
            ImageIO.write(black, "png", new File(out, "b" + batch + "_black.png"));
            ImageIO.write(white, "png", new File(out, "b" + batch + "_white.png"));
        } catch (Exception e) {
            RecipeDump.LOG.error("write batch failed", e);
        }
        RecipeDump.LOG.info("RecipeDump icons batch {} ({}/{})", batch, Math.min((batch + 1) * per, todo.size()),
            todo.size());
        batch++;
    }

    private void finish(int per) {
        try {
            File out = new File(dir, "icons");
            out.mkdirs();
            Files.write(new File(out, "manifest.txt").toPath(),
                (cols + " " + rows + " " + ICON + " " + todo.size() + " " + batch + "\n").getBytes(StandardCharsets.UTF_8));
            new File(dir, "ICONS_DONE").createNewFile();
        } catch (Exception e) {
            RecipeDump.LOG.error("finish failed", e);
        }
        RecipeDump.LOG.info("RecipeDump icons finished, {} batches, {} render failures", batch, failed);
        mc.displayGuiScreen(parent);
    }

    private BufferedImage pass(float bg, int w, int h, int per) {
        GL11.glClearColor(bg, bg, bg, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glTranslatef(0, 0, -2000);
        for (int i = 0; i < per; i++) {
            int idx = batch * per + i;
            if (idx >= todo.size()) break;
            Object o = todo.get(idx);
            if (o == null) continue;
            int mvDepth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            GL11.glTranslatef((i % cols) * ICON, (i / cols) * ICON, 0);
            GL11.glScalef(ICON / 16f, ICON / 16f, 1f);
            try {
                if (o instanceof ItemStack) drawItem((ItemStack) o);
                else drawFluid((Fluid) o);
            } catch (Throwable t) {
                if (bg == 0f) failed++;
                recover();
            }
            while (GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) > mvDepth) GL11.glPopMatrix();
            GL11.glPopAttrib();
            resetState();
        }
        GL11.glFinish();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        int iw = cols * ICON, ih = rows * ICON;
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < ih; y++) for (int x = 0; x < iw; x++) {
            int p = ((h - 1 - y) * w + x) * 4;
            img.setRGB(x, y, ((buf.get(p) & 255) << 16) | ((buf.get(p + 1) & 255) << 8) | (buf.get(p + 2) & 255));
        }
        return img;
    }

    private void drawItem(ItemStack s) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
        itemRender.zLevel = 0f;
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), s, 0, 0);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
    }

    private void drawFluid(Fluid f) {
        IIcon ic = f.getStillIcon();
        if (ic == null) ic = f.getIcon();
        if (ic == null) return;
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        int c = f.getColor();
        GL11.glColor4f((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, 1f);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(0, 16, 0, ic.getMinU(), ic.getMaxV());
        t.addVertexWithUV(16, 16, 0, ic.getMaxU(), ic.getMaxV());
        t.addVertexWithUV(16, 0, 0, ic.getMaxU(), ic.getMinV());
        t.addVertexWithUV(0, 0, 0, ic.getMinU(), ic.getMinV());
        t.draw();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Undo whatever an item renderer left behind (shader programs, texture units, texture matrix, colour). */
    private void resetState() {
        try {
            org.lwjgl.opengl.GL20.glUseProgram(0);
        } catch (Throwable ignored) {}
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        mc.getTextureManager().bindTexture(TextureMap.locationItemsTexture);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}
    }

    private static void recover() {
        try {
            if (isDrawing != null && isDrawing.getBoolean(Tessellator.instance)) Tessellator.instance.draw();
        } catch (Throwable ignored) {}
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
