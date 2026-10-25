package ru.animeoncraft.integration.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** Content-addressed media, verified before use; entries are read without extracting any paths. */
final class TitleMediaArchive implements AutoCloseable {
    record Manifest(int format_version, String sha256, long size, String url, int width, int height, int fps, int frames) {}
    private final ZipFile zip;
    TitleMediaArchive(Path gameDirectory, Manifest manifest) throws IOException {
        if (manifest == null || manifest.format_version()!=1 || manifest.sha256()==null
                || !manifest.sha256().matches("[a-f0-9]{64}") || manifest.size()<=0 || manifest.size()>134217728
                || manifest.width()!=1920 || manifest.height()!=1080 || manifest.fps()!=24 || manifest.frames()!=238)
            throw new IOException("Invalid title media manifest");
        Path file=gameDirectory.resolve("aoc-assets/title/"+manifest.sha256()+".zip");
        if(Files.size(file)!=manifest.size())throw new IOException("Title media size mismatch");
        try(var input=Files.newInputStream(file)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] chunk=new byte[262144];int count;
            while((count=input.read(chunk))!=-1)digest.update(chunk,0,count);
            if(!HexFormat.of().formatHex(digest.digest()).equals(manifest.sha256()))throw new IOException("Title media checksum mismatch");
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        zip=new ZipFile(file.toFile());
    }
    InputStream open(int frame) throws IOException {
        if(frame<0||frame>=238)throw new IOException("Invalid title frame");
        var entry=zip.getEntry(String.format(java.util.Locale.ROOT,"frame_%04d.jpg",frame));
        if(entry==null||entry.getSize()<1||entry.getSize()>1048576)throw new IOException("Missing or invalid title frame");
        return zip.getInputStream(entry);
    }
    @Override public void close() throws IOException { zip.close(); }
}
