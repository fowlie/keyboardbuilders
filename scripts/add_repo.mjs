#!/usr/bin/env node
// Add one repo OR subfolder to keyboards.json and (optionally) open a PR.
// Usage examples:
//   node scripts/add_repo.mjs --repo foostan/crkbd --dry-run | jq .
//   node scripts/add_repo.mjs --repo https://github.com/tapioki/cephalopoda/tree/main/Architeuthis%20dux --dry-run | jq .
//   node scripts/add_repo.mjs --repo owner/repo --branch main --path "sub/dir"
//   node scripts/add_repo.mjs --repo owner/repo --create-pull-request
//
// Disclaimer: This script is vibe coded, so quality may vary.

import fs from "node:fs";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { validateKeyboards } from "./validate.mjs";

const API = "https://api.github.com";
const GH_TOKEN = process.env.GH_TOKEN;
if (!GH_TOKEN) {
  console.error("Missing GH_TOKEN env");
  process.exit(1);
}

const ROOT = path.resolve(path.join(path.dirname(new URL(import.meta.url).pathname), ".."));
const DB_PATH = path.join(ROOT, "keyboards.json");

// ---------- args ----------
function parseArgs(argv) {
  const out = { repo: null, dryRun: false, base: "main", branchPrefix: "kb/add-", branch: null, path: "" };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--repo" && argv[i + 1]) out.repo = argv[++i];
    else if (a === "--dry-run") out.dryRun = true;
    else if (a === "--base" && argv[i + 1]) out.base = argv[++i];
    else if (a === "--branch-prefix" && argv[i + 1]) out.branchPrefix = argv[++i];
    else if (a === "--branch" && argv[i + 1]) out.branch = argv[++i];
    else if (a === "--path" && argv[i + 1]) out.path = argv[++i].replace(/^\/+|\/+$/g, "");
    else if (a === "--create-pull-request") out.createPullRequest = true;
  }
  if (!out.repo) {
    console.error("Usage: node scripts/add_repo.mjs --repo <owner/name|github url> [--branch main] [--path sub/dir] [--dry-run] [--create-pull-request]");
    process.exit(2);
  }
  return out;
}
const args = parseArgs(process.argv);

// ---------- HTTP ----------
async function ghGet(url, { accept } = {}) {
  const r = await fetch(url, {
    headers: {
      "Authorization": `Bearer ${GH_TOKEN}`,
      "Accept": accept || "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "keyboardbuilders-add-repo/4.0"
    }
  });
  if (!r.ok) {
    const txt = await r.text().catch(() => "");
    throw new Error(`GET ${url} -> ${r.status} ${r.statusText}\n${txt}`);
  }
  const ctype = r.headers.get("content-type") || "";
  if (ctype.includes("json") && !(accept && accept.includes("raw"))) return r.json();
  return r.text();
}

// ---------- parse GitHub spec ----------
function parseRepoSpec(input, cliBranch, cliPath) {
  let owner = "", repo = "", branch = cliBranch || null, subpath = cliPath || "";
  let s = input.trim();

  if (s.startsWith("https://github.com/")) {
    s = s.slice("https://github.com/".length);
    const parts = s.split("/");
    owner = parts[0];
    repo = parts[1];
    if (parts[2] === "tree" && parts[3]) {
      branch = branch || parts[3];
      if (parts.length > 4) subpath = parts.slice(4).join("/");
    }
  } else {
    const parts = s.split("/");
    owner = parts[0];
    repo = parts[1];
    if (!cliPath && parts.length > 2) subpath = parts.slice(2).join("/");
  }
  if (!owner || !repo) throw new Error("Could not parse owner/repo from --repo");
  subpath = (subpath || "").replace(/^\/+|\/+$/g, "");
  return { owner, repo, branch, path: subpath };
}

// ---------- utils ----------
function todayISO() { return new Date().toISOString().slice(0, 10); }
function norm(st) { return (st || "").toLowerCase().replace(/[^a-z0-9]+/g, ""); }

// ---------- detectors ----------
const KEYS_RE = /\b(\d{2,3})\s*(?:keys|key)\b/i;
const MCU_PATTERNS = [
  /rp2040/i, /nrf52840/i, /nrf52\b/i, /stm32f4\d+/i, /stm32f103/i, /stm32f401/i, /stm32f411/i, /atmega32u4/i, /esp32(?:-s3)?/i
];
const FOOTPRINT_PATS = [
  ["Pro Micro", /\b(pro\s*micro|promicro)\b/i],
  ["Elite-C", /\belite[-\s]?c\b/i],
  ["nice!nano", /\bnice[-\s]?nano\b/i],
  ["RP2040 Zero", /\brp2040[-\s]?zero\b/i],
];

function detectFormFactors(text) {
  const t = (text || "").toLowerCase();
  const out = new Set();
  if (/\bsplit\b|\bhalves?\b|\btrrs\b|\bi2c\b/.test(t)) out.add("split");
  if (/\bunibody\b|\bmonoblock\b|\bsingle[-\s]?piece\b/.test(t)) out.add("unibody");
  if (/\bsplay(ed)?\b|\bcolumn\s*splay\b/.test(t)) out.add("splay");
  if (/\bconcave\b|\bkeywell\b|\bdactyl\b|\bmanuform\b|\bkinesis\b/.test(t)) out.add("concave");
  if (/\btilt(ed)?\b/.test(t)) out.add("tilt");
  return Array.from(out).sort();
}
function detectFirmwareTargets(text) {
  const t = (text || "").toLowerCase();
  const set = new Set();
  if (/\bqmk\b/.test(t) || t.includes(" qmk") || t.includes("qmk-")) set.add("QMK");
  if (/\bzmk\b/.test(t) || t.includes(" zmk") || t.includes("zmk-")) set.add("ZMK");
  return Array.from(set).sort();
}
function detectKeys(text) {
  const m = KEYS_RE.exec(text || "");
  if (!m) return null;
  const n = parseInt(m[1], 10);
  return Number.isFinite(n) && n >= 10 && n <= 120 ? n : null;
}
function detectFootprints(text) {
  const res = [];
  for (const [name, pat] of FOOTPRINT_PATS) if (pat.test(text || "")) res.push(name);
  return Array.from(new Set(res)).sort();
}
function detectOnboardMCU(text) {
  for (const pat of MCU_PATTERNS) {
    const m = (text || "").match(pat);
    if (m) return m[0].toLowerCase();
  }
  return null;
}

// ---------- image helpers ----------
function isBadgeUrl(u) {
  const low = u.toLowerCase();
  return (
    low.includes("shields.io") ||
    low.includes("badge.fury.io") ||
    low.includes("travis-ci") ||
    low.includes("circleci") ||
    low.includes("codecov.io") ||
    low.includes("coveralls.io") ||
    (low.includes("github.com") && low.includes("/badge/")) ||
    low.endsWith(".svg")
  );
}
function toRawGithubUrl({ owner, repo, branch, rel }) {
  if (rel.charAt(0) == "/") rel = rel.slice(1); // remove leading slash
  return `https://raw.githubusercontent.com/${owner}/${repo}/${branch}/${rel}`;
}
function normalizeGithubUrl(u) {
  try {
    const url = new URL(u);
    if (url.hostname !== "github.com") return u;
    const seg = url.pathname.split("/").filter(Boolean);
    const owner = seg[0], repo = seg[1], blob = seg[2], branch = seg[3];
    if (blob !== "blob" || !branch || seg.length < 5) return u;
    const rest = seg.slice(4).map(encodeURIComponent).join("/");
    return `https://raw.githubusercontent.com/${owner}/${repo}/${encodeURIComponent(branch)}/${rest}`;
  } catch { return u; }
}
function collectReadmeImages(readme) {
  const imgs = [];
  const mdRe = /!\[[^\]]*]\((\s*<?([^)\s]+)[^)]*?)\)/g; // capture group 2 = URL
  let m;
  while ((m = mdRe.exec(readme)) !== null) if (m[2]) imgs.push(m[2]);
  const htmlRe = /<img[^>]+src=["']([^"']+)["'][^>]*>/gi;
  while ((m = htmlRe.exec(readme)) !== null) if (m[1]) imgs.push(m[1]);
  return imgs;
}
function isAbsoluteUrl(u) { return /^https?:\/\//i.test(u); }
function looksLikeImageFile(u) {
  const low = u.toLowerCase();
  return low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp") || low.endsWith(".gif");
}
function pickImageFromReadme({ readmeText, owner, repo, branch, subpath }) {
  const candidates = collectReadmeImages(readmeText)
    .map(s => s.trim())
    .map(s => s.replace(/^<|>$/g, ""))      // remove surrounding angle brackets
    .map(s => s.replace(/[#?].*$/, ""));    // drop fragments/queries

  for (let raw of candidates) {
    if (isBadgeUrl(raw)) continue;
    const abs = isAbsoluteUrl(raw)
      ? normalizeGithubUrl(raw)
      : toRawGithubUrl({ owner, repo, branch, rel: raw });
    if (looksLikeImageFile(abs)) return abs;
  }
  return null;
}
async function listFolderImages(owner, repo, branch, subpath) {
  if (!subpath) return [];
  try {
    const url = `${API}/repos/${owner}/${repo}/contents/${encodeURI(subpath)}`;
    const j = await ghGet(url + `?ref=${encodeURIComponent(branch)}`);
    if (!Array.isArray(j)) return [];
    return j
      .filter(e => e.type === "file")
      .map(e => e.name)
      .filter(n => /\.(png|jpe?g|webp|gif)$/i.test(n))
      .map(n => `https://raw.githubusercontent.com/${owner}/${repo}/${encodeURIComponent(branch)}/${subpath.split('/').map(encodeURIComponent).join('/')}/${encodeURIComponent(n)}`);
  } catch { return []; }
}

// ---------- repo IO ----------
async function getRepoMeta(owner, repo) {
  const j = await ghGet(`${API}/repos/${owner}/${repo}`);
  let topics = [];
  try {
    const tj = await ghGet(`${API}/repos/${owner}/${repo}/topics`);
    topics = Array.isArray(tj.names) ? tj.names : [];
  } catch {}
  return {
    name: String(j.name).charAt(0).toUpperCase() + j.name.slice(1), // capitalize first letter
    full_name: j.full_name,
    html_url: j.html_url,
    description: j.description || "",
    default_branch: j.default_branch || "HEAD",
    topics,
  };
}
async function getReadme(owner, repo, branch, subpath) {
  const candidates = ["README.md","Readme.md","readme.md","README","README.txt","readme.txt"];
  if (subpath) {
    for (const fname of candidates) {
      const url = `https://raw.githubusercontent.com/${owner}/${repo}/refs/heads/${branch}/${subpath}/${fname}`;
      const j = await ghGet(url);
      if (j) {
        return j
      }
    }
  }
  try {
    const raw = await ghGet(`${API}/repos/${owner}/${repo}/readme`, { accept: "application/vnd.github.raw" });
    if (typeof raw === "string") return raw;
  } catch {}
  try {
    const j = await ghGet(`${API}/repos/${owner}/${repo}/readme`);
    if (j && j.content) {
      const buf = Buffer.from(j.content, "base64");
      return buf.toString("utf8");
    }
  } catch {}
  return "";
}

// ---------- db helpers ----------
function loadDbSync() {
  if (!existsSync(DB_PATH)) return [];
  try { return JSON.parse(readFileSync(DB_PATH, "utf8")); }
  catch { return []; }
}
function saveDbSync(arr) {
  writeFileSync(DB_PATH, JSON.stringify(arr, null, 2) + "\n", "utf8");
}
function normUrl(u) { return (u || "").toLowerCase().replace(/\/+$/, ""); }
function alreadyIn(db, name, repoUrl) {
  const n = norm(name);
  const r = normUrl(repoUrl);
  return db.some(k => norm(k.name) === n || normUrl(k.repo) === r);
}

// ---------- git/PR ----------
function run(cmd, args, opts = {}) {
  const res = spawnSync(cmd, args, { stdio: "inherit", ...opts });
  if (res.status !== 0) throw new Error(`${cmd} ${args.join(" ")} failed (${res.status})`);
}
function createBranchCommitPR(record, { branchPrefix, base }) {
  const slug = (norm(record.name) || "kb").slice(0, 40);
  const stamp = new Date().toISOString().replace(/[-:T.Z]/g, "").slice(0, 14);
  const branch = `${branchPrefix}${slug}-${stamp}`;

  const actor = process.env.GITHUB_ACTOR || "bot";
  run("git", ["config", "user.name", actor]);
  run("git", ["config", "user.email", "bot@example.invalid"]);
  run("git", ["checkout", "-B", branch]);

  const db = loadDbSync();
  db.push(record);
  saveDbSync(db);

  run("git", ["add", "keyboards.json"]);
  run("git", ["commit", "-m", `Add keyboard: ${record.name}`]);
  run("git", ["push", "-u", "origin", branch]);

  const body =
`Auto-imported.

- Repo: ${record.repo}
- Form factors: ${(record.form_factors || []).join(", ")}
- Keys: ${record.keys ?? "n/a"}
- MCU: ${record.mcu} ?? ""
- Firmware: ${(record.firmware_targets || []).join(", ")}
- Image: ${record.image}`;

  run("gh", ["pr", "create", "--title", `Add: ${record.name}`, "--body", body, "--base", base, "--head", branch]);
}

// ---------- main ----------
(async () => {
  try {
    const spec = parseRepoSpec(args.repo, args.branch, args.path);
    const meta = await getRepoMeta(spec.owner, spec.repo);
    const branch = spec.branch || meta.default_branch;

    const readme = await getReadme(spec.owner, spec.repo, branch, spec.path);
    const combo = `${meta.description}\n${(meta.topics || []).join(" ")}\n${readme}`;

    // Detect fields
    const form_factors = detectFormFactors(combo);
    const keys = detectKeys(combo);
    const footprints = detectFootprints(combo);
    const onboard = detectOnboardMCU(combo);

    // mcu (single field): prefer footprint hit, else onboard chip
    let mcu = footprints[0] || (onboard ? onboard.toLowerCase() : "");

    const firmware_targets = detectFirmwareTargets(combo);

    // Prefer image from subfolder README; fallback to first image file in folder; then OG card
    let image = pickImageFromReadme({
      readmeText: readme,
      owner: spec.owner,
      repo: spec.repo,
      branch,
      subpath: spec.path
    });
    if (!image && spec.path) {
      const folderImages = await listFolderImages(spec.owner, spec.repo, branch, spec.path);
      if (folderImages.length) image = folderImages[0];
    }
    const repoWebUrl = spec.path
      ? `https://github.com/${spec.owner}/${spec.repo}/tree/${encodeURIComponent(branch)}/${spec.path}`
      : meta.html_url;
    if (!image && repoWebUrl) {
      const ownerRepo = repoWebUrl.replace("https://github.com/","").replace(/\/+$/,"");
      image = `https://opengraph.githubassets.com/1/${ownerRepo}`;
    }

    const record = {
      name: meta.name + (spec.path ? ` (${spec.path.split("/").slice(-1)[0]})` : ""),
      repo: repoWebUrl,
      image,
      form_factors,
      keys,
      mcu,                    // REQUIRED by schema
      firmware_targets,
      last_checked: todayISO()
    };

    if (args.dryRun) {
      console.log(JSON.stringify(record, null, 2));
    }

    const data = JSON.parse(fs.readFileSync(DB_PATH, "utf8"));

    const existing = data.find(item => item.repo === record.repo);
    if (existing) {
      console.error(`Already present:`, record.repo);
      return;
    }

    // Stop on dry run
    if (args.dryRun) return;

    data.push(record);
    data.sort((a, b) => a.name.localeCompare(b.name));
    fs.writeFileSync(DB_PATH, JSON.stringify(data, null, 2) + "\n", "utf8");

    console.log(`Added ${record.name} to `, DB_PATH);

    const [valid, errors] = validateKeyboards();
    if (!valid) {
      console.error("Validation failed:");
      console.error(JSON.stringify(errors, null, 2));
      return;
    }

    if (args.createPullRequest) {
      createBranchCommitPR(record, { branchPrefix: args.branchPrefix, base: args.base });
    }
  } catch (err) {
    console.error(err?.stack || String(err));
    process.exit(1);
  }
})();
