import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.toxicity188.javamesh.MeshBuilder;
import io.github.toxicity188.javamesh.MeshImage;
import io.github.toxicity188.javamesh.MeshPoint;
import io.github.toxicity188.javamesh.MeshShape;
import io.github.toxicity188.javamesh.MeshTriangleName;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Test meshes for RemoteRenderer, built with java-mesh 0.0.1 - the library BetterModel 3.5 itself uses to turn
 * Blockbench mesh elements into item models (BlueprintElement.Group.buildMeshItemModel): a "composite" item
 * model with three transformed "mesh_triangle" sub-models per triangle, each with a constant tint sampled
 * from the texture. The output is what BetterModel would put into its pack for a mesh bone.
 *
 *   java -cp <java-mesh, gson, joml, fastutil, annotations> MeshGen.java <pack root>
 */
public class MeshGen {
    static final MeshTriangleName NAMES = MeshTriangleName.of("bettermodel:mesh_triangle_single", "bettermodel:mesh_triangle_duplex");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        MeshImage stripes = MeshImage.from(stripes());
        write(root, "torus", torus(48, 24, 0.75f, 0.28f), stripes);
        write(root, "sphere", sphere(20, 10, 0.6f), stripes);
        write(root, "flag", flag(16, 10, 1.4f, 0.9f), stripes);
        // six different heavy mesh bones (each 2304 triangles = 6912 sub-models) for the "many new models at once" test
        for (int i = 0; i < 6; i++) {
            write(root, "torus_" + i, torus(48, 24, 0.5f + i * 0.07f, 0.18f + i * 0.025f), stripes);
        }
    }

    static void write(Path root, String name, List<MeshShape> shapes, MeshImage image) throws Exception {
        JsonObject model = MeshBuilder.of(NAMES).image(Map.of("0", image)).load(shapes).toJson();
        JsonObject item = new JsonObject();
        item.add("model", model);
        Path file = root.resolve("assets/dmtest/items/" + name + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().create().toJson(item), StandardCharsets.UTF_8);
        System.out.println(name + ": " + shapes.size() + " polygons, " + model.getAsJsonArray("models").size() + " sub-models");
    }

    static BufferedImage stripes() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                float h = x / 64.0f;
                int r = (int) (127 + 127 * Math.sin(6.283 * h));
                int g = (int) (127 + 127 * Math.sin(6.283 * (h + 0.33)));
                int b = (int) (127 + 127 * Math.sin(6.283 * (h + 0.66)));
                if ((y / 8) % 2 == 0) {
                    r = r * 3 / 4;
                    g = g * 3 / 4;
                    b = b * 3 / 4;
                }
                img.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        return img;
    }

    static MeshShape quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, Vector2f ua, Vector2f ub, Vector2f uc, Vector2f ud) {
        return new MeshShape(List.of(new MeshPoint(a, ua), new MeshPoint(b, ub), new MeshPoint(c, uc), new MeshPoint(d, ud)), "0");
    }

    static List<MeshShape> torus(int n, int m, float big, float small) {
        List<MeshShape> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                out.add(quad(tp(i, j, n, m, big, small), tp(i + 1, j, n, m, big, small), tp(i + 1, j + 1, n, m, big, small), tp(i, j + 1, n, m, big, small),
                    new Vector2f((float) i / n, (float) j / m), new Vector2f((float) (i + 1) / n, (float) j / m),
                    new Vector2f((float) (i + 1) / n, (float) (j + 1) / m), new Vector2f((float) i / n, (float) (j + 1) / m)));
            }
        }
        return out;
    }

    static Vector3f tp(int i, int j, int n, int m, float big, float small) {
        double u = 2 * Math.PI * (i % n) / n, v = 2 * Math.PI * (j % m) / m;
        double r = big + small * Math.cos(v);
        return new Vector3f((float) (r * Math.cos(u)), (float) (small * Math.sin(v)) + 0.5f, (float) (r * Math.sin(u)));
    }

    static List<MeshShape> sphere(int n, int m, float radius) {
        List<MeshShape> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                Vector3f a = sp(i, j, n, m, radius), b = sp(i + 1, j, n, m, radius), c = sp(i + 1, j + 1, n, m, radius), d = sp(i, j + 1, n, m, radius);
                Vector2f ua = new Vector2f((float) i / n, (float) j / m), ub = new Vector2f((float) (i + 1) / n, (float) j / m);
                Vector2f uc = new Vector2f((float) (i + 1) / n, (float) (j + 1) / m), ud = new Vector2f((float) i / n, (float) (j + 1) / m);
                if (j == 0) {
                    out.add(new MeshShape(List.of(new MeshPoint(a, ua), new MeshPoint(c, uc), new MeshPoint(d, ud)), "0"));
                } else if (j == m - 1) {
                    out.add(new MeshShape(List.of(new MeshPoint(a, ua), new MeshPoint(b, ub), new MeshPoint(c, uc)), "0"));
                } else {
                    out.add(quad(a, b, c, d, ua, ub, uc, ud));
                }
            }
        }
        return out;
    }

    static Vector3f sp(int i, int j, int n, int m, float radius) {
        double u = 2 * Math.PI * (i % n) / n, v = Math.PI * j / m;
        return new Vector3f((float) (radius * Math.sin(v) * Math.cos(u)), (float) (radius * Math.cos(v)) + 0.7f, (float) (radius * Math.sin(v) * Math.sin(u)));
    }

    /** An open, wavy sheet: boundary polygons, so java-mesh makes them double sided (mesh_triangle_duplex). */
    static List<MeshShape> flag(int n, int m, float w, float h) {
        List<MeshShape> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                out.add(quad(fp(i, j, n, m, w, h), fp(i + 1, j, n, m, w, h), fp(i + 1, j + 1, n, m, w, h), fp(i, j + 1, n, m, w, h),
                    new Vector2f((float) i / n, (float) j / m), new Vector2f((float) (i + 1) / n, (float) j / m),
                    new Vector2f((float) (i + 1) / n, (float) (j + 1) / m), new Vector2f((float) i / n, (float) (j + 1) / m)));
            }
        }
        return out;
    }

    static Vector3f fp(int i, int j, int n, int m, float w, float h) {
        float x = w * i / n - w / 2, y = h * j / m + 0.2f;
        return new Vector3f(x, y, (float) (0.12 * Math.sin(x * 6.0)));
    }
}
