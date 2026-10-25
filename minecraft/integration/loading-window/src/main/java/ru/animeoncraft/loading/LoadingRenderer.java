package ru.animeoncraft.loading;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.neoforged.fml.earlydisplay.ElementShader;
import net.neoforged.fml.earlydisplay.QuadHelper;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL32C.*;

/** No Minecraft classes, codecs, model inference or resource manager are needed at this startup stage. */
final class LoadingRenderer implements AutoCloseable {
    private record Glyph(int x,int y,int advance) {}
    private final Map<Integer,Glyph> glyphs=new HashMap<>();
    private final long started=System.nanoTime();
    private int background,font,atlasW,atlasH,ascent;
    private float scale=1,opacity=1;
    private SimpleBufferBuilder buffer;
    private RenderElement.DisplayContext context;
    private final LoadingDiagnostics diagnostics=new LoadingDiagnostics();

    LoadingRenderer() {
        try {
            background=texture("background.png"); font=texture("font.png");
            try(var in=new DataInputStream(resource("font.bin"))) {
                if(in.readInt()!=0x414F4346)throw new IOException("Invalid loading font");
                atlasW=in.readInt(); atlasH=in.readInt(); ascent=in.readInt();
                int count=in.readInt();
                if(count<95||count>512||atlasW>2048||atlasH>2048)throw new IOException("Invalid loading font bounds");
                for(int i=0;i<count;i++)glyphs.put(in.readInt(),new Glyph(in.readInt(),in.readInt(),in.readInt()));
            }
        } catch(IOException|RuntimeException failure) { close();throw new IllegalStateException("Cannot load loading artwork",failure); }
    }
    private static java.io.InputStream resource(String name) throws IOException {
        var in=LoadingRenderer.class.getResourceAsStream("/aoc_loading/"+name);
        if(in==null)throw new IOException("Missing loading resource: "+name);
        return in;
    }
    private static int texture(String name) throws IOException {
        byte[] data;
        try(var in=resource(name)) { data=in.readNBytes(8_388_609); }
        if(data.length>8_388_608)throw new IOException("Loading texture exceeds its bound");
        ByteBuffer encoded=MemoryUtil.memAlloc(data.length);
        try(var stack=MemoryStack.stackPush()) {
            encoded.put(data).flip();var w=stack.mallocInt(1);var h=stack.mallocInt(1);var c=stack.mallocInt(1);
            if(!STBImage.stbi_info_from_memory(encoded,w,h,c)||w.get(0)>2048||h.get(0)>2048)
                throw new IOException("Invalid loading image dimensions");
            var rgba=STBImage.stbi_load_from_memory(encoded,w,h,c,4);
            if(rgba==null)throw new IOException("Cannot decode loading image");
            try {
                int id=glGenTextures();glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,id);
                glPixelStorei(GL_UNPACK_ALIGNMENT,1);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w.get(0),h.get(0),0,GL_RGBA,GL_UNSIGNED_BYTE,rgba);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                glBindTexture(GL_TEXTURE_2D,0);return id;
            } finally { STBImage.stbi_image_free(rgba); }
        } finally { MemoryUtil.memFree(encoded); }
    }
    void render(SimpleBufferBuilder bb,RenderElement.DisplayContext ctx,int frame,int alpha,int mojang) {
        buffer=bb;context=ctx;scale=ctx.scale();opacity=Math.max(0,Math.min(1,alpha/255f));
        glActiveTexture(GL_TEXTURE0);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
        image(background,0,0,ctx.width(),ctx.height(),0,0,1,1,0xffffff,1);
        gradient(0,0,ctx.width(),ctx.height(),0x10081d,.02f,0x080511,.80f);
        float x=(ctx.width()-504)/2f,y=219;
        round(x-1,y+5,506,198,10,0x030108,.38f);
        round(x,y,504,194,9,0x705090,.58f);
        round(x+1,y+1,502,192,8,0x100b1b,.94f);
        text("Подготавливаем игру",x+23,y+17,19,0xf7efff);
        text("Моды, ресурсы и всё необходимое для входа",x+23,y+44,11,0xb5a2cb);
        long seconds=(System.nanoTime()-started)/1_000_000_000;
        right(String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60),x+481,y+22,12,0xc5afd9);
        var meters=StartupNotificationManager.getCurrentProgress();
        var main=meters.stream().filter(m->m.steps()>0).findFirst().orElse(meters.isEmpty()?null:meters.get(0));
        var secondary=meters.stream().filter(m->m!=main).findFirst().orElse(null);
        LoadingProgress first=main==null?new LoadingProgress("",0,0):new LoadingProgress(main.label()==null?main.name():main.label().getText(),main.current(),main.steps());
        LoadingProgress second=secondary==null?null:new LoadingProgress(secondary.label()==null?secondary.name():secondary.label().getText(),secondary.current(),secondary.steps());
        stage(first,x+23,y+76,458,8,14,0xae66ff,frame);
        if(second!=null)stage(second,x+23,y+121,458,5,11,0x7858db,frame);
        else text("Следующий этап появится автоматически",x+23,y+124,10,0x7e6b94);
        var runtime=Runtime.getRuntime();
        String memory=String.format(Locale.ROOT,"Память: %.2f / %.1f ГБ",(runtime.totalMemory()-runtime.freeMemory())/1073741824.0,runtime.maxMemory()/1073741824.0);
        text(memory,x+23,y+169,10,0x9b86b5);
        right("Первый запуск может занять больше времени",x+481,y+170,9,0x8b779f);
        if(mojang!=0) {
            image(mojang,20,ctx.height()-30,40,20,0,0,1,.5f,0xffffff,.60f);
            image(mojang,60,ctx.height()-30,40,20,0,.5f,1,1,0xffffff,.60f);
        }
        right("Minecraft 1.21.1  ·  NeoForge",ctx.width()-20,ctx.height()-24,10,0xb7a2c9);
        diagnostics.capture(ctx,first,second,mojang!=0,alpha);
        glBindTexture(GL_TEXTURE_2D,0);
    }
    private void stage(LoadingProgress progress,float x,float y,float width,float height,int size,int colour,int frame) {
        String counter=progress.counter();float reserved=stringWidth(counter,10)+16;
        text(fit(progress.translated(),size,width-reserved),x,y,size,0xeee4fa);
        right(counter,x+width,y+2,10,0xbda8d4);
        float barY=y+24;
        round(x,barY,width,height,height/2,0x362542,1);
        if(progress.determinate()) {
            float fill=width*progress.fraction();
            if(fill>1)round(x,barY,fill,height,Math.min(height/2,fill/2),colour,1);
        } else {
            float phase=((System.nanoTime()-started)/1_000_000_000f)%2.4f/2.4f;
            float span=width*.21f;
            round(x+(width-span)*(.5f-.5f*(float)Math.cos(phase*Math.PI*2)),barY,span,height,height/2,colour,.88f);
        }
    }
    private int colour(int rgb,float alpha) {
        return Math.round(Math.max(0,Math.min(1,alpha*opacity))*255)<<24|(rgb&255)<<16|(rgb&0xff00)|((rgb>>>16)&255);
    }
    private void begin(ElementShader.RenderType type,int texture) {
        context.elementShader().updateTextureUniform(0);context.elementShader().updateRenderTypeUniform(type);
        glBindTexture(GL_TEXTURE_2D,texture);buffer.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR,SimpleBufferBuilder.Mode.QUADS);
    }
    private void quad(float x,float y,float w,float h,float u0,float v0,float u1,float v1,int color) {
        QuadHelper.loadQuad(buffer,x*scale,(x+w)*scale,y*scale,(y+h)*scale,u0,u1,v0,v1,color);
    }
    private void image(int id,float x,float y,float w,float h,float u0,float v0,float u1,float v1,int rgb,float alpha) {
        begin(ElementShader.RenderType.TEXTURE,id);quad(x,y,w,h,u0,v0,u1,v1,colour(rgb,alpha));buffer.draw();
    }
    private void gradient(float x,float y,float w,float h,int top,float a0,int bottom,float a1) {
        begin(ElementShader.RenderType.BAR,0);
        buffer.pos(x*scale,y*scale).tex(0,0).colour(colour(top,a0)).endVertex();
        buffer.pos((x+w)*scale,y*scale).tex(1,0).colour(colour(top,a0)).endVertex();
        buffer.pos(x*scale,(y+h)*scale).tex(0,1).colour(colour(bottom,a1)).endVertex();
        buffer.pos((x+w)*scale,(y+h)*scale).tex(1,1).colour(colour(bottom,a1)).endVertex();buffer.draw();
    }
    private void round(float x,float y,float w,float h,float radius,int rgb,float alpha) {
        radius=Math.max(0,Math.min(radius,Math.min(w,h)/2));
        begin(ElementShader.RenderType.BAR,0);int color=colour(rgb,alpha);
        for(int row=0;row<(int)Math.ceil(h);row++) {
            float dy=row+.5f;float edge=dy<radius?radius-dy:dy>h-radius?dy-(h-radius):0;
            float inset=edge==0?0:radius-(float)Math.sqrt(Math.max(0,radius*radius-edge*edge));
            quad(x+inset,y+row,w-2*inset,Math.min(1,h-row),0,0,1,1,color);
        }
        buffer.draw();
    }
    private Glyph glyph(int point) { return glyphs.getOrDefault(point,glyphs.get((int)'?')); }
    private float stringWidth(String value,float size) {
        float width=0;for(int c:value.codePoints().toArray())width+=glyph(c).advance()/1024f*size/64f;return width;
    }
    private String fit(String value,float size,float width) {
        if(stringWidth(value,size)<=width)return value;
        int end=value.length();while(end>0&&stringWidth(value.substring(0,end)+"…",size)>width)end--;
        return value.substring(0,end)+"…";
    }
    private void right(String value,float right,float y,float size,int rgb) { text(value,right-stringWidth(value,size),y,size,rgb); }
    private void text(String value,float x,float y,float size,int rgb) {
        float factor=size/64f;float baseline=y+ascent*factor;int color=colour(rgb,1);
        begin(ElementShader.RenderType.TEXTURE,font);
        for(int c:value.codePoints().toArray()) {
            var g=glyph(c);
            quad(x-4*factor,baseline-(4+ascent)*factor,80*factor,96*factor,
                    g.x()/(float)atlasW,g.y()/(float)atlasH,(g.x()+80)/(float)atlasW,(g.y()+96)/(float)atlasH,color);
            x+=g.advance()/1024f*factor;
        }
        buffer.draw();
    }
    @Override public void close() { if(background!=0)glDeleteTextures(background);if(font!=0)glDeleteTextures(font);background=font=0; }
}
