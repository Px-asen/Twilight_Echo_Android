(() => {
  var __defProp = Object.defineProperty;
  var __defProps = Object.defineProperties;
  var __getOwnPropDescs = Object.getOwnPropertyDescriptors;
  var __getOwnPropSymbols = Object.getOwnPropertySymbols;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __propIsEnum = Object.prototype.propertyIsEnumerable;
  var __defNormalProp = (obj, key, value) => key in obj ? __defProp(obj, key, { enumerable: true, configurable: true, writable: true, value }) : obj[key] = value;
  var __spreadValues = (a, b) => {
    for (var prop in b || (b = {}))
      if (__hasOwnProp.call(b, prop))
        __defNormalProp(a, prop, b[prop]);
    if (__getOwnPropSymbols)
      for (var prop of __getOwnPropSymbols(b)) {
        if (__propIsEnum.call(b, prop))
          __defNormalProp(a, prop, b[prop]);
      }
    return a;
  };
  var __spreadProps = (a, b) => __defProps(a, __getOwnPropDescs(b));

  // src/shared/portablePath.ts
  var defaultIsWindows = (() => {
    try {
      const nav = globalThis.navigator;
      if ((nav == null ? void 0 : nav.platform) && /win/i.test(nav.platform)) return true;
      if ((nav == null ? void 0 : nav.userAgent) && /windows/i.test(nav.userAgent)) return true;
    } catch (e) {
    }
    return false;
  })();
  var defaultCwdProvider = () => defaultIsWindows ? "C:\\" : "/";
  function normalizeArrayPosix(parts, allowAboveRoot) {
    const res = [];
    for (let i = 0; i < parts.length; i += 1) {
      const p = parts[i];
      if (!p || p === ".") continue;
      if (p === "..") {
        if (res.length > 0 && res[res.length - 1] !== "..") {
          res.pop();
        } else if (allowAboveRoot) {
          res.push("..");
        }
      } else {
        res.push(p);
      }
    }
    return res;
  }
  var posix = {
    sep: "/",
    delimiter: ":",
    normalize(p) {
      if (p.length === 0) return ".";
      const isAbs = p.charCodeAt(0) === 47;
      const trailingSlash = p.charCodeAt(p.length - 1) === 47;
      const segments = normalizeArrayPosix(p.split("/"), !isAbs);
      let out = segments.join("/");
      if (!out && !isAbs) {
        out = ".";
      }
      if (out && trailingSlash) {
        out += "/";
      }
      return (isAbs ? "/" : "") + out;
    },
    isAbsolute(p) {
      return p.length > 0 && p.charCodeAt(0) === 47;
    },
    join(...paths) {
      if (paths.length === 0) return ".";
      let joined;
      for (const arg of paths) {
        if (arg.length > 0) {
          if (joined === void 0) joined = arg;
          else joined += `/${arg}`;
        }
      }
      if (joined === void 0) return ".";
      return posix.normalize(joined);
    },
    resolve(...paths) {
      let resolvedPath = "";
      let resolvedAbsolute = false;
      for (let i = paths.length - 1; i >= -1 && !resolvedAbsolute; i -= 1) {
        const path = i >= 0 ? paths[i] : defaultCwdProvider().replace(/\\/g, "/");
        if (path.length === 0) continue;
        resolvedPath = `${path}/${resolvedPath}`;
        resolvedAbsolute = path.charCodeAt(0) === 47;
      }
      resolvedPath = normalizeArrayPosix(resolvedPath.split("/"), !resolvedAbsolute).join("/");
      return (resolvedAbsolute ? "/" : "") + resolvedPath || ".";
    },
    relative(from, to) {
      if (from === to) return "";
      const fromOrig = posix.resolve(from);
      const toOrig = posix.resolve(to);
      if (fromOrig === toOrig) return "";
      const fromParts = fromOrig.split("/").filter(Boolean);
      const toParts = toOrig.split("/").filter(Boolean);
      const length = Math.min(fromParts.length, toParts.length);
      let samePartsLength = length;
      for (let i = 0; i < length; i += 1) {
        if (fromParts[i] !== toParts[i]) {
          samePartsLength = i;
          break;
        }
      }
      const outputParts = [];
      for (let i = samePartsLength; i < fromParts.length; i += 1) {
        outputParts.push("..");
      }
      outputParts.push(...toParts.slice(samePartsLength));
      return outputParts.join("/");
    },
    dirname(p) {
      if (p.length === 0) return ".";
      const hasRoot = p.charCodeAt(0) === 47;
      let end = -1;
      let matchedSlash = true;
      for (let i = p.length - 1; i >= 1; i -= 1) {
        if (p.charCodeAt(i) === 47) {
          if (!matchedSlash) {
            end = i;
            break;
          }
        } else {
          matchedSlash = false;
        }
      }
      if (end === -1) return hasRoot ? "/" : ".";
      if (hasRoot && end === 1) return "//";
      return p.slice(0, end);
    },
    basename(p, suffix) {
      let start = 0;
      let end = -1;
      let matchedSlash = true;
      for (let i = p.length - 1; i >= 0; i -= 1) {
        if (p.charCodeAt(i) === 47) {
          if (!matchedSlash) {
            start = i + 1;
            break;
          }
        } else if (end === -1) {
          matchedSlash = false;
          end = i + 1;
        }
      }
      if (end === -1) return "";
      const base = p.slice(start, end);
      if (suffix && base.endsWith(suffix)) {
        return base.slice(0, base.length - suffix.length);
      }
      return base;
    },
    extname(p) {
      const base = posix.basename(p);
      const dotIndex = base.lastIndexOf(".");
      if (dotIndex <= 0) return "";
      return base.slice(dotIndex);
    },
    parse(p) {
      if (p.length === 0) {
        return { root: "", dir: "", base: "", ext: "", name: "" };
      }
      const root = p.charCodeAt(0) === 47 ? "/" : "";
      const dir = posix.dirname(p);
      const base = posix.basename(p);
      const ext = posix.extname(p);
      const name = ext ? base.slice(0, base.length - ext.length) : base;
      return {
        root,
        dir: dir === "." && !p.startsWith("./") && !root ? "" : dir,
        base,
        ext,
        name
      };
    },
    format(pathObject) {
      const dir = pathObject.dir || pathObject.root || "";
      const base = pathObject.base || `${pathObject.name || ""}${pathObject.ext || ""}`;
      if (!dir) return base;
      return dir === pathObject.root ? `${dir}${base}` : `${dir}/${base}`;
    }
  };
  function splitWindowsRoot(p) {
    const len = p.length;
    if (len === 0) return { device: "", isUnc: false, isAbsolute: false, tail: "" };
    const normalized = p.replace(/\//g, "\\");
    if (normalized.startsWith("\\\\")) {
      const parts = normalized.slice(2).split("\\");
      if (parts.length >= 2 && parts[0] && parts[1]) {
        const device = `\\\\${parts[0]}\\${parts[1]}`;
        const rest = normalized.slice(device.length);
        return {
          device,
          isUnc: true,
          isAbsolute: true,
          tail: rest.startsWith("\\") ? rest.slice(1) : rest
        };
      }
    }
    if (len >= 2 && /^[A-Za-z]:/.test(normalized)) {
      const device = normalized.slice(0, 2);
      const rest = normalized.slice(2);
      const isAbs = rest.startsWith("\\");
      return {
        device,
        isUnc: false,
        isAbsolute: isAbs,
        tail: isAbs ? rest.slice(1) : rest
      };
    }
    if (normalized.startsWith("\\")) {
      return {
        device: "",
        isUnc: false,
        isAbsolute: true,
        tail: normalized.replace(/^\\+/, "")
      };
    }
    return {
      device: "",
      isUnc: false,
      isAbsolute: false,
      tail: normalized
    };
  }
  var win32 = {
    sep: "\\",
    delimiter: ";",
    normalize(p) {
      if (p.length === 0) return ".";
      const { device, isAbsolute, tail } = splitWindowsRoot(p);
      const trailingSlash = /[\\/]$/.test(p);
      let tailNorm = normalizeArrayPosix(tail.split(/\\+/), !isAbsolute).join("\\");
      if (!tailNorm && !isAbsolute) {
        tailNorm = ".";
      }
      if (tailNorm && trailingSlash && tailNorm !== ".") {
        tailNorm += "\\";
      }
      return `${device}${isAbsolute ? "\\" : ""}${tailNorm}`;
    },
    isAbsolute(p) {
      if (p.length === 0) return false;
      if (p.startsWith("/") || p.startsWith("\\")) return true;
      return /^[A-Za-z]:[\\/]/.test(p);
    },
    join(...paths) {
      if (paths.length === 0) return ".";
      const filtered = paths.filter((p) => p.length > 0);
      if (filtered.length === 0) return ".";
      return win32.normalize(filtered.join("\\"));
    },
    resolve(...paths) {
      let resolvedDevice = "";
      let resolvedTail = "";
      let resolvedAbsolute = false;
      for (let i = paths.length - 1; i >= -1; i -= 1) {
        const path = i >= 0 ? paths[i] : defaultCwdProvider();
        if (!path || path.length === 0) continue;
        const { device, isAbsolute, tail } = splitWindowsRoot(path);
        if (device && resolvedDevice && device.toLowerCase() !== resolvedDevice.toLowerCase()) {
          continue;
        }
        if (!resolvedDevice && device) {
          resolvedDevice = device;
        }
        if (!resolvedAbsolute) {
          resolvedTail = `${tail}\\${resolvedTail}`;
          resolvedAbsolute = isAbsolute;
        }
        if (resolvedDevice && resolvedAbsolute) {
          break;
        }
      }
      const normTail = normalizeArrayPosix(resolvedTail.split(/\\+/), !resolvedAbsolute).join("\\");
      return `${resolvedDevice}${resolvedAbsolute ? "\\" : ""}${normTail}` || ".";
    },
    relative(from, to) {
      const fromOrig = win32.resolve(from);
      const toOrig = win32.resolve(to);
      if (fromOrig.toLowerCase() === toOrig.toLowerCase()) return "";
      const fromRoot = splitWindowsRoot(fromOrig);
      const toRoot = splitWindowsRoot(toOrig);
      if (fromRoot.device.toLowerCase() !== toRoot.device.toLowerCase()) {
        return toOrig;
      }
      const fromParts = fromRoot.tail.split("\\").filter(Boolean);
      const toParts = toRoot.tail.split("\\").filter(Boolean);
      const length = Math.min(fromParts.length, toParts.length);
      let samePartsLength = length;
      for (let i = 0; i < length; i += 1) {
        if (fromParts[i].toLowerCase() !== toParts[i].toLowerCase()) {
          samePartsLength = i;
          break;
        }
      }
      const outputParts = [];
      for (let i = samePartsLength; i < fromParts.length; i += 1) {
        outputParts.push("..");
      }
      outputParts.push(...toParts.slice(samePartsLength));
      return outputParts.join("\\");
    },
    dirname(p) {
      if (p.length === 0) return ".";
      const { device, isAbsolute, tail } = splitWindowsRoot(p);
      const root = `${device}${isAbsolute ? "\\" : ""}`;
      const trimmedTail = tail.replace(/\\+$/, "");
      if (!trimmedTail) {
        return root || ".";
      }
      const lastSep = trimmedTail.lastIndexOf("\\");
      if (lastSep === -1) {
        return root || ".";
      }
      return `${root}${trimmedTail.slice(0, lastSep)}`;
    },
    basename(p, suffix) {
      const { tail } = splitWindowsRoot(p);
      const trimmed = tail.replace(/\\+$/, "");
      if (!trimmed) return "";
      const lastSep = trimmed.lastIndexOf("\\");
      const base = lastSep === -1 ? trimmed : trimmed.slice(lastSep + 1);
      if (suffix && base.endsWith(suffix)) {
        return base.slice(0, base.length - suffix.length);
      }
      return base;
    },
    extname(p) {
      const base = win32.basename(p);
      const dotIndex = base.lastIndexOf(".");
      if (dotIndex <= 0) return "";
      return base.slice(dotIndex);
    },
    parse(p) {
      if (p.length === 0) {
        return { root: "", dir: "", base: "", ext: "", name: "" };
      }
      const { device, isAbsolute } = splitWindowsRoot(p);
      const root = `${device}${isAbsolute ? "\\" : ""}`;
      const dir = win32.dirname(p);
      const base = win32.basename(p);
      const ext = win32.extname(p);
      const name = ext ? base.slice(0, base.length - ext.length) : base;
      return {
        root,
        dir: dir === "." && !p.startsWith(".\\") && !p.startsWith("./") && !root ? "" : dir,
        base,
        ext,
        name
      };
    },
    format(pathObject) {
      const dir = pathObject.dir || pathObject.root || "";
      const base = pathObject.base || `${pathObject.name || ""}${pathObject.ext || ""}`;
      if (!dir) return base;
      return dir === pathObject.root ? `${dir}${base}` : `${dir}\\${base}`;
    }
  };

  // src/shared/pluginExperience.ts
  function normalizeExperiences(manifest) {
    var _a;
    const raw = (_a = manifest.contributes) == null ? void 0 : _a.experiences;
    if (raw === void 0) return [];
    if (manifest.apiVersion < 4 || !manifest.type.includes("ui") || !manifest.permissions.includes("ui:inject"))
      throw new Error("Experiences require API v4, type ui and ui:inject");
    if (!Array.isArray(raw) || !raw.length || raw.length > 8) throw new Error("Invalid experiences");
    const ids = /* @__PURE__ */ new Set();
    return raw.map((item) => {
      if (!item || typeof item !== "object") throw new Error("Invalid experience");
      const value = item;
      const { id, name, entry, bridgeVersion } = value;
      if (typeof id !== "string" || !/^[a-z][a-z0-9-]{0,63}$/.test(id) || ids.has(id))
        throw new Error("Invalid or duplicate experience id");
      if (typeof name !== "string" || !name.trim() || name.length > 120)
        throw new Error("Invalid experience name");
      if (typeof entry !== "string" || !/^[a-zA-Z0-9_/-]+\.html$/.test(entry) || entry.startsWith("/") || entry.split("/").includes(".."))
        throw new Error("Experience entry must be a packaged HTML file");
      if (bridgeVersion !== 1 && bridgeVersion !== 2)
        throw new Error("Unsupported experience bridge version");
      if (value.launch !== void 0 && value.launch !== "page" && value.launch !== "panel")
        throw new Error("Experience launch must be page or panel");
      ids.add(id);
      return __spreadValues({
        id,
        name: name.trim(),
        entry,
        bridgeVersion
      }, value.launch === "panel" ? { launch: "panel" } : {});
    });
  }

  // src/shared/pluginTypes.ts
  var TWILIGHT_PLUGIN_API_VERSION = 4;

  // src/core/plugins/versionRange.ts
  function parseVersion(value) {
    var _a, _b;
    const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.exec(
      value.trim()
    );
    if (!match) return null;
    const core = match.slice(1, 4).map(Number);
    const prerelease = (_b = (_a = match[4]) == null ? void 0 : _a.split(".")) != null ? _b : [];
    if (core.some((part) => !Number.isSafeInteger(part))) return null;
    if (prerelease.some((part) => /^0\d+$/.test(part))) return null;
    return { core, prerelease };
  }
  function compareVersions(left, right) {
    for (let index = 0; index < 3; index++) {
      if (left.core[index] !== right.core[index]) return left.core[index] > right.core[index] ? 1 : -1;
    }
    if (!left.prerelease.length || !right.prerelease.length) {
      return left.prerelease.length === right.prerelease.length ? 0 : left.prerelease.length ? -1 : 1;
    }
    for (let index = 0; index < Math.max(left.prerelease.length, right.prerelease.length); index++) {
      const a = left.prerelease[index];
      const b = right.prerelease[index];
      if (a === b) continue;
      if (a === void 0) return -1;
      if (b === void 0) return 1;
      const numericA = /^\d+$/.test(a);
      const numericB = /^\d+$/.test(b);
      if (numericA !== numericB) return numericA ? -1 : 1;
      if (numericA && a.length !== b.length) return a.length > b.length ? 1 : -1;
      return a > b ? 1 : -1;
    }
    return 0;
  }
  function compareSemver(left, right) {
    const a = parseVersion(left);
    const b = parseVersion(right);
    if (!a || !b) throw new Error("Invalid semantic version");
    return compareVersions(a, b);
  }
  function isCompatibleTwilightRange(range, version) {
    return isCompatibleVersionRange(range, version, { includePrerelease: true });
  }
  function isCompatibleVersionRange(range, version, options = {}) {
    var _a, _b;
    const actual = parseVersion(version);
    if (!actual) return false;
    const trimmed = range.trim();
    if (trimmed === "*" || trimmed === "") {
      return options.includePrerelease === true || actual.prerelease.length === 0;
    }
    const operator = (_b = (_a = /^(>=|\^|~)/.exec(trimmed)) == null ? void 0 : _a[0]) != null ? _b : "";
    const required = parseVersion(trimmed.slice(operator.length));
    if (!required) return false;
    if (!options.includePrerelease && actual.prerelease.length && (!required.prerelease.length || actual.core.some((part, index) => part !== required.core[index])))
      return false;
    const comparison = compareVersions(actual, required);
    if (!operator) return comparison === 0;
    if (comparison < 0) return false;
    if (operator === ">=") return true;
    const [major, minor, patch] = required.core;
    const upper = operator === "~" ? [major, minor + 1, 0] : major > 0 ? [major + 1, 0, 0] : minor > 0 ? [0, minor + 1, 0] : [0, 0, patch + 1];
    return compareVersions(actual, { core: upper, prerelease: ["0"] }) < 0;
  }

  // src/core/plugins/manifest.ts
  var PLUGIN_TYPES = ["provider", "tool", "ui", "theme", "dsp"];
  var PLUGIN_PERMISSIONS = [
    "network",
    "filesystem:read",
    "filesystem:write",
    "player:control",
    "player:observe",
    "library:read",
    "library:write",
    "settings",
    "clipboard",
    "ui:inject",
    "app:settings",
    "plugins:manage",
    "dsp:native"
  ];
  var PLUGIN_ID_PATTERN = /^[a-z0-9]+(?:[.-][a-z0-9]+)+$/;
  var SEMVER_PATTERN = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;
  function isRecord(value) {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
  }
  function requireString(source, key) {
    const value = source[key];
    if (typeof value !== "string" || !value.trim()) {
      throw new Error(`plugin.json \u7F3A\u5C11\u5FC5\u586B\u5B57\u7B26\u4E32\u5B57\u6BB5\uFF1A${key}`);
    }
    return value.trim();
  }
  function normalizeRelativePath(value, key) {
    if (value == null) return void 0;
    if (typeof value !== "string" || !value.trim()) {
      throw new Error(`plugin.json \u5B57\u6BB5 ${key} \u5FC5\u987B\u662F\u76F8\u5BF9\u8DEF\u5F84\u5B57\u7B26\u4E32`);
    }
    const source = value.trim();
    if (source.includes("\0")) {
      throw new Error(`plugin.json \u5B57\u6BB5 ${key} \u5305\u542B\u975E\u6CD5\u7A7A\u5B57\u7B26`);
    }
    const slashPath = source.replace(/\\/g, "/");
    if (posix.isAbsolute(slashPath) || win32.isAbsolute(source) || win32.isAbsolute(slashPath) || /^[A-Za-z]:/.test(source)) {
      throw new Error(`plugin.json \u5B57\u6BB5 ${key} \u4E0D\u80FD\u6307\u5411\u63D2\u4EF6\u76EE\u5F55\u5916`);
    }
    const normalized = posix.normalize(slashPath);
    if (normalized === ".." || normalized.startsWith("../")) {
      throw new Error(`plugin.json \u5B57\u6BB5 ${key} \u4E0D\u80FD\u6307\u5411\u63D2\u4EF6\u76EE\u5F55\u5916`);
    }
    if (normalized === "." || normalized.endsWith("/")) {
      throw new Error(`plugin.json \u5B57\u6BB5 ${key} \u5FC5\u987B\u6307\u5411\u63D2\u4EF6\u5185\u6587\u4EF6`);
    }
    return normalized;
  }
  function normalizeTypes(value) {
    if (!Array.isArray(value) || value.length === 0) {
      throw new Error("plugin.json \u5B57\u6BB5 type \u5FC5\u987B\u662F\u975E\u7A7A\u6570\u7EC4");
    }
    const types = value.map((item) => {
      if (typeof item !== "string" || !PLUGIN_TYPES.includes(item)) {
        throw new Error(`plugin.json \u542B\u6709\u672A\u77E5\u63D2\u4EF6\u7C7B\u578B\uFF1A${String(item)}`);
      }
      return item;
    });
    return [...new Set(types)];
  }
  function normalizePermissions(value) {
    if (!Array.isArray(value)) {
      throw new Error("plugin.json \u5B57\u6BB5 permissions \u5FC5\u987B\u662F\u6570\u7EC4");
    }
    const permissions = value.map((item) => {
      if (typeof item !== "string" || !PLUGIN_PERMISSIONS.includes(item)) {
        throw new Error(`plugin.json \u542B\u6709\u672A\u77E5\u6743\u9650\u58F0\u660E\uFF1A${String(item)}`);
      }
      return item;
    });
    return [...new Set(permissions)];
  }
  function normalizeBinary(value) {
    if (value == null) return void 0;
    if (!isRecord(value)) throw new Error("plugin.json \u5B57\u6BB5 binary \u5FC5\u987B\u662F\u5BF9\u8C61");
    const binary = {};
    for (const [platform, path] of Object.entries(value)) {
      const normalized = normalizeRelativePath(path, `binary.${platform}`);
      if (normalized) binary[platform] = normalized;
    }
    return Object.keys(binary).length > 0 ? binary : void 0;
  }
  function canonicalizePluginManifestPaths(raw) {
    const canonical = __spreadValues({}, raw);
    setCanonicalPath(canonical, "main", normalizeRelativePath(raw.main, "main"));
    setCanonicalPath(canonical, "icon", normalizeRelativePath(raw.icon, "icon"));
    const binary = normalizeBinary(raw.binary);
    if (binary) canonical.binary = binary;
    else delete canonical.binary;
    return canonical;
  }
  function setCanonicalPath(target, key, value) {
    if (value) target[key] = value;
    else delete target[key];
  }
  function hasDeclarativeThemeContribution(raw, type) {
    if (!type.includes("theme")) return false;
    const contributes = raw.contributes;
    if (!isRecord(contributes)) return false;
    return Array.isArray(contributes.themes) && contributes.themes.length > 0;
  }
  function validateThemeSchemaVersion(raw, apiVersion) {
    if (!isRecord(raw.contributes) || !Array.isArray(raw.contributes.themes)) return;
    for (const contribution of raw.contributes.themes) {
      if (!isRecord(contribution) || !isRecord(contribution.structured)) continue;
      const schemaVersion = contribution.structured.schemaVersion;
      if (schemaVersion !== 1 && schemaVersion !== 2 && schemaVersion !== 3) {
        throw new Error("theme contribution structured schemaVersion must be 1, 2, or 3");
      }
      if (schemaVersion === 2 && apiVersion < 2) {
        throw new Error("structured schemaVersion 2 \u9700\u8981 plugin.json apiVersion 2");
      }
      if (schemaVersion === 3 && apiVersion < 3) {
        throw new Error("structured schemaVersion 3 \u9700\u8981 plugin.json apiVersion 3");
      }
      if (contribution.structured.layout != null && schemaVersion !== 3) {
        throw new Error("theme contribution layout requires structured schemaVersion 3");
      }
      if (contribution.structured.layout != null && !isRecord(contribution.structured.layout)) {
        throw new Error("theme contribution layout must be an object");
      }
    }
  }
  function normalizeDependencies(value) {
    if (value == null) return void 0;
    if (!isRecord(value)) throw new Error("plugin.json \u5B57\u6BB5 dependencies \u5FC5\u987B\u662F\u5BF9\u8C61");
    const dependencies = {};
    for (const [id, range] of Object.entries(value)) {
      if (!PLUGIN_ID_PATTERN.test(id)) {
        throw new Error(`plugin.json dependencies \u542B\u6709\u975E\u6CD5\u63D2\u4EF6 ID\uFF1A${id}`);
      }
      if (typeof range !== "string" || !isSupportedSemverRange(range.trim())) {
        throw new Error(`plugin.json dependencies.${id} \u5FC5\u987B\u662F\u53D7\u652F\u6301\u7684 semver range`);
      }
      dependencies[id] = range.trim();
    }
    return Object.keys(dependencies).length > 0 ? dependencies : void 0;
  }
  function validatePluginManifest(raw) {
    if (!isRecord(raw)) throw new Error("plugin.json \u5FC5\u987B\u662F JSON \u5BF9\u8C61");
    const id = requireString(raw, "id");
    if (!PLUGIN_ID_PATTERN.test(id)) {
      throw new Error("plugin.json \u5B57\u6BB5 id \u5FC5\u987B\u662F\u53CD\u57DF\u540D\u98CE\u683C\uFF0C\u4F8B\u5982 com.example.plugin");
    }
    const version = requireString(raw, "version");
    if (!SEMVER_PATTERN.test(version)) {
      throw new Error("plugin.json \u5B57\u6BB5 version \u5FC5\u987B\u9075\u5FAA semver\uFF0C\u4F8B\u5982 1.0.0");
    }
    const engines = raw.engines;
    if (!isRecord(engines) || typeof engines.twilightEcho !== "string" || !engines.twilightEcho) {
      throw new Error("plugin.json \u7F3A\u5C11 engines.twilightEcho \u517C\u5BB9\u8303\u56F4");
    }
    const apiVersion = raw.apiVersion;
    if (!Number.isInteger(apiVersion)) {
      throw new Error("plugin.json \u5B57\u6BB5 apiVersion \u5FC5\u987B\u662F\u6B63\u6574\u6570");
    }
    const normalizedApiVersion = apiVersion;
    if (normalizedApiVersion < 1) {
      throw new Error("plugin.json \u5B57\u6BB5 apiVersion \u5FC5\u987B\u662F\u6B63\u6574\u6570");
    }
    if (normalizedApiVersion > TWILIGHT_PLUGIN_API_VERSION) {
      throw new Error(
        `\u63D2\u4EF6 API \u7248\u672C ${normalizedApiVersion} \u9AD8\u4E8E\u5BBF\u4E3B\u652F\u6301\u7248\u672C ${TWILIGHT_PLUGIN_API_VERSION}`
      );
    }
    validateThemeSchemaVersion(raw, normalizedApiVersion);
    const type = normalizeTypes(raw.type);
    const canonicalPaths = canonicalizePluginManifestPaths(raw);
    const main = canonicalPaths.main;
    const binary = canonicalPaths.binary;
    const dependencies = normalizeDependencies(raw.dependencies);
    if (type.length === 1 && type[0] === "theme" && (main || binary)) {
      throw new Error("\u7EAF theme \u63D2\u4EF6\u53EA\u80FD\u901A\u8FC7 contributes.themes \u58F0\u660E\uFF0C\u4E0D\u80FD\u5305\u542B main \u6216 binary");
    }
    const permissions = normalizePermissions(raw.permissions);
    const experiences = normalizeExperiences({
      apiVersion: normalizedApiVersion,
      type,
      permissions,
      contributes: raw.contributes
    });
    if (!main && !binary && !hasDeclarativeThemeContribution(raw, type) && !experiences.length) {
      throw new Error("plugin.json \u5FC5\u987B\u58F0\u660E main \u6216 binary\uFF0C\u6216\u4E3A theme \u58F0\u660E contributes.themes");
    }
    if (type.includes("dsp") && !binary) throw new Error("type \u5305\u542B dsp \u65F6\u5FC5\u987B\u58F0\u660E binary");
    if (type.includes("dsp") && !permissions.includes("dsp:native")) {
      throw new Error("type \u5305\u542B dsp \u65F6\u5FC5\u987B\u58F0\u660E dsp:native \u6743\u9650");
    }
    return __spreadValues({
      id,
      name: requireString(raw, "name"),
      version,
      description: requireString(raw, "description"),
      author: requireString(raw, "author"),
      license: requireString(raw, "license"),
      type,
      main,
      binary,
      dependencies,
      engines: {
        twilightEcho: engines.twilightEcho.trim()
      },
      apiVersion: normalizedApiVersion,
      permissions,
      contributes: raw.contributes,
      homepage: typeof raw.homepage === "string" ? raw.homepage.trim() : void 0,
      repository: typeof raw.repository === "string" ? raw.repository.trim() : void 0,
      icon: canonicalPaths.icon,
      signature: raw.signature
    }, raw.androidRuntime ? { androidRuntime: raw.androidRuntime } : {});
  }
  function isSupportedSemverRange(range) {
    if (range === "*") return true;
    if (SEMVER_PATTERN.test(range)) return true;
    if (range.startsWith("^") || range.startsWith("~")) {
      return SEMVER_PATTERN.test(range.slice(1).trim());
    }
    if (range.startsWith(">=")) {
      return SEMVER_PATTERN.test(range.slice(2).trim());
    }
    return false;
  }

  // src/core/plugins/mobileRepository.ts
  function androidPluginRuntime(raw) {
    const value = raw;
    if (!value || value.apiVersion !== 1) throw new Error("\u6B64\u63D2\u4EF6\u5C1A\u672A\u63D0\u4F9B Android \u8FD0\u884C\u5165\u53E3");
    if (typeof value.entry !== "string" || !/^[\w/-]+\.js$/.test(value.entry) || value.entry.split("/").some((part) => part === ".." || part === "") || value.entry.startsWith("/"))
      throw new Error("Android \u63D2\u4EF6\u5165\u53E3\u8DEF\u5F84\u65E0\u6548");
    if (!Array.isArray(value.permissions) || value.permissions.some((p) => !["network", "settings"].includes(p)))
      throw new Error("\u6B64\u63D2\u4EF6\u9700\u8981 Android \u5C1A\u4E0D\u652F\u6301\u7684\u6743\u9650");
    if (!Array.isArray(value.networkHosts) || value.networkHosts.length > 32 || value.networkHosts.some((host) => typeof host !== "string" || !/^(?:[a-z0-9-]+\.)+[a-z]{2,}$/.test(host)))
      throw new Error("\u63D2\u4EF6\u7F51\u7EDC\u6388\u6743\u5FC5\u987B\u662F\u660E\u786E\u7684 HTTPS \u4E3B\u673A\u540D");
    return __spreadProps(__spreadValues({}, value), { permissions: [...new Set(value.permissions)], networkHosts: [...new Set(value.networkHosts)] });
  }
  function mobilePluginManifest(raw, appVersion = "1.4.0-perview.1") {
    var _a;
    const manifest = validatePluginManifest(raw);
    if (!isCompatibleTwilightRange(manifest.engines.twilightEcho, appVersion)) throw new Error("\u63D2\u4EF6\u9700\u8981\u5176\u4ED6\u7248\u672C\u7684 Twilight Echo");
    if (manifest.type.length !== 1 || manifest.type[0] !== "provider" || manifest.binary || Object.keys((_a = manifest.dependencies) != null ? _a : {}).length) throw new Error("Android \u5F53\u524D\u652F\u6301\u72EC\u7ACB\u7684 JavaScript Provider \u63D2\u4EF6");
    return __spreadProps(__spreadValues({}, manifest), { androidRuntime: androidPluginRuntime(raw.androidRuntime) });
  }
  function mobileRepositoryRequest(input) {
    var _a;
    switch (input.operation) {
      case "manifest":
        return mobilePluginManifest(input.manifest);
      case "catalog": {
        if (((_a = input.index) == null ? void 0 : _a.schemaVersion) !== 1 || !Array.isArray(input.index.plugins) || input.index.plugins.length > 1e3)
          throw new Error("\u63D2\u4EF6\u4ED3\u5E93\u7D22\u5F15\u683C\u5F0F\u65E0\u6548");
        const seen = /* @__PURE__ */ new Set();
        return input.index.plugins.map((raw) => {
          var _a2, _b;
          const manifest = validatePluginManifest(raw);
          if (seen.has(manifest.id)) throw new Error("\u63D2\u4EF6\u4ED3\u5E93\u5305\u542B\u91CD\u590D ID");
          seen.add(manifest.id);
          if (!/^[a-f0-9]{64}$/i.test(raw.checksumSha256)) throw new Error("\u63D2\u4EF6\u7F3A\u5C11\u6709\u6548\u7684\u5B8C\u6574\u6027\u6821\u9A8C");
          if (typeof raw.sourceUrl !== "string" || !raw.sourceUrl) throw new Error("\u63D2\u4EF6\u7F3A\u5C11\u4E0B\u8F7D\u5730\u5740");
          let unsupportedReason = null;
          try {
            mobilePluginManifest(raw);
          } catch (e) {
            unsupportedReason = e.message;
          }
          const installed = ((_a2 = input.installed) != null ? _a2 : []).find((p) => p.id === manifest.id);
          return __spreadProps(__spreadValues({}, manifest), {
            androidRuntime: raw.androidRuntime,
            sourceUrl: raw.sourceUrl,
            checksumSha256: raw.checksumSha256.toLowerCase(),
            unsupportedReason,
            installedVersion: (_b = installed == null ? void 0 : installed.version) != null ? _b : null,
            enabled: (installed == null ? void 0 : installed.enabled) === true,
            updateAvailable: installed ? compareSemver(manifest.version, installed.version) > 0 : false
          });
        });
      }
      case "verifyIdentity": {
        const manifest = mobilePluginManifest(input.manifest);
        if (manifest.id !== input.expected.id || manifest.version !== input.expected.version)
          throw new Error("\u63D2\u4EF6\u5305\u4E0E\u4ED3\u5E93\u58F0\u660E\u7684\u8EAB\u4EFD\u4E0D\u4E00\u81F4");
        if (JSON.stringify(manifest.androidRuntime) !== JSON.stringify(androidPluginRuntime(input.expected.androidRuntime)))
          throw new Error("\u63D2\u4EF6\u5305\u6743\u9650\u4E0E\u4ED3\u5E93\u58F0\u660E\u4E0D\u4E00\u81F4");
        return manifest;
      }
      default:
        throw new Error("\u672A\u77E5\u7684\u63D2\u4EF6\u4ED3\u5E93\u64CD\u4F5C");
    }
  }

  // sdk/plugins/repository-entry.ts
  globalThis.__TWILIGHT_PLUGIN_REPOSITORY__ = (json) => {
    try {
      return JSON.stringify({ ok: true, value: mobileRepositoryRequest(JSON.parse(json)) });
    } catch (e) {
      return JSON.stringify({ ok: false, error: e.message });
    }
  };
})();
