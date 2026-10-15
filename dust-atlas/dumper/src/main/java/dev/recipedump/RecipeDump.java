package dev.recipedump;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.util.IIcon;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.google.gson.stream.JsonWriter;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.objects.ItemData;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;

/**
 * One-shot exporter: once the main menu is up, writes every recipe map / crafting / smelting recipe to
 * .minecraft/recipedump/recipes.json, every referenced item & fluid to items.json, renders their icons into
 * atlas PNGs. Runs once (skipped while recipedump/DONE exists). Client-only; servers do not need it.
 */
@Mod(modid = "recipedump", name = "RecipeDump", version = "1.0", acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "after:gregtech")
public class RecipeDump {

    static final Logger LOG = LogManager.getLogger("RecipeDump");
    static final int ICON = 32, GRID = 64; // 64x64 icons of 32px per 2048px atlas page

    private boolean done, forced, iconsForced, iconsChecked;
    private int menuTicks;
    private final Map<String, ItemStack> items = new LinkedHashMap<>();
    private final Map<String, Fluid> fluids = new LinkedHashMap<>();
    private final IdentityHashMap<Object, String> oreLists = new IdentityHashMap<>();

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        if (!FMLCommonHandler.instance().getSide().isClient()) return;
        FMLCommonHandler.instance().bus().register(this);
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new net.minecraft.command.CommandBase() {

            @Override
            public String getCommandName() {
                return "recipedump";
            }

            @Override
            public String getCommandUsage(net.minecraft.command.ICommandSender sender) {
                return "/recipedump [icons]";
            }

            @Override
            public int getRequiredPermissionLevel() {
                return 0;
            }

            @Override
            public boolean canCommandSenderUseCommand(net.minecraft.command.ICommandSender sender) {
                return true;
            }

            @Override
            public void processCommand(net.minecraft.command.ICommandSender sender, String[] args) {
                if (args.length > 0 && args[0].equals("icons")) {
                    iconsForced = true;
                    return;
                }
                sender.addChatMessage(new net.minecraft.util.ChatComponentText("[RecipeDump] dumping, the game will freeze briefly..."));
                forced = true;
            }
        });
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        File dir = new File(mc.mcDataDir, "recipedump");
        if (!(mc.currentScreen instanceof GuiIconDump) && new File(dir, "icons_wanted.txt").exists()) {
            boolean auto = !iconsChecked && mc.theWorld == null && mc.currentScreen != null
                && !new File(dir, "ICONS_DONE").exists();
            if (mc.theWorld == null && mc.currentScreen != null) iconsChecked = true;
            if (auto || iconsForced) {
                iconsForced = false;
                iconsChecked = true;
                mc.displayGuiScreen(new GuiIconDump(mc.currentScreen, dir));
                return;
            }
        }
        if (!forced) {
            // auto-run once on the first title screen (any class: GTNH uses CustomMainMenu)
            if (done || mc.theWorld != null || mc.currentScreen == null) return;
            if (++menuTicks < 40) return;
            done = true;
            if (new File(dir, "DONE").exists()) return;
        }
        forced = false;
        done = true;
        items.clear();
        fluids.clear();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText("[RecipeDump] working..."));
        dir.mkdirs();
        try {
            long t = System.currentTimeMillis();
            dumpRecipes(new File(dir, "recipes.json"));
            dumpItems(new File(dir, "items.json"));
            new File(dir, "DONE").createNewFile();
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText("[RecipeDump] done -> " + dir.getAbsolutePath()));
            LOG.info("RecipeDump finished in {} ms, {} items, {} fluids", System.currentTimeMillis() - t, items.size(),
                fluids.size());
        } catch (Throwable ex) {
            LOG.error("RecipeDump failed", ex);
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText("[RecipeDump] FAILED: " + ex));
        }
    }

    // ---------------------------------------------------------------- keys

    private String key(ItemStack s) {
        if (s == null || s.getItem() == null) return null;
        Object n = Item.itemRegistry.getNameForObject(s.getItem());
        int meta = s.getItemDamage();
        String k = n + ":" + meta;
        if (!items.containsKey(k)) {
            ItemStack c = s.copy();
            c.stackSize = 1;
            if (meta == OreDictionary.WILDCARD_VALUE) c.setItemDamage(0);
            items.put(k, c);
        }
        return k;
    }

    private String fkey(FluidStack f) {
        if (f == null || f.getFluid() == null) return null;
        String k = f.getFluid().getName();
        fluids.putIfAbsent(k, f.getFluid());
        return k;
    }

    // ---------------------------------------------------------------- recipes

    private void dumpRecipes(File out) throws Exception {
        for (String ore : OreDictionary.getOreNames()) oreLists.put(OreDictionary.getOres(ore), ore);
        try (JsonWriter w = new JsonWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            w.beginObject();
            w.name("maps").beginArray();
            for (Map.Entry<String, RecipeMap<?>> me : RecipeMap.ALL_RECIPE_MAPS.entrySet()) {
                try {
                    dumpMap(w, me.getKey(), me.getValue());
                } catch (Throwable ex) {
                    LOG.error("map {} failed", me.getKey(), ex);
                }
            }
            w.endArray();
            w.name("crafting").beginArray();
            for (Object o : CraftingManager.getInstance().getRecipeList()) {
                try {
                    dumpCrafting(w, (IRecipe) o);
                } catch (Throwable ex) {
                    LOG.debug("crafting recipe failed", ex);
                }
            }
            w.endArray();
            w.name("smelting").beginArray();
            for (Object o : FurnaceRecipes.smelting().getSmeltingList().entrySet()) {
                Map.Entry<?, ?> en = (Map.Entry<?, ?>) o;
                String in = key((ItemStack) en.getKey()), outK = key((ItemStack) en.getValue());
                if (in == null || outK == null) continue;
                w.beginObject().name("i").value(in).name("o").value(outK).name("n")
                    .value(((ItemStack) en.getValue()).stackSize).endObject();
            }
            w.endArray();
            w.endObject();
        }
    }

    private void dumpMap(JsonWriter w, String name, RecipeMap<?> map) throws Exception {
        Collection<GTRecipe> rs = map.getAllRecipes();
        w.beginObject();
        w.name("id").value(name);
        w.name("name").value(StatCollector.translateToLocal(name));
        w.name("recipes").beginArray();
        for (GTRecipe r : rs) {
            if (r == null || !r.mEnabled) continue;
            w.beginObject();
            w.name("in").beginArray();
            if (r.mInputs != null) for (int i = 0; i < r.mInputs.length; i++) {
                ItemStack s = r.mInputs[i];
                String k = key(s);
                if (k == null) continue;
                w.beginArray().value(k).value(s.stackSize);
                int c = r.getInputChance(i);
                if (c != 10000) w.value(c);
                w.endArray();
            }
            w.endArray();
            w.name("out").beginArray();
            if (r.mOutputs != null) for (int i = 0; i < r.mOutputs.length; i++) {
                ItemStack s = r.mOutputs[i];
                String k = key(s);
                if (k == null) continue;
                w.beginArray().value(k).value(s.stackSize);
                int c = r.getOutputChance(i);
                if (c != 10000) w.value(c);
                w.endArray();
            }
            w.endArray();
            fluidArr(w, "fin", r.mFluidInputs);
            fluidArr(w, "fout", r.mFluidOutputs);
            w.name("eu").value(r.mEUt).name("t").value(r.mDuration);
            if (r.mSpecialValue != 0) w.name("sv").value(r.mSpecialValue);
            if (r.mHidden) w.name("hidden").value(true);
            if (r.mFakeRecipe) w.name("fake").value(true);
            w.endObject();
        }
        w.endArray();
        w.endObject();
    }

    private void fluidArr(JsonWriter w, String name, FluidStack[] fs) throws Exception {
        if (fs == null || fs.length == 0) return;
        w.name(name).beginArray();
        for (FluidStack f : fs) {
            String k = fkey(f);
            if (k != null) w.beginArray().value(k).value(f.amount).endArray();
        }
        w.endArray();
    }

    private void dumpCrafting(JsonWriter w, IRecipe r) throws Exception {
        ItemStack out = r.getRecipeOutput();
        String ok = key(out);
        if (ok == null) return;
        List<Object> ins = new ArrayList<>();
        String type;
        if (r instanceof ShapedOreRecipe) {
            type = "shaped";
            for (Object o : ((ShapedOreRecipe) r).getInput()) ins.add(o);
        } else if (r instanceof ShapelessOreRecipe) {
            type = "shapeless";
            ins.addAll(((ShapelessOreRecipe) r).getInput());
        } else if (r instanceof ShapedRecipes) {
            type = "shaped";
            for (ItemStack s : ((ShapedRecipes) r).recipeItems) ins.add(s);
        } else if (r instanceof ShapelessRecipes) {
            type = "shapeless";
            ins.addAll(((ShapelessRecipes) r).recipeItems);
        } else return;
        // aggregate identical inputs into counts
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object o : ins) {
            String k = null;
            if (o instanceof ItemStack) k = key((ItemStack) o);
            else if (o instanceof List && !((List<?>) o).isEmpty()) {
                String ore = oreLists.get(o);
                List<?> l = (List<?>) o;
                String first = key((ItemStack) l.get(0));
                for (Object alt : l) key((ItemStack) alt);
                k = ore != null ? "ore:" + ore + "|" + first : first;
            }
            if (k != null) counts.merge(k, 1, Integer::sum);
        }
        if (counts.isEmpty()) return;
        w.beginObject().name("type").value(type).name("o").value(ok).name("n").value(out.stackSize);
        w.name("in").beginArray();
        for (Map.Entry<String, Integer> c : counts.entrySet()) w.beginArray().value(c.getKey()).value(c.getValue()).endArray();
        w.endArray().endObject();
    }

    // ---------------------------------------------------------------- items

    private void dumpItems(File out) throws Exception {
        Map<String, String> oreByKey = new java.util.HashMap<>();
        try (JsonWriter w = new JsonWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            w.beginObject();
            w.name("items").beginArray();
            int idx = 0;
            for (Map.Entry<String, ItemStack> e : items.entrySet()) {
                ItemStack s = e.getValue();
                w.beginObject().name("k").value(e.getKey()).name("icon").value(idx++);
                String dn;
                try {
                    dn = s.getDisplayName();
                } catch (Throwable t) {
                    dn = e.getKey();
                }
                w.name("name").value(dn);
                try {
                    int[] ids = OreDictionary.getOreIDs(s);
                    if (ids.length > 0) {
                        w.name("ore").beginArray();
                        for (int id : ids) w.value(OreDictionary.getOreName(id));
                        w.endArray();
                    }
                } catch (Throwable ignored) {}
                try {
                    ItemData d = GTOreDictUnificator.getAssociation(s);
                    if (d != null && d.mPrefix != null && d.mMaterial != null && d.mMaterial.mMaterial != null) {
                        w.name("prefix").value(d.mPrefix.toString());
                        w.name("mat").value(d.mMaterial.mMaterial.mName);
                        String f = d.mMaterial.mMaterial.getChemicalFormula();
                        if (f != null && !f.isEmpty()) w.name("formula").value(f);
                    }
                } catch (Throwable ignored) {}
                try {
                    List<String> tip = new ArrayList<>();
                    s.getItem().addInformation(s, Minecraft.getMinecraft().thePlayer, tip, false);
                    if (!tip.isEmpty()) {
                        w.name("tip").beginArray();
                        for (String t : tip) w.value(t);
                        w.endArray();
                    }
                } catch (Throwable ignored) {}
                w.endObject();
            }
            w.endArray();
            w.name("fluids").beginArray();
            for (Map.Entry<String, Fluid> e : fluids.entrySet()) {
                Fluid f = e.getValue();
                String dn;
                try {
                    dn = f.getLocalizedName(new FluidStack(f, 1000));
                } catch (Throwable t) {
                    dn = e.getKey();
                }
                w.beginObject().name("k").value(e.getKey()).name("icon").value(idx++).name("name").value(dn)
                    .name("gas").value(f.isGaseous()).name("temp").value(f.getTemperature()).endObject();
            }
            w.endArray();
            w.endObject();
        }
    }

    // ---------------------------------------------------------------- icons

    private void renderIcons(File dir) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        int size = ICON * GRID;
        Framebuffer fb = new Framebuffer(size, size, true);
        RenderItem ri = new RenderItem();
        List<Object> all = new ArrayList<>(items.values());
        all.addAll(fluids.values());
        int perPage = GRID * GRID;
        ByteBuffer buf = BufferUtils.createByteBuffer(size * size * 4);
        for (int page = 0; page * perPage < all.size(); page++) {
            fb.setFramebufferColor(0, 0, 0, 0);
            fb.framebufferClear();
            fb.bindFramebuffer(true);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, size, size, 0, 1000, 3000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glTranslatef(0, 0, -2000);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper.glBlendFunc(770, 771, 1, 0);
            for (int i = 0; i < perPage && page * perPage + i < all.size(); i++) {
                Object o = all.get(page * perPage + i);
                int x = (i % GRID) * ICON, y = (i / GRID) * ICON;
                GL11.glPushMatrix();
                GL11.glTranslatef(x, y, 0);
                GL11.glScalef(ICON / 16f, ICON / 16f, 1);
                try {
                    if (o instanceof ItemStack) {
                        RenderHelper.enableGUIStandardItemLighting();
                        GL11.glEnable(32826); // rescale normal
                        ri.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), (ItemStack) o, 0, 0);
                        RenderHelper.disableStandardItemLighting();
                    } else {
                        drawFluid((Fluid) o, mc);
                    }
                } catch (Throwable t) {
                    LOG.debug("icon failed for {}", o, t);
                }
                GL11.glPopMatrix();
                if (GL11.glGetError() != 0) { /* swallow GL errors from bad renderers */ }
            }
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            buf.clear();
            GL11.glReadPixels(0, 0, size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                int p = ((size - 1 - y) * size + x) * 4;
                int r = buf.get(p) & 255, g = buf.get(p + 1) & 255, b = buf.get(p + 2) & 255, a = buf.get(p + 3) & 255;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
            ImageIO.write(img, "png", new File(dir, "atlas_" + page + ".png"));
            LOG.info("RecipeDump wrote atlas page {}", page);
        }
        mc.getFramebuffer().bindFramebuffer(true);
        fb.deleteFramebuffer();
    }

    private void drawFluid(Fluid f, Minecraft mc) {
        IIcon ic = f.getStillIcon();
        if (ic == null) ic = f.getIcon();
        if (ic == null) return;
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        int c = f.getColor();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, 1f);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(0, 16, 0, ic.getMinU(), ic.getMaxV());
        t.addVertexWithUV(16, 16, 0, ic.getMaxU(), ic.getMaxV());
        t.addVertexWithUV(16, 0, 0, ic.getMaxU(), ic.getMinV());
        t.addVertexWithUV(0, 0, 0, ic.getMinU(), ic.getMinV());
        t.draw();
        GL11.glColor4f(1, 1, 1, 1);
    }
}
