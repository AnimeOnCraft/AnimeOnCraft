package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Bounded, client-independent triangle/quad geometry exported from an editable Blockbench project. */
record CosmeticMesh(int width, int height, Map<String, CosmeticMesh.Pivot> parts, List<CosmeticMesh.Face> faces) {
    CosmeticMesh { parts = Map.copyOf(parts); faces = List.copyOf(faces); }
    record Pivot(float x, float y, float z) {}
    record Vertex(float x, float y, float z, float u, float v) {}
    record Face(List<Vertex> vertices, float nx, float ny, float nz, boolean emissive, String part) {
        Face { vertices = List.copyOf(vertices); }
    }

    static CosmeticMesh read(Reader reader) {
        var root = JsonParser.parseReader(reader).getAsJsonObject();
        require(root.get("format").getAsInt() == 1, "Unknown cosmetic mesh format");
        JsonArray size = root.getAsJsonArray("texture_size");
        require(size.size() == 2, "Invalid texture size");
        int width = size.get(0).getAsInt(), height = size.get(1).getAsInt();
        require(width > 0 && height > 0 && width <= 1024 && height <= 1024, "Oversized cosmetic texture");
        var parts = new LinkedHashMap<String, Pivot>();
        if (root.has("parts")) {
            var rawParts = root.getAsJsonObject("parts");
            require(!rawParts.isEmpty() && rawParts.size() <= 16, "Oversized cosmetic rig");
            for (var entry : rawParts.entrySet()) {
                require(entry.getKey().matches("[a-z0-9_]{1,32}"), "Invalid cosmetic part");
                var pivot = entry.getValue().getAsJsonArray();
                require(pivot.size() == 3, "Invalid cosmetic pivot");
                parts.put(entry.getKey(), new Pivot(number(pivot, 0, -64, 64),
                    number(pivot, 1, -64, 64), number(pivot, 2, -64, 64)));
            }
        } else parts.put("static", new Pivot(0, 0, 0));
        JsonArray rawFaces = root.getAsJsonArray("faces");
        require(!rawFaces.isEmpty() && rawFaces.size() <= 512, "Oversized cosmetic geometry");
        var faces = new ArrayList<Face>();
        for (JsonElement rawFace : rawFaces) {
            var face = rawFace.getAsJsonObject();
            var rawVertices = face.getAsJsonArray("vertices");
            require(rawVertices.size() == 3 || rawVertices.size() == 4, "Expected triangles or quads");
            var vertices = new ArrayList<Vertex>();
            for (JsonElement rawVertex : rawVertices) {
                var vertex = rawVertex.getAsJsonObject();
                var p = vertex.getAsJsonArray("position");
                var uv = vertex.getAsJsonArray("uv");
                require(p.size() == 3 && uv.size() == 2, "Invalid cosmetic vertex");
                vertices.add(new Vertex(number(p, 0, -64, 64), number(p, 1, -64, 64),
                    number(p, 2, -64, 64), number(uv, 0, 0, width) / width,
                    number(uv, 1, 0, height) / height));
            }
            var normal = face.getAsJsonArray("normal");
            require(normal.size() == 3, "Invalid cosmetic normal");
            float nx = number(normal, 0, -1, 1), ny = number(normal, 1, -1, 1), nz = number(normal, 2, -1, 1);
            require(Math.abs(nx * nx + ny * ny + nz * nz - 1) < .002f, "Cosmetic normal must be normalized");
            String part = face.has("part") ? face.get("part").getAsString() : "static";
            require(parts.containsKey(part), "Unknown cosmetic part");
            faces.add(new Face(vertices, nx, ny, nz, face.get("emissive").getAsBoolean(), part));
        }
        return new CosmeticMesh(width, height, parts, faces);
    }

    private static float number(JsonArray array, int index, float min, float max) {
        float value = array.get(index).getAsFloat();
        require(Float.isFinite(value) && value >= min && value <= max, "Invalid cosmetic coordinate");
        return value;
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
