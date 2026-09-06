import { build } from "esbuild";
import { cp, mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const targets = ["chrome", "edge", "brave", "firefox", "safari"];

await Promise.all(targets.map(async (target) => {
  const out = path.join(root, "dist", target);
  await mkdir(out, { recursive: true });
  await build({
    entryPoints: {
      background: path.join(root, "src/background.ts"),
      content: path.join(root, "src/content.ts"),
      popup: path.join(root, "src/popup.ts")
    },
    outdir: out,
    bundle: true,
    format: "iife",
    target: "es2022",
    minify: false,
    define: { __KEYSCAN_BROWSER__: JSON.stringify(target) }
  });
  await cp(path.join(root, "public"), out, { recursive: true });
  const manifest = JSON.parse(await readFile(path.join(root, "manifests", `${target}.json`), "utf8"));
  await writeFile(path.join(out, "manifest.json"), JSON.stringify(manifest, null, 2));
}));

console.log(`Built: ${targets.join(", ")}`);
