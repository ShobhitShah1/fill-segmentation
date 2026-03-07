import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const projectRoot = process.cwd();
const expoModuleScriptsRoot = path.dirname(
  require.resolve("expo-module-scripts/package.json"),
);
const tscPath = require.resolve("typescript/lib/tsc.js");
const extraTargets = ["plugin", "cli", "utils", "scripts"];

const [command, ...args] = process.argv.slice(2);

process.env.EXPO_NONINTERACTIVE = "1";

function getExpoModuleScriptPath(name) {
  return path.join(expoModuleScriptsRoot, "bin", name);
}

function run(commandPath, commandArgs = []) {
  return new Promise((resolve, reject) => {
    const child = spawn(commandPath, commandArgs, {
      cwd: projectRoot,
      env: process.env,
      stdio: "inherit",
    });

    child.on("error", reject);
    child.on("exit", (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`${commandPath} exited with code ${code ?? 1}`));
      }
    });
  });
}

function resolveBuildArgs(rawArgs) {
  const [maybeTarget, ...rest] = rawArgs;

  if (!maybeTarget || !extraTargets.includes(maybeTarget)) {
    return rawArgs;
  }

  const targetDir = path.join(projectRoot, maybeTarget);
  if (!fs.existsSync(path.join(targetDir, "tsconfig.json"))) {
    console.log(
      `tsconfig.json not found in ${maybeTarget}, skipping build for ${maybeTarget}`,
    );
    return null;
  }

  return ["--build", targetDir, ...rest];
}

async function build(rawArgs = []) {
  const buildArgs = resolveBuildArgs(rawArgs);
  if (buildArgs === null) {
    return;
  }

  await run(process.execPath, [tscPath, ...buildArgs]);
}

async function prepare() {
  console.log("Configuring module");
  await run(process.execPath, [getExpoModuleScriptPath("expo-module-clean")]);
  await run(process.execPath, [getExpoModuleScriptPath("expo-module-configure")]);
  await build();

  for (const target of extraTargets) {
    if (!fs.existsSync(path.join(projectRoot, target))) {
      continue;
    }

    console.log(`Configuring ${target}`);
    await run(process.execPath, [
      getExpoModuleScriptPath("expo-module-clean"),
      target,
    ]);
    await build([target]);
  }
}

if (command === "build") {
  await build(args);
} else if (command === "prepare") {
  await prepare();
} else {
  console.error(`Unknown command: ${command}`);
  process.exit(1);
}
