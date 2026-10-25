package ru.animeoncraft.integration.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class TitleMediaArchiveTest {
    @TempDir Path folder;
    private TitleMediaArchive.Manifest fixture() throws Exception {
        var source=folder.resolve("fixture.zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(source))) {
            zip.putNextEntry(new ZipEntry("frame_0000.jpg"));zip.write(new byte[]{1,2,3});zip.closeEntry();
        }
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        var target=folder.resolve("aoc-assets/title/"+hash+".zip");Files.createDirectories(target.getParent());Files.move(source,target);
        return new TitleMediaArchive.Manifest(1,hash,Files.size(target),"https://example.test/unused",1920,1080,24,238);
    }
    @Test void readsPinnedArchiveWithoutExtractingEntries() throws Exception {
        var manifest=fixture();try(var archive=new TitleMediaArchive(folder,manifest);var input=archive.open(0)) {
            assertArrayEquals(new byte[]{1,2,3},input.readAllBytes());
            assertThrows(java.io.IOException.class,()->archive.open(1));assertThrows(java.io.IOException.class,()->archive.open(-1));
        }
        assertFalse(Files.exists(folder.resolve("frame_0000.jpg")));
    }
    @Test void refusesTamperedMediaBeforeReadingAnyFrame() throws Exception {
        var manifest=fixture();var file=folder.resolve("aoc-assets/title/"+manifest.sha256()+".zip");
        var bytes=Files.readAllBytes(file);bytes[0]^=1;Files.write(file,bytes);
        assertThrows(java.io.IOException.class,()->new TitleMediaArchive(folder,manifest));
    }
    @Test void refusesTraversalAndMissingArchives() throws Exception {
        assertThrows(java.io.IOException.class,()->new TitleMediaArchive(folder,new TitleMediaArchive.Manifest(1,"../../bad",1,"",1920,1080,24,238)));
        assertThrows(java.io.IOException.class,()->new TitleMediaArchive(folder,new TitleMediaArchive.Manifest(1,"0".repeat(64),1,"",1920,1080,24,238)));
    }
}
