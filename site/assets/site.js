(function (root) {
  "use strict";

  var LATEST_FROM = 13;
  var COMPATIBLE_FROM = 9;

  function norm(value) {
    return String(value == null ? "" : value)
      .toLowerCase()
      .normalize("NFKD")
      .replace(/[̀-ͯ]/g, "")
      .replace(/\+/g, " plus ")
      .replace(/[^a-z0-9]+/g, " ")
      .replace(/\s+/g, " ")
      .trim();
  }

  function squash(value) {
    return norm(value).replace(/ /g, "");
  }

  function androidMajor(version) {
    var match = /^(\d+)/.exec(String(version || "").trim());
    var major = match ? parseInt(match[1], 10) : 0;
    return major > 0 ? major : null;
  }

  function modelFromUserAgent(ua) {
    var match = /Android\s[\d.]+;\s*([^;)]+)/.exec(ua || "");
    if (!match) return null;
    var model = match[1].replace(/\sBuild\/.*$/, "").trim();
    return model && !/^(K|Mobile|Tablet|wv)$/i.test(model) ? model : null;
  }

  function detect(nav) {
    var ua = nav.userAgent || "";
    var data = nav.userAgentData;
    if (data) {
      if (data.platform === "Android") {
        return Promise.resolve(data.getHighEntropyValues ? data.getHighEntropyValues(["platformVersion", "model"]) : {})
          .catch(function () { return {}; })
          .then(function (hints) {
            hints = hints || {};
            return { platform: "android", android: androidMajor(hints.platformVersion), model: String(hints.model || "").trim() || null };
          });
      }
      return Promise.resolve({ platform: data.platform === "iOS" ? "ios" : "desktop", android: null, model: null });
    }
    if (/iPhone|iPad|iPod/.test(ua) || (nav.platform === "MacIntel" && nav.maxTouchPoints > 1)) {
      return Promise.resolve({ platform: "ios", android: null, model: null });
    }
    if (/Android\s+\d/.test(ua)) {
      var reduced = /\(Linux; Android 10; K\)/.test(ua);
      var match = /Android\s+(\d+)/.exec(ua);
      return Promise.resolve({
        platform: "android",
        android: reduced ? null : androidMajor(match[1]),
        model: reduced ? null : modelFromUserAgent(ua)
      });
    }
    return Promise.resolve({ platform: "desktop", android: null, model: null });
  }

  function buildIndex(devices) {
    return devices.map(function (d) {
      var variants = [];
      [d.name].concat(d.aliases || []).forEach(function (name) {
        [norm(name), norm(d.brand + " " + name)].forEach(function (v) {
          if (v && variants.indexOf(v) < 0) variants.push(v);
        });
      });
      return {
        device: d,
        names: variants,
        tokens: variants.map(function (v) { return v.split(" "); }),
        squashed: variants.map(function (v) { return v.replace(/ /g, ""); }),
        codes: (d.modelCodes || []).map(function (code) {
          return { text: squash(code.replace(/\*$/, "")), prefix: /\*$/.test(code) };
        })
      };
    });
  }

  function scoreEntry(entry, q) {
    var best = 0;
    var codeLike = /[a-z]/.test(q.squash) && /[0-9]/.test(q.squash) && q.squash.length >= 4;
    entry.codes.forEach(function (code) {
      if (!code.text || !codeLike) return;
      if (q.squash === code.text) best = Math.max(best, 100);
      else if (code.prefix && q.squash.indexOf(code.text) === 0) best = Math.max(best, 96);
      else if (!code.prefix && code.text.length >= 6 && q.squash.indexOf(code.text) === 0) best = Math.max(best, 92);
      else if (q.squash.length >= 5 && code.text.indexOf(q.squash) === 0) best = Math.max(best, 72);
    });
    entry.names.forEach(function (name, i) {
      var tokens = entry.tokens[i];
      if (name === q.norm) {
        best = Math.max(best, 94);
      } else if (entry.squashed[i] === q.squash) {
        best = Math.max(best, 92);
      } else if (q.tokens.every(function (t) {
        return tokens.some(function (x) { return /^[0-9]+$/.test(t) ? x === t : x.indexOf(t) === 0; });
      })) {
        best = Math.max(best, 80 - Math.min((tokens.length - q.tokens.length) * 3, 25));
      } else if (q.squash.length >= 3 && entry.squashed[i].indexOf(q.squash) >= 0) {
        best = Math.max(best, 58);
      }
    });
    return best;
  }

  function search(index, query, limit) {
    var q = { norm: norm(query), squash: squash(query) };
    q.tokens = q.norm ? q.norm.split(" ") : [];
    if (q.squash.length < 2) return [];
    var scored = index.map(function (entry) { return { device: entry.device, score: scoreEntry(entry, q) }; })
      .filter(function (r) { return r.score >= 55; });
    scored.sort(function (a, b) {
      return b.score - a.score || (b.device.year || 0) - (a.device.year || 0) || a.device.name.length - b.device.name.length;
    });
    var floor = scored.length ? Math.max(55, scored[0].score - 40) : 55;
    return scored.filter(function (r) { return r.score >= floor; }).slice(0, limit || 6);
  }

  function findByModelCode(index, model) {
    if (!model) return [];
    var hits = search(index, model, 6).filter(function (r) { return r.score >= 92; });
    if (!hits.length) return [];
    return hits.filter(function (r) { return r.score === hits[0].score; }).map(function (r) { return r.device; });
  }

  function deviceNames(devices) {
    var names = [];
    devices.forEach(function (d) {
      var full = d.name.toLowerCase().indexOf(d.brand.toLowerCase()) === 0 ? d.name : d.brand + " " + d.name;
      if (names.indexOf(full) < 0) names.push(full);
    });
    return names.join(" or ");
  }

  function blockedReason(device) {
    if (!device) return null;
    if (device.arm64 === false) return "it has a 32-bit system, and AbsentMotion needs a 64-bit ARM phone";
    if (device.vulkan11 === false) return "its graphics chip does not support Vulkan 1.1, which AbsentMotion needs";
    return null;
  }

  function recommend(env, device) {
    if (env.platform === "ios") return { kind: "ios" };
    if (env.platform !== "android") return { kind: "desktop" };
    var blocked = blockedReason(device);
    if (blocked) return { kind: "blocked", reason: blocked, device: device, android: env.android };
    if (env.android == null) return { kind: "unknown", device: device };
    if (env.android >= LATEST_FROM) return { kind: "latest", device: device, android: env.android };
    if (env.android >= COMPATIBLE_FROM) return { kind: "compatible", device: device, android: env.android };
    return { kind: "old", device: device, android: env.android };
  }

  function verdict(device) {
    var blocked = blockedReason(device);
    var tier = blocked ? "unsupported" : device.tier;
    var launch = device.launchAndroid;
    var max = device.maxAndroid;
    var reason;
    if (blocked) {
      reason = "This phone cannot run AbsentMotion: " + blocked + ".";
    } else if (tier === "latest" && launch >= LATEST_FROM) {
      reason = "It came with Android " + launch + ", so it should run the Latest version.";
    } else if (tier === "latest") {
      reason = "It came with Android " + launch + " and can be updated to Android " + max + ". Once it runs Android 13 or newer it should run the Latest version. Until then, use the Compatible version.";
    } else if (tier === "compatible") {
      reason = "Its newest Android version is " + max + ", so it should work with the Compatible version (Android 9 to 12). It cannot run the Latest version, which needs Android 13 or newer.";
    } else {
      reason = "Its newest Android version is " + max + " and AbsentMotion needs Android 9 or newer, with a 64-bit ARM chip and Vulkan 1.1.";
    }
    var ai = { great: "AI features (captions, background removal) should run fast.", good: "AI features should run at a reasonable speed.", slow: "AI features will probably be slow here; short clips are fine." }[device.aiClass];
    return { tier: tier, reason: reason, ai: tier === "unsupported" ? "" : ai };
  }

  var TIER_NAMES = { latest: "Latest", compatible: "Compatible", unsupported: "Not supported" };

  var api = {
    norm: norm, squash: squash, androidMajor: androidMajor, modelFromUserAgent: modelFromUserAgent, detect: detect,
    buildIndex: buildIndex, search: search, findByModelCode: findByModelCode, deviceNames: deviceNames, recommend: recommend, verdict: verdict, TIER_NAMES: TIER_NAMES
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.AmSite = api;
  if (typeof document === "undefined") return;

  function el(tag, attrs, kids) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) {
      if (key === "text") node.textContent = attrs[key];
      else node.setAttribute(key, attrs[key]);
    });
    (kids || []).forEach(function (kid) { node.appendChild(typeof kid === "string" ? document.createTextNode(kid) : kid); });
    return node;
  }

  function loadDevices() {
    return fetch("data/devices.json").then(function (r) { return r.ok ? r.json() : []; }).catch(function () { return []; });
  }

  function fillRecommendation(box, env, devices, rec) {
    var device = devices[0] || null;
    var link = function (id) { var a = document.getElementById(id); return a ? a.getAttribute("href") : "#all"; };
    var version = box.getAttribute("data-version");
    var name = devices.length ? deviceNames(devices) : null;
    var seen = name ? "We think this is a " + name + (env.android ? " running Android " + env.android : "") + "." : (env.android ? "Your phone seems to run Android " + env.android + "." : "");
    var kids = [];
    var heading;
    var actions = [];
    var verdictInfo = device ? verdict(device) : null;
    switch (rec.kind) {
      case "latest":
        heading = "AbsentMotion " + version + " should work on your phone";
        kids.push(el("p", { text: seen + " The Latest version needs Android 13 or newer, and it should run on it." }));
        actions.push(el("a", { "class": "btn big", href: link("dl-foss") }, ["Download for your phone", el("span", { "class": "sub", text: "Latest, Android 13 or newer, " + version })]));
        kids.push(el("p", { "class": "small" }, ["Prefer Google Play services for background removal? ", el("a", { href: link("dl-play"), text: "Download the Play-services build" }), ". The two files have the same features; the first has no Google libraries and removes backgrounds with its own offline model."]));
        if (verdictInfo && verdictInfo.ai) kids.push(el("p", { "class": "small", text: verdictInfo.ai }));
        break;
      case "compatible":
        heading = "Get the Compatible version";
        kids.push(el("p", { text: seen + " Android 9 to 12 uses the Compatible version." }));
        actions.push(el("a", { "class": "btn big", href: link("dl-compatible") }, ["Download Compatible version", el("span", { "class": "sub", text: "Android 9 to 12, " + version })]));
        kids.push(el("p", { "class": "small" }, ["Some features are left out of this version. ", el("a", { href: "compatible.html", text: "See which ones" }), ". If the phone is updated to Android 13 or newer later, the Latest version is the one to use."]));
        if (verdictInfo && verdictInfo.ai) kids.push(el("p", { "class": "small", text: verdictInfo.ai }));
        break;
      case "old":
        heading = "This phone is too old for AbsentMotion";
        kids.push(el("p", { text: "Your phone runs Android " + env.android + ". AbsentMotion needs Android 9 or newer (Android 13 or newer for the Latest version)." }));
        kids.push(el("p", {}, ["If you have another phone, ", el("a", { href: "phone-checker.html", text: "type its model in the phone checker" }), " or pick a download from the list below."]));
        break;
      case "blocked":
        heading = "This phone cannot run AbsentMotion";
        kids.push(el("p", { text: (name ? name + ": " : "") + rec.reason.charAt(0).toUpperCase() + rec.reason.slice(1) + "." }));
        kids.push(el("p", {}, ["Other phones: ", el("a", { href: "phone-checker.html", text: "phone checker" }), "."]));
        break;
      case "unknown":
        heading = "Which Android version do you have?";
        kids.push(el("p", { text: (name ? "We think this is a " + name + ", but " : "") + "your browser does not tell us the Android version. Open Settings, About phone, Android version." }));
        actions.push(el("a", { "class": "btn", href: link("dl-foss"), text: "Android 13 or newer: Latest" }));
        actions.push(el("a", { "class": "btn alt", href: link("dl-compatible"), text: "Android 9 to 12: Compatible" }));
        kids.push(el("p", { "class": "small" }, ["Not sure it will run? ", el("a", { href: "phone-checker.html", text: "Use the phone checker" }), "."]));
        break;
      case "ios":
        heading = "AbsentMotion is an Android app";
        kids.push(el("p", { text: "It cannot be installed on an iPhone or iPad. Open this page on an Android phone to download it." }));
        break;
      default:
        heading = "Open this page on your Android phone";
        kids.push(el("p", { text: "AbsentMotion is an Android app, and this looks like a computer. On your phone, this page will pick the right file for it. You can also see every download below or check a phone model." }));
        var copy = el("button", { "class": "btn", type: "button", text: "Copy this page's link" });
        copy.addEventListener("click", function () {
          var done = function () { copy.textContent = "Link copied"; };
          if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(location.href.split("#")[0]).then(done, function () { copy.textContent = "Copy it from the address bar"; });
          else copy.textContent = "Copy it from the address bar";
        });
        actions.push(copy);
        actions.push(el("a", { "class": "btn alt", href: "phone-checker.html", text: "Phone checker" }));
    }
    box.textContent = "";
    box.appendChild(el("h2", { text: heading }));
    kids.slice(0, 1).forEach(function (k) { box.appendChild(k); });
    if (actions.length) box.appendChild(el("div", { "class": "actions" }, actions));
    kids.slice(1).forEach(function (k) { box.appendChild(k); });
  }

  function initDownload() {
    var box = document.getElementById("rec");
    if (!box) return;
    detect(navigator).then(function (env) {
      if (!env.model) return { env: env, devices: [] };
      return loadDevices().then(function (devices) {
        return { env: env, devices: findByModelCode(buildIndex(devices), env.model) };
      });
    }).then(function (found) {
      fillRecommendation(box, found.env, found.devices, recommend(found.env, found.devices[0] || null));
    }).catch(function () {
      fillRecommendation(box, { platform: "android", android: null, model: null }, [], { kind: "unknown" });
    });
  }

  function resultCard(result) {
    var d = result.device;
    var v = verdict(d);
    var card = el("article", { "class": "card" }, [
      el("h3", {}, [deviceNames([d]) + " ", el("span", { "class": "tier " + v.tier, text: TIER_NAMES[v.tier] })]),
      el("p", { text: v.reason })
    ]);
    if (v.ai) card.appendChild(el("p", { text: v.ai }));
    if (d.notes) card.appendChild(el("p", { "class": "small", text: d.notes }));
    if (d.confidence === "low") card.appendChild(el("p", { "class": "small muted", text: "Less certain: we have little information about this model, so treat this as a guess and check Settings, About phone, Android version." }));
    else if (d.confidence === "medium") card.appendChild(el("p", { "class": "small muted", text: "Less certain: this answer comes from the maker's usual update pattern, so it may be off by one version." }));
    return card;
  }

  function initChecker() {
    var input = document.getElementById("q");
    var box = document.getElementById("results");
    if (!input || !box) return;
    var index = null;
    var timer = 0;
    var hint = function (text) { box.textContent = ""; box.appendChild(el("p", { "class": "muted", text: text })); };
    var generic = function () {
      box.appendChild(el("div", { "class": "card" }, [
        el("h3", { text: "We do not know this model yet" }),
        el("p", { text: "Open Settings, About phone, Android version on your phone. Android 13 or newer: use the Latest version. Android 9 to 12: use the Compatible version. Android 8 or older cannot run AbsentMotion." }),
        el("p", { text: "The phone also needs a 64-bit ARM chip and Vulkan 1.1. Budget phones sold as Android Go edition usually have a 32-bit system and cannot run it." })
      ]));
    };
    var run = function () {
      var query = input.value.trim();
      if (!index) return;
      if (norm(query).length < 2) { hint("Type at least two characters of your phone's name or model code."); return; }
      var results = search(index, query, 6);
      box.textContent = "";
      if (!results.length) { generic(); return; }
      results.forEach(function (r) { box.appendChild(resultCard(r)); });
      if (results[0].score < 90) box.appendChild(el("p", { "class": "small muted", text: "Not the phone you meant? Add more of its name, or its model code from Settings, About phone." }));
    };
    hint("Loading the phone list" + String.fromCharCode(8230));
    loadDevices().then(function (devices) {
      index = buildIndex(devices);
      hint(devices.length ? "Type your phone's name or model code above." : "The phone list could not be loaded. Use the list of brands below.");
      if (input.value) run();
    });
    input.addEventListener("input", function () { clearTimeout(timer); timer = setTimeout(run, 120); });
    document.getElementById("checker-form").addEventListener("submit", function (event) { event.preventDefault(); run(); });
  }

  function initEffects() {
    var tools = document.getElementById("fx-tools");
    if (!tools) return;
    tools.hidden = false;
    var jump = document.querySelector(".jump");
    if (jump) jump.hidden = true;
    var sections = [].slice.call(document.querySelectorAll(".fx-cat"));
    var chips = document.getElementById("fx-chips");
    var input = document.getElementById("fxq");
    var count = document.getElementById("fx-count");
    var category = "all";
    var total = document.querySelectorAll(".fx-cat li").length;
    var buttons = [["all", "All " + total]].concat(sections.map(function (s) { return [s.getAttribute("data-cat"), s.querySelector("h2").firstChild.textContent.trim()]; }));
    buttons.forEach(function (pair) {
      var button = el("button", { type: "button", "aria-pressed": pair[0] === "all" ? "true" : "false", "data-cat": pair[0], text: pair[1] });
      button.addEventListener("click", function () { category = pair[0]; apply(); });
      chips.appendChild(button);
    });
    function apply() {
      var words = norm(input.value).split(" ").filter(Boolean);
      var shown = 0;
      sections.forEach(function (section) {
        var visible = 0;
        [].forEach.call(section.querySelectorAll("li"), function (li) {
          var text = norm(li.getAttribute("data-text"));
          var match = (category === "all" || category === section.getAttribute("data-cat")) && words.every(function (w) { return text.indexOf(w) >= 0; });
          li.hidden = !match;
          if (match) visible++;
        });
        section.hidden = visible === 0;
        shown += visible;
      });
      [].forEach.call(chips.children, function (b) { b.setAttribute("aria-pressed", b.getAttribute("data-cat") === category ? "true" : "false"); });
      count.textContent = shown === total ? "" : shown + " of " + total + " effects";
      if (!shown) count.textContent = "No effect matches. Try a shorter word.";
    }
    input.addEventListener("input", apply);
  }

  document.documentElement.classList.add("js");
  var page = document.body && document.body.getAttribute("data-page");
  if (page === "download") initDownload();
  else if (page === "checker") initChecker();
  else if (page === "effects") initEffects();
})(typeof window !== "undefined" ? window : globalThis);
