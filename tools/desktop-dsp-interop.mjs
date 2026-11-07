// Called by :core-client:desktopDspInteropTest with freshly exported Kotlin fixtures.
import assert from "node:assert/strict";
import { readFile, writeFile, mkdtemp } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { randomUUID } from "node:crypto";

const [, , desktopRoot, fixtures] = process.argv;
const load = (path) => import(pathToFileURL(join(desktopRoot, path)).href);
await load("src/adapters-node/index.ts");
const { CoreDspService } = await load("src/core/audio/dspService.ts");
const { CoreAudioOutputService } = await load(
  "src/core/audio/outputService.ts",
);
const { CoreSettingsRepository } = await load(
  "src/core/persistence/settingsRepository.ts",
);
const { DspAssetLibrary } = await load("src/core/audio/dspAssetLibrary.ts");
const { Vst3CatalogService } = await load("src/core/audio/vst3Catalog.ts");
const { AudioEngineManager } = await load("src/main/audioEngineManager.ts");
const { coreAudioConfiguration, initialAudioSettings } = await load(
  "src/core/audio/settings.ts",
);
const {
  createDefaultPlaybackInfo,
  normalizeOutputConfig,
  normalizeAudioProcessingSettings,
} = await load("src/core/audio/audioEngineHelpers.ts");
const { parseCorrectionProfileFile } = await load(
  "src/core/audio/correctionProfile.ts",
);

const root = await mkdtemp(join(fixtures, "desktop-core-"));
const environment = {
  platform: "win32",
  resolvePath: resolve,
  defaultCachePath: join(root, "cache"),
};
const repository = () =>
  new CoreSettingsRepository({
    filePath: join(root, "settings.json"),
    environment,
    initial: initialAudioSettings({ exclusiveMode: false }),
    changed: () => {},
  });
const settings = repository();
const assets = new DspAssetLibrary(join(root, "assets"), {
  now: Date.now,
  newId: randomUUID,
});
const catalog = new Vst3CatalogService(
  join(root, "vst3"),
  {
    scan: async () => {
      throw new Error("The interop fixture must not scan plugins");
    },
  },
  [],
  {
    platform: "win32",
    now: Date.now,
    helpers: {
      platformSupported: false,
      scannerPresent: false,
      hostPresent: false,
    },
  },
);
const info = createDefaultPlaybackInfo(
  "wasapi",
  "auto",
  false,
  normalizeOutputConfig(),
);
let nativeRevision = 0;
const engine = new AudioEngineManager(coreAudioConfiguration(settings.read()), {
  platform: "win32",
  dspAssetPathResolver: (id) => assets.getKnownPath(id),
  nativeBinding: {
    GetPlaybackInfo: () => JSON.stringify(info),
    GetLastError: () => "",
    SetDspConfig: () => {},
    SetOutputConfig: () => {},
    SetDspPluginChain: () => {},
    ApplyDspState: (revision) => {
      nativeRevision = revision;
    },
    GetDspGraphStatus: () =>
      JSON.stringify({
        revision: nativeRevision,
        nodes: [],
        compileState: "ready",
      }),
    Stop: () => {},
  },
});
const configuration = new CoreAudioOutputService({
  engine,
  settings,
  changed: () => {},
});
let chosen = join(fixtures, "phone.tedsp");
const dsp = new CoreDspService({
  engine,
  configuration,
  settings,
  assets,
  catalog,
  temporaryDirectory: root,
  now: Date.now,
  newId: randomUUID,
  preferences: {
    coreStateRead: async () => ({
      domain: "eq-preferences",
      revision: 0,
      rows: [],
      next: null,
    }),
  },
  failed: (error) => {
    throw error;
  },
  platform: {
    call: async (name, payload) => {
      if (name === "dsp.registerVst3Paths") return payload.paths;
      if (name === "dsp.chooseFile" || name === "dsp.chooseExportFile")
        return chosen;
      throw new Error(`Unexpected interop platform operation: ${name}`);
    },
  },
});
const signal = () => new AbortController().signal;
try {
  await dsp.initialize();
  const imported = await dsp.import(
    { revision: configuration.getRevision(), kind: "profile" },
    signal(),
  );
  assert.deepEqual(imported.state, engine.getDspSceneState());
  assert.equal(imported.state.pinnedSceneId, imported.profile.pinnedSceneId ?? null);
  for (const scene of imported.profile.scenes) {
    const stored = settings
      .read()
      .dspScenes.find((candidate) => candidate.id === scene.id);
    assert.deepEqual(stored.android, scene.android);
    assert.deepEqual(stored.graph, scene.graph);
  }
  // Exercise the settings-file reader, not only the live in-memory scene array.
  assert.deepEqual(repository().read().dspScenes, settings.read().dspScenes);
  if (process.argv[4] === "impulses") {
    assert.equal(imported.state.scenes.length, 4);
    assert.equal(imported.importedAssets.length, 4);
    assert.deepEqual(
      new Set(imported.importedAssets.map((asset) => asset.mediaType)),
      new Set(["audio/wav", "audio/flac", "audio/aiff"]),
    );
    chosen = join(fixtures, "desktop-roundtrip.tedsp");
    await dsp.export(configuration.getRevision(), "无损脉冲响应往返", signal());
    const summary = { scenes: 4, impulseAssets: 4, settingsReload: true };
    await writeFile(
      join(fixtures, "desktop-evidence.json"),
      JSON.stringify(summary, null, 2),
    );
    process.stdout.write(JSON.stringify(summary) + "\n");
  } else {
    assert.equal(imported.state.pinnedSceneId, "phone-parametric");
    const selected = imported.state.scenes.find(
      (scene) => scene.id === "phone-parametric",
    );
    assert.equal(selected.android.dspEnabled, false);
    assert.equal(imported.state.graph.nodes[0].enabled, true);
    chosen = join(fixtures, "desktop-roundtrip.tedsp");
    await dsp.export(configuration.getRevision(), "手机场景往返", signal());

    const scenes = structuredClone(imported.state.scenes);
    const eq = scenes.find((scene) => scene.id === "phone-parametric").graph
      .nodes[0];
    eq.params.preampDb = -4.75;
    eq.params.bands[0].gain = 4.75;
    // A desktop Q=1 graphic EQ must return as phone PEQ, not Q=1.4 sliders.
    const graphic = scenes.find((scene) => scene.id === "phone-graphic").graph
      .nodes[0];
    graphic.params.bands.forEach((band) => {
      band.q = 1;
    });
    await dsp.edit(
      {
        revision: configuration.getRevision(),
        edit: { kind: "scenes", scenes, pinnedSceneId: "phone-parametric" },
      },
      signal(),
    );
    chosen = join(fixtures, "desktop-edited.tedsp");
    await dsp.export(configuration.getRevision(), "电脑编辑后返回", signal());

    const expected = JSON.parse(
      await readFile(join(fixtures, "phone-eq.json"), "utf8"),
    );
    const correction = await parseCorrectionProfileFile(
      join(fixtures, "phone-eq.txt"),
    );
    assert.equal(correction.preampDb, expected.preampDb);
    assert.deepEqual(correction.bands, expected.bands);
    assert.deepEqual(
      normalizeAudioProcessingSettings({
        eqMode: "parametric",
        eqBands: correction.bands,
      }).eqBands,
      expected.bands,
    );
    await writeFile(
      join(fixtures, "desktop-eq.json"),
      JSON.stringify(correction, null, 2),
    );
    const summary = {
      scenes: imported.state.scenes.length,
      impulseAssets: imported.importedAssets.length,
      textBands: correction.bands.length,
      settingsReload: true,
      editedPackageExported: true,
    };
    await writeFile(
      join(fixtures, "desktop-evidence.json"),
      JSON.stringify(summary, null, 2),
    );
    process.stdout.write(JSON.stringify(summary) + "\n");
  }
} finally {
  engine.destroy();
}
