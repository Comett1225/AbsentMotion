const assert = require("assert");
const fs = require("fs");
const path = require("path");

const site = require("../site/assets/site.js");
const devices = JSON.parse(fs.readFileSync(path.join(__dirname, "../site/data/devices.json"), "utf8"));
const index = site.buildIndex(devices);

function android(version, model) {
  return {
    userAgent: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
    userAgentData: { platform: "Android", mobile: true, getHighEntropyValues: () => Promise.resolve({ platformVersion: version, model }) }
  };
}

async function recommendation(nav) {
  const env = await site.detect(nav);
  return { env, rec: site.recommend(env, site.findByModelCode(index, env.model)) };
}

async function main() {
  let r = await recommendation(android("14.0.0", "SM-S918B"));
  assert.strictEqual(r.env.android, 14);
  assert.strictEqual(r.rec.kind, "latest");
  assert.ok(/s23 ultra/i.test(r.rec.device.name), "model code SM-S918B names the Galaxy S23 Ultra");

  r = await recommendation(android("11.0.0", "TB-J606L"));
  assert.strictEqual(r.rec.kind, "compatible");
  assert.ok(/lenovo/i.test(r.rec.device.brand) && /p11/i.test(r.rec.device.name), "TB-J606L is a Lenovo Tab P11");

  r = await recommendation(android("8.1.0", "A6000"));
  assert.strictEqual(r.rec.kind, "old");

  r = await recommendation(android("", ""));
  assert.strictEqual(r.env.android, null);
  assert.strictEqual(r.rec.kind, "unknown");

  r = await recommendation(android("0.0.0", ""));
  assert.strictEqual(r.rec.kind, "unknown");

  r = await recommendation(android("13.0.0", "SM-A032F"));
  assert.strictEqual(r.rec.kind, "blocked", "a 32-bit phone is never offered a download");

  r = await recommendation({ userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0.0.0 Safari/537.36", userAgentData: { platform: "Windows", mobile: false } });
  assert.strictEqual(r.rec.kind, "desktop");

  r = await recommendation({ userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15", platform: "MacIntel", maxTouchPoints: 0 });
  assert.strictEqual(r.rec.kind, "desktop");

  r = await recommendation({ userAgent: "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1", platform: "iPhone", maxTouchPoints: 5 });
  assert.strictEqual(r.rec.kind, "ios");

  r = await recommendation({ userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15", platform: "MacIntel", maxTouchPoints: 5 });
  assert.strictEqual(r.rec.kind, "ios", "iPadOS asks for the desktop site");

  r = await recommendation({ userAgent: "Mozilla/5.0 (Android 13; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0" });
  assert.strictEqual(r.env.android, 13);
  assert.strictEqual(r.env.model, null);
  assert.strictEqual(r.rec.kind, "latest");

  r = await recommendation({ userAgent: "Mozilla/5.0 (Android 11; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0" });
  assert.strictEqual(r.rec.kind, "compatible");

  r = await recommendation({ userAgent: "Mozilla/5.0 (Linux; Android 12; SM-G998B Build/SP1A.210812.016; wv) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36" });
  assert.strictEqual(r.env.android, 12);
  assert.strictEqual(r.env.model, "SM-G998B");

  r = await recommendation({ userAgent: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36" });
  assert.strictEqual(r.env.android, null, "the frozen Chrome user agent is not trusted");
  assert.strictEqual(r.rec.kind, "unknown");

  const top = (query) => (site.search(index, query, 6)[0] || { device: {} }).device;
  assert.ok(/s21 ultra/i.test(top("s21 ultra").name));
  assert.ok(/s21 ultra/i.test(top("  GALAXY   S21ULTRA ").name));
  assert.ok(/s21 ultra/i.test(top("sm-g998b").name));
  assert.ok(/s21 ultra/i.test(top("SM-G998B/DS").name));
  assert.ok(/pixel 7a/i.test(top("pixel 7a").name) || /^7a$/i.test(top("pixel 7a").name));
  assert.ok(/note 12/i.test(top("redmi note 12").name));
  assert.ok(/s9/i.test(top("galaxy s9").name));
  assert.strictEqual(site.search(index, "zzzzqqq", 6).length, 0);
  assert.strictEqual(site.search(index, "a", 6).length, 0);

  const pixels = site.search(index, "pixel 7a", 6).map((x) => x.device.name);
  assert.ok(pixels.length >= 1);

  for (const d of devices) {
    const v = site.verdict(d);
    assert.ok(["latest", "compatible", "unsupported"].includes(v.tier));
    assert.ok(v.reason.length > 10);
  }
  console.log("site_test: all checks passed (" + devices.length + " devices)");
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
