package ru.animeoncraft.integration.neoforge;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticMeshTest {
    @Test void allEightEditableModelsHaveBoundedGeometryAndUsableAnimationPivots() throws Exception {
        for (String code : new String[]{"FOX_EARS","ONI_MASK","CYAN_VISOR","RIFT_LANTERN",
                "STAR_CHAIN","SHARD_CROWN","ORBIT_HALO","RIFT_MANTLE"}) {
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources/assets/animeoncraft/cosmetics/"
                    + code.toLowerCase(java.util.Locale.ROOT) + ".json"))) {
                var mesh = CosmeticMesh.read(reader);
                assertTrue(mesh.faces().size() >= 32 && mesh.faces().size() <= 512, code);
                assertTrue(mesh.faces().stream().anyMatch(CosmeticMesh.Face::emissive), code);
                assertTrue(mesh.faces().stream().allMatch(face -> mesh.parts().containsKey(face.part())), code);
                if (code.equals("RIFT_MANTLE")) assertEquals(5, mesh.parts().size());
                if (code.equals("SHARD_CROWN")) assertEquals(6, mesh.parts().size());
            }
        }
    }
    @Test void exportedBlockbenchGeometryHasDetailedSilhouetteAndBoundedTextureCoordinates() throws Exception {
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/assets/animeoncraft/cosmetics/fox_ears.json"))) {
            var mesh = CosmeticMesh.read(reader);
            assertEquals(128, mesh.width());
            assertEquals(128, mesh.height());
            assertEquals(106, mesh.faces().size());
            assertEquals(26, mesh.faces().stream().filter(face -> face.vertices().size() == 3).count());
            assertTrue(mesh.faces().stream().anyMatch(CosmeticMesh.Face::emissive));
            // The editor's reference head must never become part of the wearable geometry.
            assertTrue(mesh.faces().stream().flatMap(face -> face.vertices().stream()).allMatch(vertex -> vertex.y() < -5));
            assertTrue(mesh.faces().stream().flatMap(face -> face.vertices().stream()).anyMatch(vertex -> vertex.y() < -13));
            assertThrows(UnsupportedOperationException.class, () -> mesh.faces().clear());
        }
    }

    @Test void malformedAndUnboundedMeshesAreRejectedBeforeRendering() {
        String valid = """
            {"format":1,"texture_size":[128,128],"faces":[{"vertices":[
            {"position":[0,0,0],"uv":[0,0]},{"position":[1,0,0],"uv":[1,0]},
            {"position":[0,1,0],"uv":[0,1]}],"normal":[0,0,1],"emissive":false}]}
            """;
        assertEquals(1, CosmeticMesh.read(new StringReader(valid)).faces().size());
        for (String invalid : new String[]{valid.replace("[128,128]", "[4096,128]"),
            valid.replace("[1,0]", "[129,0]"), valid.replace("[0,0,1]", "[0,0,0]"),
            valid.replace("[1,0,0]", "[999,0,0]"), valid.replace("\"format\":1", "\"format\":2")}) {
            assertThrows(IllegalArgumentException.class, () -> CosmeticMesh.read(new StringReader(invalid)));
        }
    }
}
