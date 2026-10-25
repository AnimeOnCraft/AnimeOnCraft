package ru.animeoncraft.loading;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL32C.*;

/** Opt-in screenshots from the actual early framebuffer; no production writes or fabricated progress. */
final class LoadingDiagnostics {
    private final Set<String> saved=new HashSet<>();
    private final Path directory;
    LoadingDiagnostics() {
        Path game=FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        directory=Boolean.getBoolean("animeoncraft.loadingProbe")&&game.getFileName().toString().startsWith("title-menu-client-")
                ?game.resolve("loading-screenshots"):null;
    }
    void capture(RenderElement.DisplayContext ctx,LoadingProgress first,LoadingProgress second,boolean handoff,int alpha) {
        if(directory==null||alpha<255)return;
        String phase=handoff?"03-minecraft-loading":first.determinate()?"02-mod-progress":"01-early-startup";
        if(!saved.add(phase))return;
        ByteBuffer pixels=MemoryUtil.memAlloc(ctx.scaledWidth()*ctx.scaledHeight()*4);
        try {
            Files.createDirectories(directory);glPixelStorei(GL_PACK_ALIGNMENT,1);
            glReadPixels(0,0,ctx.scaledWidth(),ctx.scaledHeight(),GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            // The early framebuffer is intentionally flipped by FML at presentation, so its first row is UI-top.
            if(!STBImageWrite.stbi_write_png(directory.resolve(phase+".png").toString(),ctx.scaledWidth(),ctx.scaledHeight(),4,pixels,ctx.scaledWidth()*4))
                throw new java.io.IOException("Could not save loading screenshot");
            String data="label="+first.label().replace('\n',' ').replace('\r',' ')+"\ncurrent="+first.current()+"\ntotal="+first.total()
                    +"\nindeterminate="+!first.determinate()+"\nhandoff="+handoff+"\nsecondary="+(second==null?"":second.label())+"\n";
            Files.writeString(directory.resolve(phase+".properties"),data);
        } catch(java.io.IOException failure) { System.getLogger("AnimeOnCraftLoading").log(System.Logger.Level.WARNING,"Loading screenshot failed",failure); }
        finally { MemoryUtil.memFree(pixels); }
    }
}
