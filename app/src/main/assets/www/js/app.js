(function () {
  'use strict';
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var svg = Icons.svg, Spring = Motion.Spring;
  var store = {
    get: function (k, d) { try { var v = localStorage.getItem('ibz.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('ibz.' + k, JSON.stringify(v)); } catch (e) {} Bridge.setPref(k, JSON.stringify(v)); }
  };
  var CFG = window.IBZ_CONFIG || { frames: 48, topBox: null };

  /* ======================= matériau verre ======================= */
  var root = document.documentElement;
  var lite = false;
  function applyLite() { lite = !!store.get('lite', false); root.classList.toggle('lite', lite); if (typeof drawAngle === 'function' && typeof angle !== 'undefined' && angle) { drawn.i = -1; drawAngle(angle.x); } }
  /* Mise à l'échelle : maquette pensée en 1024×600, adaptée à toute taille d'écran */
  var SC = 1, VW = 1024, VH = 600, PER_PAGE = 18;
  /* Taille de l'interface : 1 = maquette d'origine ; 1,12 « Grand » (défaut v1.5) ; 1,25 « Très grand » */
  var ZOOMS = { normal: 1, grand: 1.12, xl: 1.25 };
  function fit() {
    var w = window.innerWidth, h = window.innerHeight, portrait = h > w * 1.05;
    var Z = ZOOMS[store.get('zoom', 'grand')] || 1.12;
    if (portrait) { SC = w / (600 / Z); }
    else { SC = Math.max(0.6, h / (600 / Z)); if (w / SC < 860) SC = w / 860; }
    VW = Math.round(w / SC); VH = Math.round(h / SC);
    var app = $('#app'); app.style.width = VW + 'px'; app.style.height = VH + 'px'; app.style.transform = 'scale(' + SC + ')';
    root.classList.toggle('portrait', portrait);
    root.classList.toggle('wide', !portrait && VW > 1400);
    PER_PAGE = portrait ? 20 : VW > 1400 ? 24 : 18;
  }
  function alignGlass() {
    root.style.setProperty('--vw', VW + 'px');
    root.style.setProperty('--vh', VH + 'px');
    $$('.glass').forEach(function (el) {
      var r = el.getBoundingClientRect();
      el.style.setProperty('--gx', (-r.left / SC) + 'px');
      el.style.setProperty('--gy', (-r.top / SC) + 'px');
    });
  }
  function applyGlassMode() {
    var m = store.get('glass', 'sim');
    document.body.classList.toggle('real-blur', m === 'real');
    document.body.classList.toggle('no-glass', m === 'off');
  }

  /* ======================= thème (sombre / clair / auto) ======================= */
  var isLight = false, sig = { lights: null, night: null };
  function applyTheme() {
    var t = store.get('theme', 'auto'), light;
    if (t === 'light') light = true;
    else if (t === 'dark') light = false;
    else if (sig.lights !== null) light = !sig.lights;          // phares allumés -> sombre
    else if (sig.night !== null) light = !sig.night;            // mode nuit Android
    else { var h = new Date().getHours(); light = h >= 7 && h < 20; }
    if (light === isLight && document.documentElement.classList.contains('themed')) return;
    isLight = light;
    root.classList.add('themed'); root.classList.toggle('light', light);
    wallNow = ''; applyWall();
  }
  Bridge.on('night', function (d) { sig.night = !!(d && d.night); applyTheme(); });
  Bridge.on('lights', function (d) { sig.lights = !!(d && d.on); applyTheme(); });

  /* ======================= fond d'écran ======================= */
  var WALLS = [['match', 'Suit le thème'], ['dark', 'Sombre'], ['light', 'Clair'], ['day', 'Jour'], ['evening', 'Soir'], ['sunset', 'Couchant'], ['night', 'Nuit']];
  function wallFor(sel) {
    if (sel === 'match') return isLight ? 'light' : 'dark';
    if (sel === 'auto') { var h = new Date().getHours(); return h >= 7 && h < 17 ? 'day' : h >= 17 && h < 19 ? 'evening' : h >= 19 && h < 21 ? 'sunset' : 'night'; }
    return sel;
  }
  var wallNow = '';
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return u; } }
  function applyWall() {
    var sel = store.get('wall', 'match'), w = wallFor(sel), a, b;
    if (sel === 'custom') {
      try { a = localStorage.getItem('ibz.wallimg'); b = localStorage.getItem('ibz.wallblur'); } catch (e) {}
      if (!a) { sel = 'dark'; w = 'dark'; }
    }
    var key = sel === 'custom' ? 'custom' : w;
    if (key === wallNow) return; wallNow = key;
    measureWall(key === 'custom' ? b : abs('img/walls/' + w + '_t.jpg'));
    if (key === 'custom') { a = 'url(' + a + ')'; b = 'url(' + b + ')'; }
    else { a = 'url(' + abs('img/walls/' + w + '.jpg') + ')'; b = 'url(' + abs('img/walls/' + w + '_blur.jpg') + ')'; }
    root.style.setProperty('--wall', a); root.style.setProperty('--wall-blur', b);
  }
  /* fond clair (gris, blanc) -> libellés d'apps et points de page en sombre */
  function measureWall(url) {
    var im = new Image();
    im.onload = function () {
      try {
        var c = document.createElement('canvas'); c.width = 24; c.height = 14;
        var x = c.getContext('2d'); x.drawImage(im, 0, 0, 24, 14);
        var d = x.getImageData(0, 0, 24, 14).data, s = 0;
        for (var i = 0; i < d.length; i += 4) s += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
        root.classList.toggle('wall-bright', s / (d.length / 4) > 125);
      } catch (e) { root.classList.remove('wall-bright'); }
    };
    im.src = url;
  }
  function wallSheet() {
    var sel = store.get('wall', 'match');
    var h = WALLS.map(function (w) { var th = w[0] === 'match' ? 'dark' : w[0]; return '<div class="wthumb press' + (sel === w[0] ? ' on' : '') + '" data-w="' + w[0] + '" style="background-image:' + (w[0] === 'match' ? 'linear-gradient(90deg,rgba(0,0,0,0) 50%,rgba(255,255,255,.0) 50%),url(img/walls/light_t.jpg),url(img/walls/dark_t.jpg)' : 'url(img/walls/' + th + '_t.jpg)') + ';background-size:50% 100%,50% 100%,cover;background-position:0 0,100% 0,0 0;background-repeat:no-repeat"><span>' + w[1] + '</span></div>'; }).join('');
    h += '<div class="wthumb auto press' + (sel === 'auto' ? ' on' : '') + '" data-w="auto"><span>Selon l\'heure</span></div>';
    h += '<div class="wthumb usb press' + (sel === 'custom' ? ' on' : '') + '" data-w="usb">' + svg('files') + '<span>Clé USB…</span></div>';
    openSheet('<div class="t-headline">Fond d\'écran</div><div class="t-sub">« Selon l\'heure » passe du jour à la nuit tout seul.</div><div class="walls">' + h + '</div>', function (s) {
      $$('[data-w]', s).forEach(function (el) {
        tappable(el, function () {
          var w = el.getAttribute('data-w');
          if (w === 'usb') { closeSheet(); $('#wall-file').click(); return; }
          store.set('wall', w); applyWall(); closeSheet(); if (current === 'settings') renderSettings();
        });
      });
    });
  }
  function loadCustomWall(file) {
    var rd = new FileReader();
    rd.onload = function () {
      var img = new Image();
      img.onload = function () {
        var W = 1024, H = 600, c = document.createElement('canvas'); c.width = W; c.height = H;
        var x = c.getContext('2d'), sc = Math.max(W / img.width, H / img.height), w = img.width * sc, h = img.height * sc;
        x.drawImage(img, (W - w) / 2, (H - h) / 2, w, h);
        var full = c.toDataURL('image/jpeg', 0.86);
        var b = document.createElement('canvas'); b.width = W; b.height = H; var bx = b.getContext('2d');
        if ('filter' in bx) { bx.filter = 'blur(28px)'; bx.drawImage(c, -40, -40, W + 80, H + 80); }
        else { var t = document.createElement('canvas'); t.width = 48; t.height = 28; t.getContext('2d').drawImage(c, 0, 0, 48, 28); bx.imageSmoothingEnabled = true; bx.drawImage(t, 0, 0, W, H); }
        try { localStorage.setItem('ibz.wallimg', full); localStorage.setItem('ibz.wallblur', b.toDataURL('image/jpeg', 0.8)); }
        catch (e) { toast('Image trop lourde'); return; }
        store.set('wall', 'custom'); wallNow = ''; applyWall(); toast('Fond d\'écran appliqué');
      };
      img.src = rd.result;
    };
    rd.readAsDataURL(file);
  }

  /* ======================= appui instantané ======================= */
  document.addEventListener('pointerdown', function (e) {
    var p = e.target.closest ? e.target.closest('.press, .press-soft, .app, .dock-slot, .row.btn, .qbtn') : null;
    if (!p) return;
    p.classList.add('down');
    var up = function () { p.classList.remove('down'); document.removeEventListener('pointerup', up); document.removeEventListener('pointercancel', up); };
    document.addEventListener('pointerup', up); document.addEventListener('pointercancel', up);
  }, true);

  /* tap = appui puis relâche sans déplacement (> 10 px annule) ; appui long = 550 ms */
  function tappable(el, onTap, onLong) {
    var sx, sy, t, moved, longFired, down = false;
    el.addEventListener('pointerdown', function (e) {
      sx = e.clientX; sy = e.clientY; moved = false; longFired = false; down = true;
      clearTimeout(t);
      if (onLong) t = setTimeout(function () { if (!moved) { longFired = true; el.classList.remove('down'); onLong(e); } }, 550);
    });
    el.addEventListener('pointermove', function (e) {
      if (Math.abs(e.clientX - sx) > 10 || Math.abs(e.clientY - sy) > 10) { moved = true; clearTimeout(t); }
    });
    el.addEventListener('pointerup', function (e) { clearTimeout(t); var ok = down && !moved && !longFired; down = false; if (ok) onTap(e); });
    el.addEventListener('pointercancel', function () { clearTimeout(t); moved = true; down = false; });
    el.addEventListener('pointerleave', function () { down = false; clearTimeout(t); });
  }

  /* ======================= horloge ======================= */
  var JOURS = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi'];
  var MOIS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function tick() {
    var d = new Date(), t = pad(d.getHours()) + ':' + pad(d.getMinutes());
    $('#rail-time').textContent = t; $('#c-time').textContent = t;
    $('#c-date').textContent = JOURS[d.getDay()] + ' ' + d.getDate() + ' ' + MOIS[d.getMonth()];
  }

  /* ======================= apps & dock ======================= */
  var apps = [], appByKey = {};
  var VIRTUAL = [
    { pkg: ':vehicle', cls: '', label: 'Véhicule', theme: 'car', virtual: 'vehicle' },
    { pkg: ':settings', cls: '', label: 'Réglages lanceur', theme: 'launcher', virtual: 'settings' }
  ];
  function key(a) { return a.pkg + '/' + a.cls; }
  function loadApps() {
    var list = Bridge.apps() || [];
    list.forEach(function (a) { a.theme = a.custom ? null : Icons.themeFor(a); });
    list.sort(function (a, b) { return a.label.localeCompare(b.label, 'fr'); });
    apps = VIRTUAL.concat(list);
    appByKey = {}; apps.forEach(function (a) { appByKey[key(a)] = a; });
  }
  var barAsked = false;
  var WANT = { nav: { pkg: 'com.waze', name: 'Waze' }, music: { pkg: 'com.spotify.music', name: 'Spotify' } };
  function missingSheet(w, a) {
    openSheet('<div class="t-headline">' + w.name + ' n\'est pas installé</div>' +
      '<div class="t-sub">Le lanceur ouvre « ' + esc(a.label || '') + ' » à la place. Installe ' + w.name + ' depuis le Play Store (la tablette doit être connectée à Internet). ' + (w.pkg === 'com.waze' ? 'Si le Play Store dit que l\'appareil n\'est pas compatible, installe plutôt Google Maps.' : '') + '</div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-a="no">Ouvrir ' + esc(a.label || 'quand même') + '</button><button class="sbtn primary press" data-a="ok">Installer ' + w.name + '</button></div>', function (s) {
      $('[data-a="no"]', s).onclick = function () { closeSheet(); store.set('skipWant_' + w.pkg, true); openApp(a); };
      $('[data-a="ok"]', s).onclick = function () { closeSheet(); Bridge.openStore(w.pkg); };
    });
  }
  function openApp(a, originEl) {
    if (a.virtual) { showPage(a.virtual, originEl); return; }
    /* v2.4 : Waze / Spotify absents : on propose de les installer au lieu d'ouvrir « GPS Test » ou le lecteur du poste */
    var w = WANT[a.theme];
    var isPref = (PREFER[a.theme] || []).some(function (x) { return String(a.pkg).indexOf(x) === 0; });   // Google Maps, Deezer… suffisent
    if (w && !isPref && !roleApp(a.theme) && !store.get('skipWant_' + w.pkg, false) && !Bridge.installed(w.pkg)) { missingSheet(w, a); return; }
    /* v2.3 : sans l'autorisation « affichage par-dessus », la barre ne peut pas rester sur Waze / Spotify */
    var info = store.get('bar', true) && !barAsked ? Bridge.barInfo() : null;
    if (info && !info.demo && info.overlay === false) {
      barAsked = true;
      openSheet('<div class="t-headline">Garder la barre sur ' + esc(a.label || 'l\'appli') + ' ?</div>' +
        '<div class="t-sub">Pour que la barre de gauche reste visible quand l\'appli est ouverte, Android doit autoriser Car Launcher à s\'afficher par-dessus les autres applis. ' +
        'Dans l\'écran qui va s\'ouvrir, choisis Car Launcher et active l\'autorisation, puis reviens avec la touche Retour.</div>' +
        '<div class="sheet-actions"><button class="sbtn press" data-a="no">Ouvrir sans la barre</button><button class="sbtn primary press" data-a="ok">Autoriser</button></div>', function (s) {
        $('[data-a="no"]', s).onclick = function () { closeSheet(); Bridge.launch(a); };
        $('[data-a="ok"]', s).onclick = function () { closeSheet(); act('sys:overlay'); };
      });
      return;
    }
    Bridge.launch(a);
  }
  function defaultDock() {
    var want = ['nav', 'music', 'phone', 'link'], out = [];
    want.forEach(function (th) {
      var f = null;
      var ra = roleApp(th); if (ra && out.indexOf(key(ra)) < 0) f = ra;
      if (!f) { var best = findTheme(th); if (best && out.indexOf(key(best)) < 0) f = best; }
      if (!f) for (var i = 0; i < apps.length; i++) if (apps[i].theme === th && !apps[i].virtual && out.indexOf(key(apps[i])) < 0) { f = apps[i]; break; }
      out.push(f ? key(f) : null);
    });
    return out;
  }
  function renderDock() {
    var dock = store.get('dock', null);
    if (!dock || dock.length !== 4) { dock = defaultDock(); store.set('dock', dock); }
    if (!store.get('dockfix15', false)) { // v1.5 : le gestionnaire de fichiers avait été pris pour la musique
      var mus = findTheme('music');
      dock = dock.map(function (k) { var a = k && appByKey[k]; return a && a.theme === 'files' && mus ? key(mus) : k; });
      store.set('dock', dock); store.set('dockfix15', true);
    }
    if (!store.get('dockfix23', false)) { // v2.3 : « GPS Test » ou le lecteur du poste avaient été pris à la place de Waze / Spotify
      dock = dock.map(function (k) {
        var a = k && appByKey[k]; if (!a || (a.theme !== 'nav' && a.theme !== 'music')) return k;
        var best = findTheme(a.theme); return best && !best.virtual ? key(best) : k;
      });
      store.set('dock', dock); store.set('dockfix23', true);
    }
    var el = $('#dock'); el.innerHTML = '';
    dock.forEach(function (k, i) {
      var a = k ? appByKey[k] : null;
      var slot = document.createElement('div'); slot.className = 'dock-slot press';
      slot.innerHTML = a ? Icons.appIcon(a, 60) : '<div class="dock-empty">' + svg('plus') + '</div>';
      tappable(slot, function () { if (a) openApp(a, slot); else { showPage('apps', $('#btn-pages')); toast('Appui long sur une app pour la mettre dans le dock'); } },
        function () { dockSheet(i); });
      el.appendChild(slot);
    });
    /* v2.1 : la barre affichée par-dessus Waze, Spotify… reprend les applis du dock */
    Bridge.setBar(dock.map(function (k) { return k ? appByKey[k] : null; })
      .filter(function (a) { return a && !a.virtual; })
      .map(function (a) { return { pkg: a.pkg, cls: a.cls, label: a.label }; }));
  }
  function setDock(i, a) { var d = store.get('dock', defaultDock()); d[i] = a ? key(a) : null; store.set('dock', d); renderDock(); alignGlass(); }

  /* ======================= feuilles (menus) ======================= */
  var sheetSpring = new Spring(0, { onUpdate: function (v) {
    var s = $('#sheet'); s.style.opacity = Math.max(0, Math.min(1, v));
    s.style.transform = 'translateY(-50%) scale(' + (0.92 + 0.08 * v) + ')';
    s.style.visibility = v > 0.01 ? 'visible' : 'hidden';
  } });
  function openSheet(html, bind) {
    var s = $('#sheet'); s.classList.remove('sheet-ready'); s.innerHTML = html; if (bind) bind(s);
    $('#sheet-scrim').classList.add('on'); alignGlass();
    sheetSpring.to(1, { response: 0.32, damping: 1 });
  }
  function closeSheet() { $('#sheet').style.top = ''; if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); $('#sheet-scrim').classList.remove('on'); sheetSpring.to(0, { response: 0.25, damping: 1 }); }
  $('#sheet-scrim').addEventListener('click', closeSheet);
  function appSheet(a) {
    var btns = '';
    for (var i = 0; i < 4; i++) btns += '<button class="sbtn press" data-slot="' + i + '">Dock · place ' + (i + 1) + '</button>';
    openSheet('<div class="t-headline">' + esc(a.label) + '</div><div class="t-sub">Choisis où l\'épingler dans la barre latérale.</div>' +
      '<div class="t-cap" style="margin-top:6px">Icône perso : icons/' + esc(a.pkg) + '.png</div>' +
      '<div class="sheet-actions">' + btns + '<button class="sbtn wide press" data-open="1">Ouvrir</button></div>', function (s) {
      $$('[data-slot]', s).forEach(function (b) { b.onclick = function () { setDock(+b.getAttribute('data-slot'), a); closeSheet(); toast('Ajouté au dock'); }; });
      $('[data-open]', s).onclick = function () { closeSheet(); openApp(a); };
    });
  }
  function dockSheet(i) {
    openSheet('<div class="t-headline">Place ' + (i + 1) + ' du dock</div><div class="t-sub">Pour changer d\'app : appui long sur une app dans la bibliothèque.</div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-lib="1">Bibliothèque</button><button class="sbtn danger press" data-clear="1">Retirer</button></div>', function (s) {
      $('[data-lib]', s).onclick = function () { closeSheet(); showPage('apps', $('#btn-pages')); };
      $('[data-clear]', s).onclick = function () { setDock(i, null); closeSheet(); };
    });
  }
  function esc(t) { return String(t).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  /* ======================= toast ======================= */
  var toastT;
  function toast(t) { var el = $('#toast'); el.textContent = t; alignGlass(); el.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(function () { el.classList.remove('on'); }, 2400); }
  Bridge.on('toast', toast);

  /* ======================= navigation entre pages ======================= */
  var current = 'home';
  var pageSprings = {};
  function pageEl(n) { return $('#page-' + n); }
  function showPage(name, originEl) {
    if (name === current) return;
    var from = pageEl(current), to = pageEl(name);
    var st = $('#stage').getBoundingClientRect();
    var ox = '50%', oy = '50%';
    if (originEl) { var r = originEl.getBoundingClientRect(); ox = ((r.left + r.width / 2 - st.left) / SC) + 'px'; oy = ((r.top + r.height / 2 - st.top) / SC) + 'px'; }
    to.style.transformOrigin = ox + ' ' + oy; from.style.transformOrigin = ox + ' ' + oy;
    animatePage(from, 0, 1.04); animatePage(to, 1, 0.9);
    var leaving = current;
    current = name;
    if (dashOn() && dashFree()) {
      if (name !== 'home' && leaving === 'home') { stayPage = true; Bridge.front(); }
      if (name === 'home') setTimeout(function () { tileDash(); }, 450);
    }
    $('#btn-pages').innerHTML = svg(name === 'home' ? 'grid' : 'dash');
    if (name === 'vehicle' || name === 'home') hideLive(true);
    if (name === 'vehicle') renderVehicle();
    if (name === 'settings') renderSettings();
    if (name === 'apps') setAppsPage(appsPageIdx, 0, true);
  }
  function animatePage(el, target, startScale) {
    var id = el.id, sp = pageSprings[id];
    if (!sp) {
      sp = pageSprings[id] = new Spring(el.classList.contains('is-active') ? 1 : 0, { onUpdate: function (v, s) {
        var sc = s.dir ? (s.from + (1 - s.from) * v) : (1 + (s.from - 1) * (1 - v));
        el.style.opacity = Math.max(0, Math.min(1, v));
        el.style.transform = 'scale(' + sc + ')';
        el.style.visibility = v > 0.005 ? 'visible' : 'hidden';
        el.style.pointerEvents = v > 0.5 ? 'auto' : 'none';
      } });
    }
    sp.dir = target === 1; sp.from = startScale;
    el.classList.toggle('is-active', target === 1);
    el.classList.add('anim');
    if (lite) { sp.set(target); el.style.transform = ''; el.classList.remove('anim'); if (target === 1) alignGlass(); return; }
    sp.to(target, { response: 0.38, damping: 1, onRest: function () { el.classList.remove('anim'); if (target === 1) { el.style.transform = ''; alignGlass(); } } });
  }
  tappable($('#btn-pages'), function () { showPage(current === 'home' ? 'apps' : 'home', $('#btn-pages')); });

  /* ======================= bibliothèque (pages + élan) ======================= */
  var appsPageIdx = 0, appsPages = 1, trackX = new Spring(0, { onUpdate: function (v) { $('#apps-track').style.transform = 'translate3d(' + v + 'px,0,0)'; } });
  function renderApps() {
    var per = PER_PAGE, vp = $('#apps-viewport'), w = vp.clientWidth || 900;
    var list = apps; appsPages = Math.max(1, Math.ceil(list.length / per));
    var html = '';
    for (var p = 0; p < appsPages; p++) {
      html += '<div class="apps-page" style="width:' + w + 'px">';
      list.slice(p * per, p * per + per).forEach(function (a, i) {
        html += '<div class="app" data-i="' + (p * per + i) + '">' + Icons.appIcon(a, 76) + '<div class="app-l">' + esc(a.label) + '</div></div>';
      });
      html += '</div>';
    }
    $('#apps-track').innerHTML = html;
    $$('.app', $('#apps-track')).forEach(function (el) {
      var a = list[+el.getAttribute('data-i')];
      tappable(el, function () { if (!dragging) openApp(a, el); }, function () { if (!a.virtual) appSheet(a); });
    });
    var dots = ''; for (var d = 0; d < appsPages; d++) dots += '<i></i>';
    $('#apps-dots').innerHTML = appsPages > 1 ? dots : '';
    setAppsPage(Math.min(appsPageIdx, appsPages - 1), 0, true);
  }
  function setAppsPage(i, vel, instant) {
    appsPageIdx = i;
    var w = $('#apps-viewport').clientWidth;
    if (instant) trackX.set(-i * w); else trackX.to(-i * w, { response: 0.4, damping: vel ? 0.86 : 1, velocity: vel || 0 });
    $$('#apps-dots i').forEach(function (d, k) { d.classList.toggle('on', k === i); });
  }
  var dragging = false;
  (function () {
    var vp = $('#apps-viewport'), tr = new Motion.Tracker(), sx = 0, sy = 0, base = 0, locked = null, id = null;
    vp.addEventListener('pointerdown', function (e) { id = e.pointerId; sx = e.clientX; sy = e.clientY; base = trackX.x; trackX.stop(); locked = null; dragging = false; tr = new Motion.Tracker(); tr.add(e.clientX / SC, 0); });
    vp.addEventListener('pointermove', function (e) {
      if (e.pointerId !== id) return;
      var dx = (e.clientX - sx) / SC, dy = (e.clientY - sy) / SC;
      if (locked === null && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) { locked = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y'; if (locked === 'x') { dragging = true; try { vp.setPointerCapture(id); } catch (er) {} } }
      if (locked !== 'x') return;
      tr.add(e.clientX / SC, 0);
      var w = vp.clientWidth, min = -(appsPages - 1) * w, x = base + dx;
      if (x > 0) x = Motion.rubber(x, w); else if (x < min) x = min - Motion.rubber(min - x, w);
      trackX.set(x);
    });
    function end(e) {
      if (e.pointerId !== id) return; id = null;
      if (locked !== 'x') return;
      var w = vp.clientWidth, v = tr.velocity().x;
      var projected = trackX.x + Motion.project(v, 0.99);
      var i = Math.round(-projected / w); i = Math.max(appsPageIdx - 1, Math.min(appsPageIdx + 1, i)); i = Math.max(0, Math.min(appsPages - 1, i));
      setAppsPage(i, v);
      setTimeout(function () { dragging = false; }, 50);
    }
    vp.addEventListener('pointerup', end); vp.addEventListener('pointercancel', end);
  })();

  /* ======================= plateau tournant 3D ======================= */
  /* Les 48 vues sont décodées UNE fois en mémoire (ImageBitmap) puis dessinées dans un <canvas>.
     Avant : on changeait le src de deux <img> à chaque cran de 7,5° -> le WebView redécodait le WebP
     au moment de l'affichage (à-coup), et le fondu « B par-dessus A opaque » laissait dépasser la
     silhouette de l'ancienne vue jusqu'au changement de cran (petit saut visible à chaque cran). */
  var N = CFG.frames, STEP = 360 / N, bitmaps = new Array(N), loaded = 0;
  var ttCanvas = $('#tt-canvas'), ttCtx = ttCanvas.getContext('2d');
  var drawn = { i: -1, j: -1, t: -1 };
  /* v1.16 : véhicule configurable (js/config.js -> car) ; sans pack 3D, la carte affiche un message au lieu de la voiture */
  var CAR = CFG.car || { name: 'Ma voiture', model: 'Ma voiture', fuel: 'essence' };
  /* sans boîtier CAN : portes, radars et lecture de l'appli CAN sont coupés, l'ordinateur de bord passe par le GPS */
  var NOCAN = CFG.can === false;
  document.title = CAR.name;
  $$('.car-name').forEach(function (e) { e.textContent = CAR.name; });
  $$('.car-model').forEach(function (e) { e.textContent = CAR.model; });
  if (CFG.carPack === false) root.classList.add('nocar');
  if (NOCAN) root.classList.add('nocan');
  if (CFG.topView === false) root.classList.add('notop');
  function loadFrame(f) {
    if (CFG.carPack === false) return;
    var im = new Image();
    im.src = 'img/car/hero_' + pad(f) + '.webp';
    var done = function () {
      loaded++;
      if (loaded === 1 && im.naturalWidth && (ttCanvas.width !== im.naturalWidth || ttCanvas.height !== im.naturalHeight)) { ttCanvas.width = im.naturalWidth; ttCanvas.height = im.naturalHeight; }
      drawn.i = -1; drawAngle(angle.x);
    };
    var keep = function () {
      if (window.createImageBitmap) {
        createImageBitmap(im).then(function (bm) { bitmaps[f] = bm; done(); }, function () { bitmaps[f] = im; done(); });
      } else { bitmaps[f] = im; done(); }
    };
    if (im.decode) im.decode().then(keep, function () { im.onload = keep; if (im.complete && im.naturalWidth) keep(); });
    else { im.onload = keep; }
  }
  ttCanvas.width = 600; ttCanvas.height = 335;
  /* v1.12 : position par défaut = 3/4 avant droit (vue 8) — au démarrage, au réveil et quand tout se referme */
  var HOME = (CFG.homeView != null ? CFG.homeView : 8) * STEP;
  function homeTarget() { return angle.x + ((((HOME - angle.x) % 360) + 540) % 360 - 180); }
  var angle = new Spring(HOME, { precision: 0.05, onUpdate: function (d) { drawAngle(d); } });
  // la vue courante d'abord, puis le reste en s'éloignant (le plateau s'affiche tout de suite)
  (function () {
    var c = Math.round((((angle.x / STEP) % N) + N) % N) % N, order = [c];
    for (var k = 1; k <= N / 2; k++) { order.push((c + k) % N); if (k < N / 2) order.push((c - k + N) % N); }
    order.forEach(loadFrame);
  })();
  function drawAngle(deg) {
    var p = ((deg / STEP) % N + N) % N, i = Math.floor(p) % N, j = (i + 1) % N, t = p - Math.floor(p);
    t = lite ? (t < 0.5 ? 0 : 1) : Math.round(t * 64) / 64;      // 64 niveaux de fondu suffisent
    if (t === 1) { i = j; j = (i + 1) % N; t = 0; }
    var A = bitmaps[i], B = bitmaps[j];
    if (!A && !B) return;
    if (!A) { A = B; t = 0; } else if (!B) { t = 0; }
    if (drawn.i === i && drawn.j === j && drawn.t === t) return; // rien n'a changé : pas de redessin
    drawn.i = i; drawn.j = j; drawn.t = t;
    var w = ttCanvas.width, h = ttCanvas.height;
    ttCtx.globalCompositeOperation = 'source-over'; ttCtx.globalAlpha = 1;
    ttCtx.clearRect(0, 0, w, h);
    if (t === 0) { ttCtx.drawImage(A, 0, 0, w, h); return; }
    // vrai fondu enchaîné : A·(1−t) + B·t (mode additif « lighter » en alpha prémultiplié)
    // -> ni silhouette fantôme qui saute au changement de cran, ni voiture qui devient translucide.
    ttCtx.globalAlpha = 1 - t; ttCtx.drawImage(A, 0, 0, w, h);
    ttCtx.globalCompositeOperation = 'lighter'; ttCtx.globalAlpha = t; ttCtx.drawImage(B, 0, 0, w, h);
    ttCtx.globalCompositeOperation = 'source-over'; ttCtx.globalAlpha = 1;
  }
  /* portes animées en 3D : séquences pré-rendues à l'angle qui montre le mieux chaque ouvrant */
  var DV = CFG.doorViews || {}, DSTEPS = CFG.doorSteps || 6, doorK = {}, doorImgs = {}, doorAnim = {};
  Object.keys(DV).forEach(function (d) {
    doorK[d] = 0;
    var im = document.createElement('img'); im.className = 'tt-img'; im.alt = ''; $('#tt-doors').appendChild(im); doorImgs[d] = im;
    for (var k = 1; k <= DSTEPS; k++) { var pre = new Image(); pre.src = 'img/car/door_' + d + '_' + k + '.webp'; }
    if (d === 'fl' || d === 'fr') { var pc = new Image(); pc.src = 'img/car/door_' + (d === 'fl' ? 'flrl' : 'frrr') + '_' + DSTEPS + '.webp'; }
  });
  function viewIndex() {
    var p = ((angle.x / STEP) % N + N) % N, i = Math.round(p);
    return Math.abs(p - i) < 0.04 ? i % N : -1;
  }
  /* 5 portes : les deux portes d'un même côté partagent une vue ; ouvertes ensemble -> image combinée */
  var PAIRS = [['fl', 'rl', 'flrl', 'L'], ['fr', 'rr', 'frrr', 'R']];
  /* v1.13 : à la vue de 3/4 arrière d'un côté, les deux portes de ce côté et le hayon forment un seul ensemble :
     un ouvrant s'anime pendant que les autres restent ouverts (images door_m<qui bouge>_s<ouverts>_<étape>). */
  var SIDEV = {}; SIDEV[DV.fl] = PAIRS[0]; SIDEV[DV.fr] = PAIRS[1];
  function sideFinal(p, S) {
    var a = S.indexOf(p[0]) >= 0, b = S.indexOf(p[1]) >= 0, t = S.indexOf('trunk') >= 0;
    if (!a && !b) return t ? 'door_trunk' + p[3] : null;
    var key = a && b ? p[2] : a ? p[0] : p[1];
    return t ? 'door_' + key + '_trunk' : 'door_' + key + '_' + DSTEPS;
  }
  function sideName(p) {
    var parts = [p[0], p[1], 'trunk'], mv = [], st = [];
    parts.forEach(function (d) { var k = doorK[d] || 0; if (k >= DSTEPS) st.push(d); else if (k > 0) mv.push(d); });
    if (!mv.length) return sideFinal(p, st);
    var m = mv[0];
    for (var i = 1; i < mv.length; i++) if (doorK[mv[i]] >= DSTEPS / 2) st.push(mv[i]);   // deux qui bougent : rare, on fige le second
    st = parts.filter(function (d) { return st.indexOf(d) >= 0; });
    if (!st.length) return m === 'trunk' ? null : 'door_' + m + '_' + doorK[m];
    return 'door_m' + m + '_s' + st.join('') + '_' + doorK[m];
  }
  function preloadCombos(d3) {   // les images qui pourront servir au prochain mouvement de ce côté
    PAIRS.forEach(function (p) {
      var parts = [p[0], p[1], 'trunk'], open = parts.filter(function (d) { return d3[d]; });
      if (!d3[p[0]] && !d3[p[1]]) return;
      parts.forEach(function (m) {
        var st = open.filter(function (d) { return d !== m; }); if (!st.length) return;
        for (var k = 1; k < DSTEPS; k++) { var im = new Image(); im.src = 'img/car/door_m' + m + '_s' + st.join('') + '_' + k + '.webp'; }
      });
    });
  }
  function drawDoors() {
    var vi = viewIndex(), src = {}, p = CFG.comboAnim ? SIDEV[vi] : null;
    if (p) {
      var nm = sideName(p);
      if (nm) src[p[0]] = 'img/car/' + nm + '.webp';
    } else {
      Object.keys(DV).forEach(function (d) { if (vi === DV[d] && doorK[d] > 0) src[d] = 'img/car/door_' + d + '_' + doorK[d] + '.webp'; });
      PAIRS.forEach(function (q) {
        if (src[q[0]] && src[q[1]] && doorK[q[0]] === DSTEPS && doorK[q[1]] === DSTEPS) { src[q[0]] = 'img/car/door_' + q[2] + '_' + DSTEPS + '.webp'; delete src[q[1]]; }
        else if (src[q[0]] && src[q[1]]) { if (doorK[q[0]] === DSTEPS) delete src[q[0]]; else delete src[q[1]]; }
      });
    }
    Object.keys(DV).forEach(function (d) {
      var im = doorImgs[d], show = !!src[d];
      if (show && im.getAttribute('src') !== src[d]) im.setAttribute('src', src[d]);
      im.classList.toggle('on', show);
    });
  }
  function rotateTo(v, cb) {
    var target = v * STEP, cur = angle.x, diff = ((target - cur) % 360 + 540) % 360 - 180;
    if (Math.abs(diff) < 0.5 || Motion.reduced) { angle.set(cur + diff); cb(); }
    else angle.to(cur + diff, { response: 0.7, damping: 1, onRest: cb });
  }
  function playDoor(d, to, done) {
    cancelAnimationFrame(doorAnim[d]);
    var from = doorK[d], t0 = performance.now(), dur = Motion.reduced ? 1 : 650;
    (function step(t) {
      var u = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - u, 3);
      doorK[d] = Math.round(from + (to - from) * e); drawDoors();
      if (u < 1) doorAnim[d] = requestAnimationFrame(step); else if (done) done();
    })(t0);
  }
  /* v1.12 : un ouvrant se referme alors qu'un autre reste ouvert -> la voiture revient sur la vue de celui resté ouvert
     (avant : elle restait sur la vue du coffre, et la porte encore ouverte n'apparaissait plus) */
  var homeT = null;
  function goHome() {          // tout est refermé : retour en douceur sur la position par défaut
    clearTimeout(homeT);
    homeT = setTimeout(function () {
      if (anyOpen3D()) return;
      var t = homeTarget(); if (Math.abs(t - angle.x) < 0.5) return;
      angle.to(t, { response: 0.9, damping: 1, onRest: function () { store.set('angle', angle.x % 360); } });
    }, 900);
  }
  function backToOpen() {
    var d3 = doors3D(veh.doors);
    var rest = ['fl', 'rl', 'fr', 'rr', 'trunk', 'hood'].filter(function (k) { return d3[k] && DV[k] != null; })[0];
    if (hiKey) return;
    if (!rest) { goHome(); return; }
    doorK[rest] = DSTEPS;
    var target = DV[rest] * STEP, cur = angle.x, diff = ((target - cur) % 360 + 540) % 360 - 180;
    if (Math.abs(diff) < 0.5 || Motion.reduced) { angle.set(cur + diff); drawDoors(); }
    else angle.to(cur + diff, { response: 0.7, damping: 1, onRest: drawDoors });
  }
  /* portes ouvertes des deux côtés : une seule vue de face légèrement plongeante (9 combinaisons pré-rendues),
     la voiture du plateau s'efface en fondu derrière */
  var HI = !!CFG.hiView, hiKey = null;
  function hiName(d3) {
    var L = d3.fl && d3.rl ? 'flrl' : d3.fl ? 'fl' : d3.rl ? 'rl' : '';
    var R = d3.fr && d3.rr ? 'frrr' : d3.fr ? 'fr' : d3.rr ? 'rr' : '';
    if (!(HI && L && R)) return null;
    return L + '_' + R + (d3.trunk ? '_t' : '');      // v1.13 : coffre ouvert visible dans la vue de face (le capot n'est pas détecté sur l'Ibiza)
  }
  if (HI) setTimeout(function () {
    ['fl', 'rl', 'flrl'].forEach(function (l) { ['fr', 'rr', 'frrr'].forEach(function (r) { var p = new Image(); p.src = 'img/car/door_hi_' + l + '_' + r + '.webp'; }); });
    var f = new Image(); f.src = 'img/car/floor_hi.webp';
  }, 4000);
  function applyHi(k) {
    hiKey = k;
    if (k) { $('#tt-floor-hi').setAttribute('src', 'img/car/floor_hi.webp'); $('#tt-hi').setAttribute('src', 'img/car/door_hi_' + k + '.webp'); }
    $('#turntable').classList.toggle('hi', !!k);
  }
  /* 5 portes : chaque ouvrant a sa propre animation, vue de 3/4 arrière du côté concerné */
  function doors3D(nd) { nd = nd || {}; var o = {}; Object.keys(DV).forEach(function (k) { o[k] = !!nd[k]; }); return o; }
  function showDoor3D(d) { // fait pivoter la voiture vers le bon angle puis ouvre la porte
    var target = DV[d] * STEP, cur = angle.x, diff = ((target - cur) % 360 + 540) % 360 - 180;
    var go = function () { playDoor(d, doors3D(veh.doors)[d] ? DSTEPS : 0); };
    if (Math.abs(diff) < 0.5 || Motion.reduced) { angle.set(cur + diff); go(); }
    else angle.to(cur + diff, { response: 0.7, damping: 1, onRest: go });
  }
  var drawAngle0 = drawAngle;
  drawAngle = function (deg) { drawAngle0(deg); drawDoors(); };
  angle.onUpdate = drawAngle;
  drawAngle(angle.x);
  (function () {
    var el = $('#turntable'), tr, sx, base, id = null, moved = false;
    el.addEventListener('pointerdown', function (e) {
      id = e.pointerId; sx = e.clientX; base = angle.x; moved = false; lockHinted = false;
      if (!anyOpen3D()) angle.stop();
      tr = new Motion.Tracker(); tr.add(e.clientX, 0); try { el.setPointerCapture(id); } catch (er) {}
    });
    el.addEventListener('pointermove', function (e) {
      if (e.pointerId !== id) return; tr.add(e.clientX, 0);
      var dx = e.clientX - sx; if (Math.abs(dx) > 6) moved = true;
      if (anyOpen3D()) { if (moved && !lockHinted) { lockHinted = true; toast('Ferme les ouvrants pour faire tourner la voiture'); } return; }
      angle.set(base - dx * 0.5);
    });
    function end(e) {
      if (e.pointerId !== id) return; id = null;
      if (!moved) { showPage('vehicle', $('#card-car')); return; }
      if (anyOpen3D()) return;   // voiture verrouillée sur la vue de l'ouvrant
      var v = -tr.velocity().x * 0.5; // deg/s
      var proj = angle.x + Motion.project(v, 0.995);
      var target = Math.round(proj / STEP) * STEP;
      angle.to(target, { response: 0.5, damping: 1, velocity: v, onRest: function () { store.set('angle', angle.x % 360); } });
      $('#tt-hint').style.opacity = 0;
    }
    el.addEventListener('pointerup', end); el.addEventListener('pointercancel', end);
  })();
  /* Les portes animées n'existent qu'à l'angle exact de leur vue : tant qu'un ouvrant est ouvert, on bloque la rotation. */
  var lockHinted = false;
  function anyOpen3D() { var d = doors3D(veh.doors); return Object.keys(DV).some(function (k) { return d[k]; }); }
  function introSpin() { // petit tour d'accueil au réveil (désactivé si mouvement réduit ou mode léger)
    if (Motion.reduced || lite || anyOpen3D()) return;
    var a = Math.round(homeTarget() / STEP) * STEP;
    angle.set(a - 60); angle.to(a, { response: 1.3, damping: 1 });
  }

  /* ======================= média ======================= */
  var media = {}, playGuessT = null;
  function renderMedia(m) {
    media = m || {};
    if (playGuessT && media.playing === undefined) media.playing = playGuessT.v;
    var has = !!(media.title), isRadio = media.kind === 'radio';
    $('#m-title').textContent = has ? media.title : 'Aucune lecture';
    $('#m-artist').textContent = has ? (media.artist || '') : 'Touchez une source pour commencer';
    $('#m-source').textContent = media.source || '';
    $('#m-play').innerHTML = svg(media.playing ? 'pause' : 'play');
    var art = $('#m-art');
    var bg = $('#m-bg');
    if (media.art) { art.style.backgroundImage = 'url(' + media.art + ')'; art.innerHTML = ''; bg.style.backgroundImage = 'url(' + media.art + ')'; bg.classList.add('on'); }
    else { art.style.backgroundImage = ''; art.innerHTML = svg(isRadio ? 'radio' : 'music'); bg.classList.remove('on'); }
    art.classList.toggle('is-radio', isRadio);
    $('#card-media').classList.toggle('no-progress', !media.dur);
    var p = media.dur ? Math.max(0, Math.min(1, media.pos / media.dur)) : 0;
    $('#m-bar').style.transform = 'scaleX(' + p + ')'; $('#m-bar').style.width = '100%';
    $('#m-t0').textContent = media.dur ? mmss(media.pos) : '';
    $('#m-t1').textContent = media.dur ? '-' + mmss(media.dur - media.pos) : '';
  }
  $('#m-prev').innerHTML = svg('prev'); $('#m-next').innerHTML = svg('next');
  /* Lecture / pause : toujours une bascule. L'icône change tout de suite ; l'état réel (s'il est connu) la corrige. */
  tappable($('#m-play'), function () {
    var nowPlaying = !media.playing;
    playGuessT = { v: nowPlaying }; setTimeout(function () { playGuessT = null; }, 2500);
    $('#m-play').innerHTML = svg(nowPlaying ? 'pause' : 'play'); media.playing = nowPlaying;
    Bridge.media('toggle');
  });
  tappable($('#m-prev'), function () { Bridge.media('prev'); });
  tappable($('#m-next'), function () { Bridge.media('next'); });
  // pochette : ouvre l'appli qui joue (radio, Spotify…), sinon l'appli Musique choisie
  tappable($('#m-art'), function () {
    if (media.pkg) { var p = appByPkg(media.pkg); if (p) { openApp(p); return; } Bridge.launchPkg(media.pkg); return; }
    var a = findTheme(media.kind === 'radio' ? 'radio' : 'music'); if (a) openApp(a); else toast('Choisis ton appli Musique dans les réglages');
  });
  Bridge.on('media', renderMedia);
  function mmss(t) { t = Math.max(0, Math.round(t || 0)); return Math.floor(t / 60) + ':' + pad(t % 60); }
  /* applis par défaut choisies à la main (Réglages > Applis par défaut) ; sinon reconnaissance par le nom */
  function roleApp(th) { var r = store.get('roles', {}) || {}; return r[th] && appByKey[r[th]] ? appByKey[r[th]] : null; }
  /* v2.3 : sans choix manuel, on préfère les applis les plus probables (Waze avant « GPS Test », Spotify avant le lecteur du poste) */
  var PREFER = {
    nav: ['com.waze', 'com.google.android.apps.maps', 'com.sygic', 'net.osmand', 'com.here', 'com.generalmagic', 'com.nng'],
    music: ['com.spotify.music', 'deezer.android', 'com.deezer', 'com.google.android.apps.youtube.music', 'com.amazon.mp3', 'com.apple.android.music', 'com.soundcloud', 'com.maxmpz.audioplayer'],
    phone: ['com.google.android.dialer']
  };
  var AVOID = { nav: /gps ?test|gps ?status|test|info|compass|boussole/i, music: /^$/ };
  function findTheme(th) {
    var ra = roleApp(th); if (ra) return ra;
    var cand = apps.filter(function (a) { return a.theme === th && !a.virtual; });
    if (!cand.length) return null;
    var pref = PREFER[th] || [];
    for (var p = 0; p < pref.length; p++) for (var i = 0; i < cand.length; i++) if (String(cand[i].pkg).indexOf(pref[p]) === 0) return cand[i];
    var avoid = AVOID[th];
    if (avoid) { var good = cand.filter(function (a) { return !avoid.test(a.label || ''); }); if (good.length) return good[0]; }
    return cand[0];
  }
  function appByPkg(pkg) { for (var i = 0; i < apps.length; i++) if (apps[i].pkg === pkg) return apps[i]; return null; }

  function renderQuick() {
    var q = $('#quick'); q.innerHTML = '';
    ['radio', 'eq', 'car', 'settings'].forEach(function (th) {
      var a = th === 'car' ? VIRTUAL[0] : findTheme(th); if (!a) a = th === 'settings' ? VIRTUAL[1] : null;
      var b = document.createElement('button'); b.className = 'qbtn press';
      var lbl = { radio: 'Radio', eq: 'Son', car: 'Véhicule', settings: 'Réglages' }[th];
      b.innerHTML = (a ? Icons.appIcon(a, 50) : Icons.appIcon({ theme: th }, 50)) + '<span>' + lbl + '</span>';
      tappable(b, function () { if (a) openApp(a, b); else toast('App introuvable sur le poste'); });
      q.appendChild(b);
    });
  }

  /* ======================= véhicule ======================= */
  var DOORS = [
    ['fl', 'Porte avant gauche', 'door'], ['fr', 'Porte avant droite', 'door'],
    ['rl', 'Porte arrière gauche', 'door'], ['rr', 'Porte arrière droite', 'door'],
    ['trunk', 'Coffre', 'trunk'], ['hood', 'Capot', 'hood']
  ].filter(function (d) { return CFG.doors !== 3 || (d[0] !== 'rl' && d[0] !== 'rr'); });   // 3 portes : pas de portes arrière
  var veh = { doors: {}, radar: null, reverse: false };
  var vehView = NOCAN ? 'trip' : 'doors';
  function openList() { return DOORS.filter(function (d) { return veh.doors[d[0]]; }); }
  function statusText() {
    if (NOCAN) return { t: 'Données calculées par le GPS', c: 'ok' };
    var o = openList();
    if (!o.length) return veh.doors.any ? { t: 'Un ouvrant est ouvert', c: 'warn' } : { t: 'Tout est fermé', c: 'ok' };
    if (o.length === 1) return { t: o[0][1] + ' ouvert' + (o[0][0] === 'trunk' || o[0][0] === 'hood' ? '' : 'e'), c: 'warn' };
    return { t: o.length + ' ouvrants ouverts', c: 'warn' };
  }
  function layersHtml() {
    return DOORS.map(function (d) { return '<img class="tv-layer" data-d="' + d[0] + '" src="img/top/' + d[0] + '.webp" alt="">'; }).join('');
  }
  $('#tv-layers').innerHTML = layersHtml(); $('#live-layers').innerHTML = layersHtml();
  function applyLayers(scope) {
    $$('.tv-layer', scope).forEach(function (l) { l.classList.toggle('on', !!veh.doors[l.getAttribute('data-d')]); });
  }
  function renderVehicleSummary() {
    var s = statusText();
    [$('#car-status'), $('#veh-status')].forEach(function (el) { el.innerHTML = '<i class="dot ' + s.c + '"></i><span>' + s.t + '</span>'; });
    var o = openList(), sv = serviceText();
    var svChip = { ic: 'wrench', b: 'Entretien', s: sv.t === '—' ? 'À renseigner' : sv.label ? sv.label + ' · ' + sv.t.replace('Dans ', '').replace('Dépassé de ', 'retard ') : sv.t, a: sv.warn, page: 'maint' };
    var chips = NOCAN ? [
      { ic: 'car', b: 'Trajet', s: (fmtN(car.trip || 0, 1) || '0') + ' km', page: 'trip' },
      { ic: 'gauge', b: 'Compteur', s: car.odo != null ? fmtN(car.odo) + ' km' : 'À saisir', a: car.odo == null, page: 'trip' },
      svChip
    ] : [
      { ic: 'door', b: 'Ouvrants', s: o.length ? (o.length === 1 ? o[0][1].replace('Porte ', '') : o.length + ' ouverts') : veh.doors.any ? 'Ouvert' : 'Fermés', a: o.length > 0 || !!veh.doors.any },
      (sv.t === '—' && car.fuel != null && !store.get('maintChip', false)) ? { ic: 'fuel', b: 'Carburant', s: fmtN(car.fuel) + ' L', a: car.fuel < 7, page: 'trip' } : { ic: 'wrench', b: 'Entretien', s: sv.t === '—' ? 'À renseigner' : sv.label ? sv.label + ' · ' + sv.t.replace('Dans ', '').replace('Dépassé de ', 'retard ') : sv.t, a: sv.warn, page: 'maint' },
      { ic: 'radar', b: 'Radars', s: veh.radar ? minDist(veh.radar) : 'En veille', a: !!veh.radar }
    ];
    $('#car-chips').innerHTML = chips.map(function (c) {
      return '<div class="cchip' + (c.a ? ' alert' : '') + '">' + svg(c.ic) + '<div><b>' + c.b + '</b><span>' + c.s + '</span></div></div>';
    }).join('');
    $$('.cchip').forEach(function (c, i) { tappable(c, function () { if (chips[i].page === 'maint' && CFG.cockpit) { openCockpit(); return; } if (chips[i].page) setVehView(chips[i].page); showPage('vehicle', c); }); });
  }
  function renderVehicle() {
    applyLayers($('#topview'));
    var list = DOORS.map(function (d) {
      var o = !!veh.doors[d[0]];
      return '<div class="row">' + svg(d[2]) + '<div class="grow">' + d[1] + '</div><div class="val' + (o ? ' warn' : '') + '">' + (o ? 'Ouvert' : 'Fermé') + '</div></div>';
    }).join('');
    list += '<div class="hdr">Radars de recul</div><div class="row">' + svg('radar') + '<div class="grow">Distance mini</div><div class="val">' + (veh.radar ? minDist(veh.radar) : '—') + '</div></div>';
    if (NOCAN) {          // pas de capteurs : fiche de la voiture + prochain entretien
      var nx = maintNext();
      list = '<div class="hdr">Ma voiture</div>' +
        '<div class="row">' + svg('car') + '<div class="grow">' + esc(CAR.model) + '</div></div>' +
        '<div class="row">' + svg('fuel') + '<div class="grow">Carburant</div><div class="val">' + (engine() === 'diesel' ? 'Diesel' : 'Essence') + '</div></div>' +
        '<div class="row">' + svg('gauge') + '<div class="grow">Kilométrage</div><div class="val' + (car.odo == null ? ' warn' : '') + '">' + (car.odo != null ? fmtN(car.odo) + ' km' : 'À saisir') + '</div></div>' +
        '<div class="hdr">Prochain entretien</div>' +
        '<div class="row">' + svg('wrench') + '<div class="grow">' + (nx ? esc(nx.name) : 'Rien de prévu') + '</div><div class="val' + (nx && nx.level > 0 ? ' warn' : '') + '">' + (nx ? esc(nx.short) : '') + '</div></div>';
    }
    $('#veh-list').innerHTML = list;
    drawRadar($('#radar-svg'));
    $('#radar-svg').classList.toggle('on', vehView === 'radar' || !!veh.radar);
    $('#topview').style.display = vehView === 'trip' || vehView === 'maint' ? 'none' : '';
    $('#tripview').style.display = vehView === 'trip' ? '' : 'none';
    $('#maintview').style.display = vehView === 'maint' ? '' : 'none';
    if (vehView === 'trip') renderTrip();
    if (vehView === 'maint') renderMaint();
  }
  function setVehView(v) {
    if (v === 'maint' && CFG.cockpit) { openCockpit(); return; }
    vehView = v;
    $$('#veh-seg .seg-b').forEach(function (x) { x.classList.toggle('is-on', x.getAttribute('data-v') === v); });
    if (current === 'vehicle') renderVehicle();
  }
  $$('#veh-seg .seg-b').forEach(function (b) { tappable(b, function () { setVehView(b.getAttribute('data-v')); renderVehicle(); }); });

  /* ======================= ordinateur de bord (CAN, profil VW) ======================= */
  var car = store.get('car', {}) || {};   // dernières valeurs connues (gardées au redémarrage)
  if (NOCAN) car = {};                      // sans boîtier : on repart des seules données GPS
  function fmtN(v, dec) { if (v == null || v === '' || isNaN(v)) return null; return Number(v).toFixed(dec || 0).replace('.', ',').replace(/\B(?=(\d{3})+(?!\d))/g, '\u202f'); }
  function serviceText() {
    var nx = maintNext();      // entretien saisi à la main (prioritaire : la voiture ne le fournit pas)
    if (nx) return { t: nx.short, warn: nx.level > 0, label: nx.name };
    var km = car.insp_km, d = car.insp_days;
    if (km == null && d == null) { km = car.oil_km; d = car.oil_days; }
    if (km == null && d == null) return { t: '—', warn: false };
    var parts = []; if (km != null) parts.push(fmtN(km) + ' km'); if (d != null) parts.push(fmtN(d) + ' j');
    return { t: 'Dans ' + parts.join(' · '), warn: (km != null && km < 1000) || (d != null && d < 30) };
  }
  var TRIP = [
    ['outside', 'Extérieur', 'temp', function (v) { return fmtN(v, 1) + '°'; }, function (v) { return v <= 3; }],
    ['coolant', 'Liquide de refroid.', 'temp', function (v) { return fmtN(v) + '°'; }, function (v) { return v > 110; }],
    ['oil', 'Huile moteur', 'temp', function (v) { return fmtN(v) + '°'; }, function (v) { return v > 130; }],
    ['battery', 'Batterie', 'battery', function (v) { return fmtN(v, 1) + ' V'; }, function (v) { return v < 11.8; }],
    ['insp', 'Prochaine révision', 'wrench', null, null],
    ['oilsvc', 'Prochaine vidange', 'wrench', null, null],
    ['range', 'Autonomie', 'fuel', function (v) { return fmtN(v) + ' km'; }, function (v) { return v < 60; }],
    ['cons', 'Consommation moy.', 'gauge', function (v) { return fmtN(v, 1) + ' L/100'; }, null],
    ['avg', 'Vitesse moyenne', 'gauge', function (v) { return fmtN(v) + ' km/h'; }, null],
    ['odo', 'Kilométrage', 'car', function (v) { return fmtN(v) + ' km'; }, null],
    ['fuel', 'Carburant restant', 'fuel', function (v) { return fmtN(v) + ' L'; }, function (v) { return v < 7; }],
    ['rpm', 'Régime moteur', 'gauge', function (v) { return fmtN(v) + ' tr/min'; }, null],
    ['speed', 'Vitesse', 'gauge', function (v) { return fmtN(v) + ' km/h'; }, null],
    // frein à main retiré : l'Ibiza ne l'envoie pas sur le CAN (reste « Pas de freins » même dans CarCan)
    ['belt', 'Ceinture conducteur', 'check', function (v) { return /ne pas|non|pas attach/i.test(v) ? 'Non bouclée' : v; }, function (v) { return /ne pas|non|pas attach/i.test(v); }],
    ['washer', 'Lave-glace', 'check', function (v) { return v; }, function (v) { return !/normal|ok/i.test(v); }]
  ];
  function hhmm(sec) { sec = Math.round(sec || 0); var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60); return h ? h + ' h ' + pad(m) : m + ' min'; }
  if (NOCAN) TRIP = [      // sans boîtier CAN : ce que le GPS de la tablette permet de calculer
    ['trip', 'Trajet du jour', 'car', function (v) { return fmtN(v, 1) + ' km'; }, null],
    ['drive', 'Temps de conduite', 'gauge', hhmm, null],
    ['avg', 'Vitesse moyenne', 'gauge', function (v) { return fmtN(v) + ' km/h'; }, null],
    ['speed', 'Vitesse actuelle', 'gauge', function (v) { return fmtN(v) + ' km/h'; }, null],
    ['odo', 'Kilométrage estimé', 'car', function (v) { return fmtN(v) + ' km'; }, null],
    ['vmax', 'Vitesse max du jour', 'gauge', function (v) { return fmtN(v) + ' km/h'; }, null]
  ];
  var TRIP_CORE = NOCAN ? TRIP.map(function (t) { return t[0]; }) : ['outside', 'battery', 'fuel', 'odo'];
  function svcVal(kk, dk) {
    var p = []; if (car[kk] != null) p.push(fmtN(car[kk]) + ' km'); if (car[dk] != null) p.push(fmtN(car[dk]) + ' j');
    return p.length ? { v: p.join(' · '), w: (car[kk] != null && car[kk] < 1000) || (car[dk] != null && car[dk] < 30) } : null;
  }
  function renderTrip() {
    var h = '<div class="trip-grid">';
    TRIP.forEach(function (t) {
      var val = null, warn = false;
      var has = t[0] === 'insp' ? (car.insp_km != null || car.insp_days != null) : t[0] === 'oilsvc' ? (car.oil_km != null || car.oil_days != null) : car[t[0]] != null;
      if (!has && TRIP_CORE.indexOf(t[0]) < 0) return;   // ce que le boîtier ne fournit pas n'est pas affiché
      if (t[0] === 'insp') { var a = svcVal('insp_km', 'insp_days'); if (a) { val = a.v; warn = a.w; } }
      else if (t[0] === 'oilsvc') { var b = svcVal('oil_km', 'oil_days'); if (b) { val = b.v; warn = b.w; } }
      else if (car[t[0]] != null) { val = t[3](car[t[0]]); warn = t[4] ? t[4](car[t[0]]) : false; }
      h += '<div class="trip' + (warn ? ' warn' : '') + (val ? '' : ' empty') + (t[0] === 'insp' || t[0] === 'oilsvc' ? ' wide' : '') + '">' + svg(t[2]) + '<div><span>' + t[1] + '</span><b>' + (val || '—') + '</b></div></div>';
    });
    h += '</div>';
    if (NOCAN) {
      h += '<div class="trip-foot"><span>Calculé avec le GPS de la tablette. Le trajet repart à zéro chaque jour.</span>' +
        '<span class="trip-btns"><button class="chip-btn press" id="trip-odo">' + svg('gauge') + 'Kilométrage</button><button class="chip-btn press" id="trip-reset">' + svg('check') + 'Remettre à zéro</button></span></div>';
      $('#tripview').innerHTML = h;
      tappable($('#trip-odo'), odoSheet);
      tappable($('#trip-reset'), function () { gpsReset(); toast('Trajet remis à zéro'); });
      return;
    }
    var when = car.at ? new Date(car.at) : null;
    h += '<div class="trip-foot"><span>' + (when ? 'Mis à jour ' + pad(when.getHours()) + ':' + pad(when.getMinutes()) + (car.src ? ' · ' + esc(car.src) : '') : 'Aucune donnée CAN reçue pour l\'instant.') + '</span>' +
      '<span class="trip-btns"><button class="chip-btn press" id="trip-refresh">' + svg('check') + 'Actualiser</button><button class="chip-btn press" id="trip-open">' + svg('car') + 'CarCan</button></span></div>';
    $('#tripview').innerHTML = h;
    tappable($('#trip-refresh'), function () {
      if (!Bridge.canRefreshPossible()) { toast('Active l\'accessibilité du lanceur pour lire CarCan'); return; }
      Bridge.canRefresh();
    });
    tappable($('#trip-open'), function () {
      var a = roleApp('can'); if (a) { openApp(a); return; }
      toast('Choisis l\'appli CAN dans Réglages > Applis par défaut'); showPage('settings', $('#trip-open'));
    });
  }
  tappable($('#btn-vehicle'), function () { showPage('vehicle', $('#btn-vehicle')); });
  $('#btn-vehicle').innerHTML = svg('car') + 'Véhicule';

  /* radars : capteurs arrière (4) et avant (4) en cm ; null = inconnu */
  function minDist(r) {
    var all = (r.rear || []).concat(r.front || []).filter(function (x) { return x != null; });
    if (!all.length) return 'Actifs';
    var m = Math.min.apply(null, all); return m >= 150 ? '> 1,5 m' : m + ' cm';
  }
  function zoneColor(d) { return d == null ? 'rgba(255,255,255,.10)' : d < 40 ? '#ff4245' : d < 80 ? '#ff9230' : d < 150 ? '#ffd600' : 'rgba(48,209,88,.55)'; }
  function drawRadar(el, noText) {
    var B = CFG.topBox || { x0: 219, x1: 460, y0: 49, y1: 633, w: 680, h: 750 }; // vue de dessus verticale, avant en haut
    el.setAttribute('viewBox', '0 0 ' + (B.w || 680) + ' ' + (B.h || 750));
    var cx = (B.x0 + B.x1) / 2, w = B.x1 - B.x0, r = veh.radar || { rear: [null, null, null, null] };
    var out = '';
    function sector(cy, r0, r1, a0, a1, col) {
      function pt(rr, a) { return (cx + rr * Math.cos(a)).toFixed(1) + ',' + (cy + rr * Math.sin(a)).toFixed(1); }
      return '<path fill="' + col + '" d="M' + pt(r0, a0) + 'L' + pt(r1, a0) + 'A' + r1 + ',' + r1 + ' 0 0 1 ' + pt(r1, a1) + 'L' + pt(r0, a1) + 'A' + r0 + ',' + r0 + ' 0 0 0 ' + pt(r0, a0) + 'Z"/>';
    }
    function arcs(side, vals) {
      var rear = side === 'rear', cy = rear ? B.y1 - w * 0.5 : B.y0 + w * 0.5, mid = rear ? Math.PI / 2 : -Math.PI / 2;
      var span = Math.PI * 0.5;
      for (var s = 0; s < 4; s++) {
        var d = vals ? vals[s] : null; // capteurs de gauche à droite vus du dessus
        var a0 = mid - span / 2 + s * span / 4, a1 = a0 + span / 4;
        if (rear) { a0 = mid + span / 2 - (s + 1) * span / 4; a1 = a0 + span / 4; }
        for (var ring = 0; ring < 4; ring++) {
          var zi = d == null ? -1 : d < 40 ? 0 : d < 80 ? 1 : d < 150 ? 2 : d < 250 ? 3 : -1, lit = ring === zi;
          var r0 = w * 0.5 + 34 + ring * 20, r1 = r0 + 15;
          out += sector(cy, r0, r1, a0 + 0.025, a1 - 0.025, lit ? zoneColor(d) : 'rgba(255,255,255,.08)');
        }
      }
    }
    arcs('rear', r.rear);
    if (r.front && r.front.some(function (x) { return x != null; })) arcs('front', r.front);
    if (veh.radar && !noText) out += '<text x="' + (B.x1 + 46) + '" y="' + (B.y1 - w * 0.45) + '" fill="#fff" font-size="40" font-weight="700" text-anchor="start" font-family="UI">' + minDist(veh.radar) + '</text>';
    el.innerHTML = out;
  }

  /* ======================= activité en direct ======================= */
  /* panneau couché : la plus grande taille possible (image recadrée 528 x 425 tournée de -90°) */
  var LIVE = { W: 560, H: 420, s: 1 };
  function liveSize(radar) {
    var head = 56, padd = 14;
    var carW = Math.min(VW * 0.62 - 2 * padd, (VH * 0.80 - head - padd) * 1.242);
    LIVE.W = Math.round(carW + 2 * padd); LIVE.H = Math.round(carW / 1.242 + head + padd);
    // portes : cadrage serré sur la voiture (y 22→550 de l'image) ; radars : on garde les arcs arrière (y 22→600)
    var len = radar ? 578 : 528, shift = radar ? -11 : 14;
    LIVE.s = Math.min(carW / len, (carW / 1.242) / 425);
    var r = $('#live-rot'); if (r) r.style.transform = 'rotate(-90deg) scale(' + LIVE.s.toFixed(4) + ') translate(0px,' + shift + 'px)';
  }
  var liveS = new Spring(0, { onUpdate: function (v) {
    var el = $('#live'), W0 = 340, H0 = 56, W1 = LIVE.W, H1 = LIVE.H;
    var open = Math.max(0, liveS.expand.x);
    var w = W0 + (W1 - W0) * open, h = H0 + (H1 - H0) * open;
    el.style.width = w + 'px'; el.style.height = h + 'px'; el.style.marginLeft = (-w / 2) + 'px';
    el.style.opacity = Math.max(0, Math.min(1, v));
    el.style.transform = 'translateY(' + ((1 - v) * -60) + 'px) scale(' + (0.9 + 0.1 * v) + ')';
    el.style.pointerEvents = v > 0.5 ? 'auto' : 'none';
    el.style.borderRadius = (28 + 4 * open) + 'px';
    $('.live-body').style.opacity = open;
  } });
  liveS.expand = new Spring(0, { onUpdate: function () { liveS._emit(); } });
  var liveTimer, dismissed = null;
  function openSig() { return DOORS.filter(function (d) { return veh.doors[d[0]]; }).map(function (d) { return d[0]; }).join(',') + (veh.doors.any ? ',any' : '') + (veh.radar ? ',radar' : ''); }
  function showLive(text, radar) {
    // accueil (voiture 3D) et page Véhicule montrent déjà les ouvrants en direct : pas de fenêtre en plus
    if (current === 'vehicle' || current === 'home' || store.get('live', true) === false) return;
    if (dismissed !== null) {
      // fermé à la main : on ne revient que si un nouvel ouvrant s'ouvre
      var now = openSig().split(','), was = dismissed.split(','), fresh = now.some(function (k) { return k && was.indexOf(k) < 0; });
      if (!fresh) return;
      dismissed = null;
    }
    $('#live-text').textContent = text;
    liveSize(radar); applyLayers($('#live')); drawRadar($('#live-radar'), true); $('#live-radar').classList.toggle('on', !!radar);
    alignGlass();
    liveS.to(1, { response: 0.4, damping: 0.85 });
    liveS.expand.to(1, { response: 0.45, damping: 0.82 });
    clearTimeout(liveTimer);
  }
  function hideLive(now) { clearTimeout(liveTimer); liveS.expand.to(0, { response: 0.35, damping: 1 }); liveS.to(0, { response: now ? 0.25 : 0.45, damping: 1 }); }
  tappable($('#live'), function (e) { if (e.target.closest && e.target.closest('#live-close')) return; hideLive(true); showPage('vehicle', $('#live')); });
  $('#live-close').innerHTML = svg('plus').replace('class="ic ', 'style="transform:rotate(45deg)" class="ic ');
  tappable($('#live-close'), function () { dismissed = openSig(); hideLive(true); });

  var prevOpen = 0;
  var last3D = {};
  function onVehicle(v) {
    if (!v) return;
    var nd = v.doors || veh.doors, d3 = doors3D(nd), changed = null;
    Object.keys(DV).forEach(function (d) { if (!!d3[d] !== !!last3D[d]) changed = d; });
    last3D = d3;
    veh.doors = nd;
    var hk = changed ? hiName(d3) : hiKey;
    if (changed && (hk || hiKey)) {
      // portes des deux côtés : vue de face plongeante (états figés, pas d'animation de porte)
      Object.keys(DV).forEach(function (d) { cancelAnimationFrame(doorAnim[d]); doorK[d] = d3[d] ? DSTEPS : 0; });
      if (!hk) { // retour à un seul côté : la voiture se cale directement sur la vue de l'ouvrant resté ouvert
        var rest = ['fl', 'rl', 'fr', 'rr', 'trunk', 'hood'].filter(function (d) { return d3[d] && DV[d] != null; })[0];
        if (rest) { var tg = DV[rest] * STEP, df = ((tg - angle.x) % 360 + 540) % 360 - 180; angle.stop(); angle.set(angle.x + df); }
        else goHome();
      }
      applyHi(hk); drawDoors();
    } else if (changed === 'trunk' && CFG.comboAnim && (d3.fl || d3.rl || d3.fr || d3.rr)) {
      // hayon avec porte(s) ouverte(s) : il s'anime à la vue de 3/4 arrière du côté des portes, elles restent ouvertes
      Object.keys(DV).forEach(function (d) { if (d !== 'trunk') { cancelAnimationFrame(doorAnim[d]); doorK[d] = d3[d] ? DSTEPS : 0; } });
      rotateTo(d3.fl || d3.rl ? DV.fl : DV.fr, function () { playDoor('trunk', d3.trunk ? DSTEPS : 0); });
    } else if (changed) {
      if (d3[changed]) showDoor3D(changed);
      else if (viewIndex() === DV[changed]) playDoor(changed, 0, backToOpen); else { doorK[changed] = 0; backToOpen(); }
      Object.keys(DV).forEach(function (d) { if (d !== changed && !d3[d]) doorK[d] = 0; if (d !== changed && d3[d]) doorK[d] = DSTEPS; });
    }
    if (changed && CFG.comboAnim) preloadCombos(d3);
    veh.radar = v.radar || null; veh.reverse = !!v.reverse;
    renderVehicleSummary(); if (current === 'vehicle') renderVehicle();
    var o = openList(), s = statusText();
    if (veh.radar && veh.reverse) showLive('Radars · ' + minDist(veh.radar), true);
    else if (o.length || veh.doors.any) showLive(s.t, false);
    else if (!o.length && !veh.doors.any && !veh.radar) dismissed = null;
    if (!o.length && !veh.doors.any && !veh.radar && (prevOpen || true)) { if (liveS.target) { $('#live-text').textContent = 'Tout est fermé'; applyLayers($('#live')); liveTimer = setTimeout(function () { hideLive(); }, 1200); } }
    prevOpen = o.length;
  }
  Bridge.on('vehicle', onVehicle);

  /* ======================= réglages du lanceur ======================= */
  function renderSettings() {
    var st = Bridge.state(), p = st.perms || {};
    var glass = store.get('glass', 'sim');
    var canOnly = function (arr) { return NOCAN ? [] : arr; };   // lignes inutiles sans boîtier CAN
    var rows = [].concat([
      '<div class="hdr">Installation</div>',
      row('home', 'Lanceur par défaut', p.home ? 'Actif' : 'Choisir', 'sys:home'),
      row('music', 'Accès aux notifications (média)', p.notif ? 'Autorisé' : 'À autoriser', 'sys:notif')
    ], canOnly([
      row('car', 'Lecture CAN (accessibilité)', p.access ? 'Autorisé' : 'À autoriser', 'sys:access'),
      row('door', 'Fenêtre portes par-dessus les apps', p.overlay ? 'Autorisé' : 'À autoriser', 'sys:overlay')
    ]), [
      row('home', 'Revenir au lanceur d\'origine / désinstaller', '', 'sys:leave'),
      /* v2.1 : barre latérale qui reste affichée sur Waze, Spotify… */
      '<div class="hdr">Barre sur les applis</div>',
      toggleRow('home', 'Garder la barre sur Waze, Spotify…', store.get('bar', true), 'bar'),
      row('door', 'Autorisation d\'affichage par-dessus', p.overlay ? 'Autorisée' : 'À autoriser', 'sys:overlay'),
      row('settings', 'Place des applis', BAR_MODES[store.get('barMode', 'auto')] || 'Automatique', 'bar:mode'),
      toggleRow('home', 'Tableau de bord : carte et musique en fenêtres', store.get('dash', true), 'dash'),
      toggleRow('music', 'Son Bluetooth du téléphone au démarrage', store.get('btAuto', true), 'btAuto'),
      roleRow('bt', 'Appli son Bluetooth du poste'),
      toggleRow('music', 'Rectangle musique sur Waze', store.get('mini', true), 'mini'),
      toggleRow('home', 'Ouvrir Waze au démarrage', store.get('autoNav', true), 'autoNav'),
      /* v2.2 : mise à jour depuis GitHub */
      '<div class="hdr">Mises à jour</div>',
      row('check', 'Rechercher une mise à jour', UPD.remote > UPD.local && UPD.local ? 'Version ' + esc(UPD.name || '') + ' disponible' : 'Version actuelle ' + esc(APP_VERSION_NAME), 'upd:check'),
      toggleRow('check', 'Vérifier au démarrage', store.get('autoUpdate', true), 'autoUpdate')
    ], NOCAN ? [
      '<div class="hdr">Ma voiture</div>',
      row('gauge', 'Kilométrage du compteur', car.odo != null ? fmtN(car.odo) + ' km' : 'À saisir', 'gps:odo'),
      row('check', 'Remettre le trajet du jour à zéro', '', 'gps:reset')
    ] : [], [
      '<div class="hdr">Affichage</div>',
      row('grid', 'Taille de l\'interface', { normal: 'Normale', grand: 'Grande', xl: 'Très grande' }[store.get('zoom', 'grand')], 'zoom'),
      row('sun', 'Thème', { auto: 'Auto (phares / nuit)', dark: 'Sombre', light: 'Clair' }[store.get('theme', 'auto')], 'theme'),
      row('home', 'Fond d\'écran', { match: 'Suit le thème', dark: 'Sombre', light: 'Clair', day: 'Jour', evening: 'Soir', sunset: 'Couchant', night: 'Nuit', auto: 'Selon l\'heure', custom: 'Perso' }[store.get('wall', 'match')] || '', 'wall'),
      row('sun', 'Effet verre', { sim: 'Rapide', real: 'Flou réel', off: 'Désactivé' }[glass], 'glass')
    ], canOnly([
      toggleRow('door', 'Activité en direct (portes, radars)', store.get('live', true), 'live'),
      toggleRow('door', 'Alerte ouvrant ouvert en roulant', store.get('moveAlert', true), 'moveAlert'),
      toggleRow('sun', 'Carte « Prêt à partir » au démarrage', store.get('ready', true), 'ready'),
      row('gauge', 'Lecture auto de l\'ordinateur de bord', { off: 'Désactivée', boot: 'Au démarrage', '15': 'Démarrage + 15 min' }[store.get('autoread', 'boot')], 'autoread')
    ]), [
      toggleRow('sun', 'Mode léger (processeur lent)', store.get('lite', false), 'lite'),
      toggleRow('dash', 'Masquer la barre système de l\'autoradio', store.get('immersive', true), 'immersive'),
      row('grid', 'Recharger les icônes perso', '', 'icons:reload'),
      '<div class="t-cap" style="padding:0 10px 6px">Dossier : ' + esc(Bridge.iconDir()) + '/&lt;paquet&gt;.png</div>',
      '<div class="hdr">Applis par défaut</div>',
      roleRow('music', 'Musique'), roleRow('radio', 'Radio'), roleRow('nav', 'Navigation'), roleRow('phone', 'Téléphone'), roleRow('eq', 'Son / égaliseur')
    ], canOnly([
      roleRow('can', 'Appli CAN (infos véhicule)'),
      '<div class="hdr">Données CAN du poste</div>',
      row('car', 'Analyser les applis CAN du poste', '', 'can:scan'),
      row('files', 'Copier les APK CAN (pour analyse)', '', 'can:export'),
      row('bug', 'Ordinateur de bord en direct (journal)', !Bridge.canLogs() ? 'Non autorisé' : Bridge.canLive() ? 'Actif' : 'Autorisé · en attente', 'can:logs'),
      '<div class="hdr">Tester sans la voiture</div>',
      row('door', 'Simuler : porte conducteur', '', 'sim:fl'),
      row('door', 'Simuler : porte arrière gauche', '', 'sim:rl'),
      row('trunk', 'Simuler : coffre', '', 'sim:trunk'),
      row('radar', 'Simuler : marche arrière + radars', '', 'sim:radar'),
      row('check', 'Tout fermer', '', 'sim:none'),
      row('door', 'Tester l\'alerte « porte ouverte en roulant »', '', 'test:moving'),
      row('sun', 'Afficher la carte « Prêt à partir »', '', 'test:ready'),
      '<div class="hdr">Diagnostic CAN</div>',
      row('bug', 'Exporter le journal (Téléchargements)', '', 'diag:export'),
      row('bug', 'Vider le journal', '', 'diag:clear'),
      '<div class="diag" id="diag"></div>'
    ]), [
      '<div class="hdr">À propos</div>',
      row('settings', 'Paramètres Android', '', 'sys:android')
    ]);
    $('#settings-list').innerHTML = rows.join('');
    if ($('#diag')) $('#diag').textContent = Bridge.diag() || '';
    $$('#settings-list [data-act]').forEach(function (el) { tappable(el, function () { act(el.getAttribute('data-act'), el); }); });
  }
  function roleRow(th, t) {
    var a = roleApp(th), auto = a ? null : (th === 'can' ? null : findTheme(th));
    return row(th === 'can' ? 'car' : th === 'eq' ? 'eq' : th === 'bt' ? 'music' : th, t, a ? esc(a.label) : auto ? 'Auto : ' + esc(auto.label) : 'Choisir', 'role:' + th);
  }
  function roleSheet(th, label) {
    var list = apps.filter(function (a) { return !a.virtual; });
    var r = store.get('roles', {}) || {};
    var h = '<div class="t-headline">' + label + '</div><div class="t-sub">Touche l\'appli à utiliser.</div><div class="pick-list">' +
      '<div class="pick press" data-k=""><div class="aicon aicon-raw noimg" style="width:44px;height:44px"><b>A</b></div><span>Automatique</span></div>' +
      list.map(function (a) { return '<div class="pick press' + (r[th] === key(a) ? ' on' : '') + '" data-k="' + esc(key(a)) + '">' + Icons.appIcon(a, 44) + '<span>' + esc(a.label) + '</span></div>'; }).join('') + '</div>';
    openSheet(h, function (s) {
      $$('.pick', s).forEach(function (el) { tappable(el, function () {
        var rr = store.get('roles', {}) || {}, k = el.getAttribute('data-k');
        if (k) rr[th] = k; else delete rr[th];
        store.set('roles', rr); closeSheet(); renderDock(); renderQuick(); renderSettings(); alignGlass();
      }); });
    });
  }
  function row(ic, t, val, act) { return '<div class="row btn" data-act="' + act + '">' + svg(ic) + '<div class="grow">' + t + '</div><div class="val">' + val + '</div>' + (val !== '' || true ? '' : '') + '</div>'; }
  function toggleRow(ic, t, on, act) { return '<div class="row btn" data-act="toggle:' + act + '">' + svg(ic) + '<div class="grow">' + t + '</div><div class="switch' + (on ? ' on' : '') + '"></div></div>'; }
  /* ======================= v2.1 : place des applis à côté de la barre ======================= */
  var BAR_MODES = { auto: 'Automatique', window: 'À côté de la barre', reserve: 'Bande réservée', over: 'Par-dessus' };
  function applyBarMode() {
    var info = Bridge.barInfo() || {};
    root.classList.toggle('extbar', info.effective === 'reserve' && info.enabled !== false && !!info.overlay);   // la barre système remplace celle de la page
  }
  function barSheet() {
    var info = Bridge.barInfo() || {}, cur = store.get('barMode', 'auto'), px = info.barPx || 88;
    var andro = info.demo ? 'Aperçu dans le navigateur : les infos de la tablette s\'afficheront sur l\'autoradio.' :
      'Android ' + esc(String(info.release || '?')) + ', multi-fenêtres ' + (info.freeform ? 'disponible' : 'non activé') +
      '. Méthode utilisée : ' + (BAR_MODES[info.effective] || '—') + '.';
    var help = {
      auto: 'Choisit tout seul : à côté de la barre si la tablette le permet, sinon par-dessus.',
      window: 'Waze et Spotify s\'ouvrent dans une fenêtre qui commence après la barre. Il faut que le multi-fenêtres soit activé. ' +
        'Sinon, une fois depuis un PC (câble USB, débogage USB activé) :<code>adb shell settings put global enable_freeform_support 1</code>' +
        '<code>adb shell settings put global force_resizable_activities 1</code>puis redémarre la tablette.',
      reserve: 'Android 10 ou moins : la bande de gauche est réservée à la barre, les applis s\'affichent à côté sans rien cacher. ' +
        'Une fois depuis un PC :<code>adb shell wm overscan ' + px + ',0,0,0</code>Pour annuler :<code>adb shell wm overscan reset</code>',
      over: 'Les applis restent en plein écran et la barre se pose sur leur bord gauche. Toujours disponible, rien à configurer.'
    };
    var btns = Object.keys(BAR_MODES).map(function (k) {
      return '<button class="sbtn press' + (k === cur ? ' primary' : '') + '" data-m="' + k + '">' + BAR_MODES[k] + '</button>';
    }).join('');
    openSheet('<div class="t-headline">Place des applis</div><div class="t-sub">' + andro + '</div>' +
      '<div class="sheet-actions bar-modes">' + btns + '</div><div class="t-sub bar-help" id="bar-help">' + help[cur] + '</div>', function (s) {
      $$('[data-m]', s).forEach(function (b) {
        b.onclick = function () {
          var m = b.getAttribute('data-m');
          store.set('barMode', m);
          $$('[data-m]', s).forEach(function (x) { x.classList.toggle('primary', x === b); });
          $('#bar-help', s).innerHTML = help[m];
          setTimeout(function () { applyBarMode(); renderSettings(); }, 300);
        };
      });
    });
  }

  /* ======================= v2.2 : mise à jour depuis GitHub ======================= */
  var APP_VERSION_NAME = CFG.version || '';
  var UPD = { local: 0, remote: 0, name: '', manual: false };
  function updRepo() { return CFG.updateRepo || ''; }
  function updSheet() {
    openSheet('<div class="t-headline">Mise à jour disponible</div><div class="t-sub">Version ' + esc(UPD.name || '') +
      '. Elle s\'installe par-dessus l\'actuelle, tes réglages sont conservés. Android te demandera de confirmer.</div>' +
      '<div class="t-sub" id="upd-state"></div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-a="cancel">Plus tard</button><button class="sbtn primary press" data-a="ok">Installer</button></div>', function (s) {
      $('[data-a="cancel"]', s).onclick = closeSheet;
      $('[data-a="ok"]', s).onclick = function () { $('#upd-state', s).textContent = 'Téléchargement…'; Bridge.installUpdate(updRepo()); };
    });
  }
  function checkUpdate(manual) { UPD.manual = !!manual; if (manual) toast('Recherche d\'une mise à jour…'); Bridge.checkUpdate(updRepo()); }
  Bridge.on('update', function (d) {
    d = d || {};
    if (d.local) UPD.local = d.local;
    var st = $('#upd-state');
    switch (d.state) {
      case 'available':
        UPD.remote = d.remote; UPD.name = d.name;
        if (UPD.manual) updSheet(); else toast('Mise à jour ' + (d.name || '') + ' disponible : Réglages > Mises à jour');
        break;
      case 'none': if (UPD.manual) toast('Le lanceur est à jour'); break;
      case 'downloading': if (st) st.textContent = 'Téléchargement… ' + (d.progress || 0) + ' %'; break;
      case 'installing': if (st) st.textContent = 'Installation…'; break;
      case 'permission': closeSheet(); toast(d.msg || 'Autorise l\'installation, puis réessaie'); break;
      case 'error': if (UPD.manual || st) { if (st) st.textContent = d.msg || 'Erreur'; else toast(d.msg || 'Erreur'); } break;
      case 'done': closeSheet(); toast('Mise à jour installée'); break;
    }
    if (current === 'settings') renderSettings();
  });
  function autoUpdateCheck() {
    if (!store.get('autoUpdate', true)) return;
    var last = store.get('updCheckedAt', 0);
    if (Date.now() - last < 6 * 3600 * 1000) return;     // au plus toutes les 6 h
    store.set('updCheckedAt', Date.now());
    setTimeout(function () { checkUpdate(false); }, 8000);  // laisse le lanceur démarrer d'abord
  }

  /* ======================= v3.0 : tableau de bord =======================
     Les vraies applis (Waze ou Maps, Spotify) sont posées en fenêtres sur les cadres de l'accueil.
     Le lanceur reste visible autour : barre, vitesse, trajet. Ailleurs (Réglages, Véhicule…) il repasse devant. */
  var stayPage = false;
  function dashOn() { return store.get('dash', true); }
  function dashFree() { var i = Bridge.barInfo() || {}; return !!i.freeform; }
  function pxRect(el) {
    var r = el.getBoundingClientRect(), d = window.devicePixelRatio || 1;
    return { x: Math.round(r.left * d), y: Math.round(r.top * d), w: Math.round(r.width * d), h: Math.round(r.height * d) };
  }
  function frameItem(app, el) { var r = pxRect(el); return { pkg: app.pkg, cls: app.cls || '', x: r.x, y: r.y, w: r.w, h: r.h, label: app.label }; }
  function renderDash() {
    var on = dashOn(); root.classList.toggle('dashon', on);
    if (!on) return;
    var n = findTheme('nav'), m = findTheme('music'), free = dashFree();
    $('#dash-nav-name').textContent = n ? n.label : 'Navigation';
    $('#dash-music-name').textContent = m ? m.label : 'Musique';
    var how = 'Pour l\'afficher ici : Paramètres Android > Options pour les développeurs > active « fenêtres de forme libre » et « forcer le redimensionnement », puis redémarre la tablette. En attendant, touche ce cadre pour l\'ouvrir.';
    $('#dash-nav-msg').textContent = !n ? 'Installe Waze ou Google Maps.' : free ? 'Ouverture…' : how;
    $('#dash-music-msg').textContent = !m ? 'Installe Spotify.' : free ? 'Ouverture…' : 'Touche ce cadre pour ouvrir ' + m.label + '.';
    $('#dash-speed').textContent = Math.round(car.speed || 0);
    $('#dash-trip').textContent = (fmtN(car.trip || 0, 1) || '0') + ' km';
  }
  function tileDash(which) {
    if (!dashOn() || current !== 'home') return;
    var n = findTheme('nav'), m = findTheme('music'), list = [];
    if (which === 'nav' && n) list.push(frameItem(n, $('#stage')));
    else if (which === 'music' && m) list.push(frameItem(m, $('#stage')));
    else {
      if (m) list.push(frameItem(m, $('#dash-music')));
      if (n) list.push(frameItem(n, $('#dash-nav')));       // la carte en dernier : c'est elle qui a la main
    }
    if (list.length && dashFree()) Bridge.tile(list);
  }
  /* sans fenêtres : un appui sur un cadre ouvre l'appli normalement */
  tappable($('#dash-nav'), function () { var n = findTheme('nav'); if (!n) return; if (dashFree()) tileDash(); else openApp(n); });
  tappable($('#dash-music'), function () { var m = findTheme('music'); if (!m) return; if (dashFree()) tileDash(); else openApp(m); });
  tappable($('#dash-big-nav'), function () { if (dashFree()) tileDash('nav'); else { var n = findTheme('nav'); if (n) openApp(n); } });
  tappable($('#dash-big-music'), function () { if (dashFree()) tileDash('music'); else { var m = findTheme('music'); if (m) openApp(m); } });
  tappable($('#dash-retile'), function () { tileDash(); });

  /* son Bluetooth : l'appli « BT Music » du poste doit être active pour que le son du téléphone sorte */
  function findBt() {
    var ra = roleApp('bt'); if (ra) return ra;
    var re = /bt ?music|bluetooth ?music|btmusic|music ?bt|a2dp|bt ?audio|蓝牙音乐/i;
    for (var i = 0; i < apps.length; i++) if (!apps[i].virtual && (re.test(apps[i].label || '') || re.test(apps[i].cls || ''))) return apps[i];
    return null;
  }
  function wakeBt(then) {
    var bt = store.get('btAuto', true) ? findBt() : null;
    if (!bt) { if (then) then(); return; }
    Bridge.launchPlain(bt);
    setTimeout(function () { Bridge.front(); if (then) setTimeout(then, 1200); }, 2200);
  }

  function act(a, el) {
    var p = a.split(':');
    if (a === 'upd:check') { if (UPD.remote > UPD.local && UPD.local) updSheet(); else checkUpdate(true); return; }
    if (a === 'bar:mode') { barSheet(); return; }
    if (a === 'gps:odo') { odoSheet(); return; }
    if (a === 'gps:reset') { gpsReset(); renderSettings(); toast('Trajet remis à zéro'); return; }
    if (a === 'sys:leave') {
      openSheet('<div class="t-headline">Revenir au lanceur d\'origine</div><div class="t-sub">Rien n\'est modifié dans le système : CarPlay, la radio et les autres applis restent intactes.<br><br>' +
        '<b>1.</b> Choisis l\'écran d\'accueil d\'origine de l\'autoradio (souvent « Launcher3 »).<br><b>2.</b> Pour supprimer ce lanceur, reviens ici et touche « Désinstaller ». ' +
        'Les données du lanceur (entretiens, réglages) seront effacées.</div>' +
        '<div class="sheet-actions"><button class="sbtn press" data-l="home" style="background:rgba(255,255,255,.18)">1. Écran d\'accueil</button><button class="sbtn danger press" data-l="uninstall">2. Désinstaller</button></div>', function (sh) {
        $$('[data-l]', sh).forEach(function (b) { b.onclick = function () { closeSheet(); Bridge.openSystem(b.getAttribute('data-l')); }; });
      });
    }
    else if (p[0] === 'sys') Bridge.openSystem(p[1]);
    else if (a === 'wall') wallSheet();
    else if (a === 'theme') { var th = store.get('theme', 'auto'); th = th === 'auto' ? 'dark' : th === 'dark' ? 'light' : 'auto'; store.set('theme', th); applyTheme(); renderSettings(); }
    else if (p[0] === 'role') roleSheet(p[1], $('.grow', el).textContent);
    else if (a === 'autoread') { var ar = store.get('autoread', 'boot'); ar = ar === 'off' ? 'boot' : ar === 'boot' ? '15' : 'off'; store.set('autoread', ar); renderSettings(); }
    else if (a === 'zoom') { var z = store.get('zoom', 'grand'); z = z === 'normal' ? 'grand' : z === 'grand' ? 'xl' : 'normal'; store.set('zoom', z); fit(); renderApps(); renderSettings(); alignGlass(); }
    else if (a === 'can:scan') { toast('Analyse en cours…'); setTimeout(function () { var r = Bridge.canScan(); renderSettings(); toast((r.split('\n')[0] || 'Fait').replace(/^CAN : /, '')); }, 60); }
    else if (a === 'can:export') { var f2 = Bridge.canExport(); toast(f2 ? 'Copié : ' + f2 : 'Copie impossible'); }
    else if (a === 'can:logs') { openSheet('<div class="t-headline">Ordinateur de bord en direct</div><div class="t-sub" style="-webkit-user-select:text;user-select:text">' +
      (Bridge.canLive() ? '<b>Actif</b> : carburant, batterie, température, kilométrage et régime arrivent en direct, CarCan n\'est plus ouvert.' :
       Bridge.canLogs() ? 'Autorisé, en attente des premières données du boîtier CAN (moteur allumé, CarCan installé).' :
       'Une seule fois, depuis un ordinateur relié en USB (débogage USB activé) :<br><br><b>adb shell pm grant fr.ibiza.launcher android.permission.READ_LOGS</b><br><br>puis redémarre l\'autoradio. Le lanceur lira alors les données du CAN en direct, sans ouvrir CarCan.') +
      '</div><div class="sheet-actions">' + (Bridge.canLogs() ? '' : '<button class="sbtn wide press" data-r="1">Essayer l\'autorisation automatique (root)</button>') + '<button class="sbtn wide press" data-x="1">OK</button></div>', function (sh) {
        $('[data-x]', sh).onclick = closeSheet;
        var rb = $('[data-r]', sh); if (rb) rb.onclick = function () { closeSheet(); toast('Essai en cours… accepte la fenêtre « root » si elle apparaît'); Bridge.tryRoot(); };
      }); }
    else if (a === 'icons:reload') { Bridge.reloadIcons(); toast('Icônes rechargées'); }
    else if (a === 'glass') { var m = store.get('glass', 'sim'); m = m === 'sim' ? 'real' : m === 'real' ? 'off' : 'sim'; store.set('glass', m); applyGlassMode(); renderSettings(); }
    else if (p[0] === 'toggle') { var dflt = p[1] !== 'lite'; var v = !store.get(p[1], dflt); store.set(p[1], v); $('.switch', el).classList.toggle('on', v); if (p[1] === 'lite') applyLite(); if (p[1] === 'bar') setTimeout(applyBarMode, 300); if (p[1] === 'dash') { renderDash(); setTimeout(function () { if (v) { showPage('home'); tileDash(); } }, 300); } }
    else if (a === 'test:moving') Bridge.testMoveAlert();
    else if (a === 'test:ready') { showPage('home'); setTimeout(function () { showReady(true); }, 350); }
    else if (p[0] === 'sim') {
      var d = { fl: false, fr: false, rl: false, rr: false, trunk: false, hood: false };
      var v2 = { doors: d, radar: null, reverse: false };
      if (p[1] === 'fl' || p[1] === 'rl' || p[1] === 'trunk') d[p[1]] = true;
      if (p[1] === 'radar') { v2.reverse = true; v2.radar = { rear: [120, 64, 38, 150], front: [null, null, null, null] }; }
      Bridge.simulate(v2);
      if (p[1] !== 'none') setTimeout(function () { showPage('home', $('#btn-pages')); }, 150);
    }
    else if (a === 'diag:export') { var f = Bridge.diagExport(); toast(f ? 'Enregistré : ' + f : 'Export impossible'); }
    else if (a === 'diag:clear') { Bridge.diagClear(); renderSettings(); }
  }
  tappable($('#card-clock'), function () {}, function () { showPage('settings', $('#card-clock')); });

  /* ======================= démarrage ======================= */
  function boot() {
    fit(); applyLite();
    applyGlassMode(); applyTheme();
    loadApps(); renderDock(); renderQuick(); renderApps();
    $('#btn-pages').innerHTML = svg('grid');
    $('#st-bt').innerHTML = svg('bt'); $('#st-wifi').innerHTML = svg('wifi');
    var st = Bridge.state();
    renderMedia(st.media); onVehicle(st.vehicle); renderCd();
    if (NOCAN) { renderNocarHome(); renderVehicleSummary(); setVehView('trip'); }
    applyBarMode();
    UPD.local = Bridge.appVersion() || 0;
    autoUpdateCheck();
    /* v2.4 : au démarrage du poste, Waze s'ouvre à côté de la barre (une fois) */
    renderDash();
    if (Bridge.firstStart()) {
      setTimeout(function () {
        wakeBt(function () {
          if (dashOn()) { tileDash(); return; }
          var n = findTheme('nav');
          if (store.get('autoNav', true) && n && (roleApp('nav') || PREFER.nav.some(function (x) { return String(n.pkg).indexOf(x) === 0; }))) openApp(n);
        });
      }, 2500);
    }
    tick(); setInterval(function () { tick(); applyTheme(); applyWall(); }, 1000 * 5);
    $('#wall-file').addEventListener('change', function (e) { var f = e.target.files && e.target.files[0]; if (f) loadCustomWall(f); e.target.value = ''; });
    alignGlass();
    Object.keys(pageSprings).forEach(function () {});
    setTimeout(introSpin, 250);
  }
  /* données véhicule (CAN) : huile, eau, extérieur, batterie */
  function setCd(id, v, unit, warnIf) {
    var el = $(id); if (!el) return;
    if (v == null || v === '' || isNaN(v)) { el.textContent = '—'; el.classList.remove('warn'); return; }
    el.textContent = (unit === 'V' ? Number(v).toFixed(1).replace('.', ',') : Math.round(v)) + (unit === 'V' ? ' V' : '°');
    el.classList.toggle('warn', !!(warnIf && warnIf(v)));
  }

  /* ======================= v1.13 : écran « Prêt à partir » (plein écran) =======================
     Au démarrage de l'autoradio (et au réveil après plus de 15 min de veille), l'écran s'affiche tout de suite en
     « lecture… » (aucune valeur périmée), puis la lecture CarCan se fait sous le rideau : c'est cet écran qu'on voit
     pendant la lecture, jamais CarCan. Les vraies valeurs s'affichent au retour ; « C'est parti » ramène à l'accueil. */
  var RD = { tick: Date.now(), state: 'off', mv: 0, t: null, wait: null };
  function washerLow(w) { return !!w && !/^\s*(normal|ok|plein|full|bon)\s*$/i.test(w) && /anormal|bas|faible|low|insuf|vide|empty|manque|ajout|rempli|add|niveau/i.test(w); }
  function readyAlerts() {
    var a = [], c = car || {};
    if (c.battery != null && c.battery < 12) a.push({ lv: 2, ic: 'battery', t: 'Batterie faible', s: fmtN(c.battery, 1) + ' V : à faire contrôler' });
    if (washerLow(c.washer)) a.push({ lv: 1, ic: 'gauge', t: 'Lave-glace à remplir', s: String(c.washer) });
    if (c.outside != null && c.outside <= 3) a.push({ lv: 1, ic: 'temp', t: 'Risque de verglas', s: fmtN(c.outside, 1) + ' °C dehors : prudence' });
    if ((c.range != null && c.range < 60) || (c.fuel != null && c.fuel < 7)) a.push({ lv: 1, ic: 'fuel', t: 'Réserve de carburant', s: c.range != null ? 'Autonomie ' + fmtN(c.range) + ' km' : fmtN(c.fuel) + ' L restants' });
    var m = store.get('maint', null);
    if (m) maintDefs(m).forEach(function (d) { var st = maintState(m[d[0]] || {}); if (st && st.level >= 1) a.push({ lv: st.level, ic: 'wrench', t: d[1], s: (st.level === 2 ? '' : 'Bientôt : ') + leftText(st) }); });
    var o = openList(); if (o.length) a.push({ lv: 1, ic: 'door', t: o.length === 1 ? o[0][1] + ' ouvert' + (o[0][0] === 'trunk' || o[0][0] === 'hood' ? '' : 'e') : o.length + ' ouvrants ouverts', s: 'À fermer avant de partir' });
    a.sort(function (x, y) { return y.lv - x.lv; });
    return a;
  }
  function readyRender() {
    var el = $('#ready'), c = car || {}, now = new Date(), h = now.getHours(), loading = RD.state === 'loading';
    var hello = h >= 5 && h < 12 ? 'Bonjour' : h >= 12 && h < 18 ? 'Bon après-midi' : 'Bonsoir';
    var days = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi'], months = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
    var nx = maintNext();
    var tile = function (ic, label, val, warn) { return '<div class="rdf-tile' + (warn ? ' warn' : '') + '">' + svg(ic) + '<span>' + label + '</span><b>' + (loading ? '<i class="sk"></i>' : val) + '</b></div>'; };
    var lowF = (c.range != null && c.range < 60) || (c.fuel != null && c.fuel < 7);
    var tiles = tile('fuel', c.range != null || loading ? 'Autonomie' : 'Carburant', c.range != null ? fmtN(c.range) + ' km' : c.fuel != null ? fmtN(c.fuel) + ' L' : '—', !loading && lowF) +
      tile('temp', 'Extérieur', c.outside != null ? fmtN(c.outside, 1) + ' °C' : '—', !loading && c.outside != null && c.outside <= 3) +
      tile('battery', 'Batterie', c.battery != null ? fmtN(c.battery, 1) + ' V' : '—', !loading && c.battery != null && c.battery < 12) +
      tile('wrench', 'Entretien', nx ? esc(nx.name) + '<em>' + esc(nx.short) + '</em>' : 'À jour', !loading && nx && nx.level >= 1);
    var body;
    if (loading) body = '<div class="rdf-load"><i class="spin"></i>Lecture des données du véhicule…</div>';
    else {
      var al = readyAlerts();
      body = !al.length ? '<div class="rdf-ok">' + svg('check') + '<div><b>Tout est en ordre</b><span>Bonne route !</span></div></div>'
        : '<div class="rdf-list">' + al.slice(0, 4).map(function (x) { return '<div class="rdf-al lv' + x.lv + '">' + svg(x.ic) + '<div><b>' + esc(x.t) + '</b><span>' + esc(x.s) + '</span></div></div>'; }).join('') + '</div>' +
          (al.length > 4 ? '<div class="rdf-more">+ ' + esc(al.slice(4).map(function (x) { return x.t; }).join(', ')) + '</div>' : '');
      if (RD.state === 'stale') body += '<div class="rdf-note">Lecture de l\'ordinateur de bord impossible pour l\'instant : dernières valeurs connues' + (car.at ? ' (' + frDate(new Date(car.at)) + ')' : '') + '.</div>';
    }
    el.innerHTML = '<div class="rdf-glow"></div><div class="rdf-in">' +
      '<div class="rdf-head"><div><div class="rdf-hello">' + hello + '</div><div class="rdf-sub">Prêt à partir · ' + days[now.getDay()] + ' ' + now.getDate() + ' ' + months[now.getMonth()] + (!loading && c.odo != null ? ' · ' + fmtN(Math.round(c.odo)) + ' km' : '') + '</div></div>' +
      '<div class="rdf-time">' + pad(h) + ':' + pad(now.getMinutes()) + '</div></div>' +
      '<div class="rdf-tiles">' + tiles + '</div><div class="rdf-body">' + body + '</div>' +
      '<button class="rdf-go press" id="rdf-go">C\'est parti</button></div>';
    $('#rdf-go').onclick = hideReady;
  }
  function readyOpen() { return RD.state !== 'off'; }
  function showReady(force) {
    if (!force && (NOCAN || !store.get('ready', true) || CK.on)) return false;   // sans CAN : rien à lire au démarrage
    var canRead = !Bridge.mock && Bridge.canRefreshPossible() && store.get('autoread', 'boot') !== 'off';
    if (current !== 'home') showPage('home');
    if ($('#sheet-scrim').classList.contains('on')) closeSheet();
    RD.state = canRead ? 'loading' : (Bridge.mock ? 'fresh' : 'stale'); RD.mv = 0;
    readyRender();
    var el = $('#ready'); el.classList.add('on'); el.setAttribute('aria-hidden', 'false');
    clearTimeout(RD.wait); clearTimeout(RD.t);
    if (canRead) {
      // la lecture CarCan démarre une fois l'écran affiché : le rideau en fait une image figée, CarCan n'apparaît jamais
      setTimeout(readyRead, Bridge.canLive() ? 300 : RD.boot ? 5000 : 1500);   // v1.15 : données déjà en direct -> immédiat
    }
    RD.boot = false;
    RD.t = setTimeout(function () { if (readyOpen()) hideReady(); }, 5 * 60000);
    return true;
  }
  function readyRead() {          // jamais par-dessus CarPlay ou une autre appli : on attend que le lanceur soit affiché
    if (RD.state !== 'loading') return;
    if (!visible) { RD.pending = true; return; }
    RD.pending = false; lastAutoRead = Date.now(); Bridge.canRefresh();
    clearTimeout(RD.wait); RD.wait = setTimeout(function () { if (RD.state === 'loading') { RD.state = 'stale'; readyRender(); } }, 12000);
  }
  function hideReady() {
    if (!readyOpen()) return;
    RD.state = 'off'; clearTimeout(RD.wait); clearTimeout(RD.t);
    var el = $('#ready'); el.classList.remove('on'); el.setAttribute('aria-hidden', 'true');
    if (current !== 'home') showPage('home');
    renderVehicleSummary(); renderCd();
  }
  Bridge.on('car', function (d) {          // valeurs fraîches : l'écran passe de « lecture… » aux vraies valeurs
    if (!readyOpen() || !d || Object.keys(d).length < 2) return;
    RD.state = 'fresh'; clearTimeout(RD.wait);
    setTimeout(function () { readyRender(); $('#ready').classList.add('pop'); setTimeout(function () { $('#ready').classList.remove('pop'); }, 500); }, 30);
  });
  Bridge.on('resume', function (d) {        // retour de la lecture masquée sans valeurs : on montre les dernières connues
    if (d && d.silent && RD.state === 'loading') setTimeout(function () { if (RD.state === 'loading') { RD.state = 'stale'; readyRender(); } }, 2500);
    else if (!(d && d.silent) && RD.pending) setTimeout(readyRead, 1200);
  });
  Bridge.on('vehicle', function () { if (readyOpen() && RD.state !== 'loading') setTimeout(readyRender, 50); });
  Bridge.on('speed', function (d) {          // on roule depuis 3 s : l'écran s'efface
    var k = d && d.kmh ? d.kmh : 0;
    if (!readyOpen()) return;
    if (k >= 10) { if (!RD.mv) RD.mv = Date.now(); else if (Date.now() - RD.mv > 3000) hideReady(); } else RD.mv = 0;
  });
  RD.boot = true;
  setTimeout(function () { if (!showReady(false)) bootRead = true; }, 300);      // démarrage de l'autoradio
  setInterval(function () {                                                          // réveil après une veille
    var now = Date.now();
    if (now - RD.tick > 15 * 60000) setTimeout(function () { showReady(false); }, 800);
    RD.tick = now;
  }, 20000);

  /* ======================= Entretien : vue « poste de conduite » =======================
     Vidéo pré-rendue (la caméra tourne autour de la voiture, passe par la lunette arrière et s'installe
     au milieu de l'habitacle), qui se fond dans la photo du tableau de bord ; la carte Entretien glisse ensuite à droite. */
  var CK = { on: false, timer: null };
  function ckLayout() { // fond flou de la carte aligné au pixel sur la photo (object-fit: cover)
    var ov = $('#cockpit'), card = $('#ck-card'), s = Math.max(VW / 1024, VH / 600), bw = 1024 * s, bh = 600 * s;
    ov.style.setProperty('--ck-bw', bw + 'px'); ov.style.setProperty('--ck-bh', bh + 'px');
    ov.style.setProperty('--ck-bx', ((VW - bw) / 2 - card.offsetLeft) + 'px'); ov.style.setProperty('--ck-by', ((VH - bh) / 2 - card.offsetTop) + 'px');
  }
  function openCockpit() {
    if (CK.on) return; CK.on = true;
    var ov = $('#cockpit'), v = $('#ck-video'), ph = $('#ck-photo');
    if (!ph.getAttribute('src')) ph.setAttribute('src', 'img/cockpit/dash.webp');
    renderMaint(); ckLayout(); hideLive(true);
    ov.classList.remove('card-in'); v.classList.remove('done');
    ov.classList.add('on'); ov.setAttribute('aria-hidden', 'false');
    var finish = function () { if (!CK.on) return; clearTimeout(CK.timer); v.classList.add('done'); ov.classList.add('card-in'); };
    if (Motion.reduced || lite) { v.classList.add('done'); CK.timer = setTimeout(finish, 150); return; }
    if (!v.getAttribute('src')) v.setAttribute('src', 'img/cockpit/intro.mp4');
    v.onended = finish; v.onerror = finish;
    try { v.currentTime = 0; } catch (e) {}
    var p = v.play(); if (p && p.catch) p.catch(finish);
    CK.timer = setTimeout(finish, 5000);   // filet de sécurité si la vidéo ne démarre pas
  }
  function closeCockpit() {
    if (!CK.on) return; CK.on = false; clearTimeout(CK.timer);
    var ov = $('#cockpit'), v = $('#ck-video');
    ov.classList.remove('card-in');
    setTimeout(function () { ov.classList.remove('on'); ov.setAttribute('aria-hidden', 'true'); try { v.pause(); } catch (e) {} }, 200);
  }
  $('#ck-close').innerHTML = svg('back');
  tappable($('#ck-close'), closeCockpit);
  window.addEventListener('resize', function () { if (CK.on) setTimeout(ckLayout, 60); });

  /* ======================= entretien saisi à la main =======================
     Chaque opération : dernière fois (date + km) et intervalle (km et/ou mois), « au premier des deux termes ».
     Valeurs par défaut = plan d'entretien fixe habituel Seat Ibiza (à vérifier dans le carnet, modifiables). */
  var MAINT_DEF = [
    ['oil', 'Vidange + filtre à huile', 15000, 12, 'fuel', 'Vidange'],
    ['insp', 'Révision (inspection)', 30000, 24, 'wrench', 'Révision'],
    ['air', 'Filtre à air', 60000, 48, 'gauge', 'Filtre à air'],
    ['cabin', 'Filtre d\'habitacle', 30000, 24, 'gauge', 'Filtre habitacle'],
    ['fuelf', 'Filtre à gasoil', 60000, 0, 'fuel', 'Filtre gasoil', 'diesel'],
    ['plugs', 'Bougies d\'allumage', 60000, 48, 'gauge', 'Bougies', 'essence'],
    ['brakefl', 'Liquide de frein', 0, 24, 'car', 'Liquide frein'],
    ['belt', 'Courroie de distribution', 120000, 60, 'gauge', 'Distribution'],
    ['ct', 'Contrôle technique', 0, 24, 'check', 'Contrôle tech.']
  ];
  /* v1.11 : motorisation (diesel par défaut : filtre à gasoil, pas de bougies) + entretiens libres ajoutés à la main */
  function engine() { return store.get('engine', CAR.fuel || 'diesel'); }
  function maintDefs(m) {
    m = m || store.get('maint', null) || {};
    var e = engine(), out = MAINT_DEF.filter(function (d) { return !d[6] || d[6] === e; });
    Object.keys(m).filter(function (k) { return m[k] && m[k].custom; }).sort(function (x, y) { return (m[x].created || 0) - (m[y].created || 0); })
      .forEach(function (k) { out.push([k, m[k].name || 'Entretien', m[k].km, m[k].months, 'wrench', m[k].name || 'Entretien']); });
    return out;
  }
  function maintData() {
    var m = store.get('maint', null) || {};
    MAINT_DEF.forEach(function (d) {
      if (!m[d[0]]) m[d[0]] = { km: d[2], months: d[3], on: true, last: null, hist: [] };
    });
    return m;
  }
  function maintSave(m) { store.set('maint', m); renderVehicleSummary(); if (CK.on || (current === 'vehicle' && vehView === 'maint')) renderMaint(); }
  function curKm() { return car.odo != null ? Math.round(car.odo) : null; }
  function addMonths(iso, n) { var d = new Date(iso + 'T12:00:00'); d.setMonth(d.getMonth() + n); return d; }
  function frDate(d) { if (typeof d === 'string') d = new Date(d + 'T12:00:00'); return pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear(); }
  function todayIso() { var d = new Date(); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  /* état d'une opération : km et jours restants, niveau 0 ok / 1 bientôt / 2 dépassé */
  function maintState(it) {
    if (!it.on || !it.last) return null;
    var odo = curKm(), kmLeft = null, daysLeft = null;
    if (it.km > 0 && it.last.km != null && odo != null) kmLeft = it.last.km + it.km - odo;
    if (it.months > 0 && it.last.date) daysLeft = Math.round((addMonths(it.last.date, it.months) - Date.now()) / 864e5);
    if (kmLeft == null && daysLeft == null) return null;
    var level = (kmLeft != null && kmLeft < 0) || (daysLeft != null && daysLeft < 0) ? 2 : (kmLeft != null && kmLeft < 1500) || (daysLeft != null && daysLeft < 45) ? 1 : 0;
    // part déjà « consommée » de l'intervalle (pour la barre)
    var pk = kmLeft != null ? 1 - kmLeft / it.km : 0, pd = daysLeft != null ? 1 - daysLeft / (it.months * 30.44) : 0;
    return { kmLeft: kmLeft, daysLeft: daysLeft, level: level, used: Math.max(0, Math.min(1, Math.max(pk, pd))) };
  }
  function leftText(st) {
    if (!st) return '';
    if (st.level === 2) {
      var o = []; if (st.kmLeft != null && st.kmLeft < 0) o.push(fmtN(-st.kmLeft) + ' km'); if (st.daysLeft != null && st.daysLeft < 0) o.push(fmtN(-st.daysLeft) + ' j');
      return 'Dépassé de ' + o.join(' · ');
    }
    var p = []; if (st.kmLeft != null) p.push(fmtN(st.kmLeft) + ' km');
    if (st.daysLeft != null) p.push(st.daysLeft > 60 ? Math.round(st.daysLeft / 30.44) + ' mois' : st.daysLeft + ' j');
    return 'Dans ' + p.join(' · ');
  }
  function ivText(it) {
    var mo = it.months > 0 ? (it.months % 12 === 0 ? it.months / 12 + ' an' + (it.months > 12 ? 's' : '') : it.months + ' mois') : '';
    return (it.km > 0 ? fmtN(it.km) + ' km' : '') + (it.km > 0 && it.months > 0 ? ' / ' : '') + mo;
  }
  /* la prochaine échéance (pour l'accueil) */
  function maintNext() {
    var m = store.get('maint', null); if (!m) return null;
    var best = null;
    maintDefs(m).forEach(function (d) {
      var st = maintState(m[d[0]] || {}); if (!st) return;
      var score = Math.min(st.kmLeft != null ? st.kmLeft / 15000 : 9, st.daysLeft != null ? st.daysLeft / 365 : 9);
      if (!best || score < best.score) best = { score: score, name: d[5], st: st, level: st.level };
    });
    if (!best) return null;
    best.short = leftText(best.st);
    return best;
  }
  function renderMaint() {
    var m = maintData(), odo = curKm();
    var h = '<div class="maint-head"><span>' + (odo != null ? 'Kilométrage : <b>' + fmtN(odo) + ' km</b>' : 'Kilométrage inconnu : ouvre l\'écran ordinateur de bord de CarCan') + '</span>' +
      '<span class="maint-tools"><button class="chip-btn press" id="m-engine">' + (engine() === 'diesel' ? 'Diesel' : 'Essence') + '</button>' +
      '<button class="chip-btn press" id="m-pdf">' + svg('files') + 'PDF</button></span></div><div class="maint-list">';
    maintDefs(m).forEach(function (d) {
      var it = m[d[0]], st = maintState(it), iv = ivText(it);
      var sub = !it.on ? 'Désactivé' : it.last ? 'Fait le ' + frDate(it.last.date) + (it.last.km != null ? ' à ' + fmtN(it.last.km) + ' km' : '') : 'Pas encore renseigné';
      h += '<div class="mrow press-soft' + (st ? ' lv' + st.level : '') + (it.on ? '' : ' off') + '" data-m="' + d[0] + '">' + svg(d[4]) +
        '<div class="mmain"><b>' + esc(d[1]) + '</b><span>' + sub + (iv ? ' · tous les ' + iv : '') + '</span>' + (st ? '<i class="mbar"><i style="width:' + Math.round(st.used * 100) + '%"></i></i>' : '') + '</div>' +
        '<div class="mleft">' + (st ? leftText(st) : it.on ? '—' : '') + '</div></div>';
    });
    h += '<div class="mrow madd press-soft" data-m="+">' + svg('plus') + '<div class="mmain"><b>Ajouter un entretien</b><span>Pneus, amortisseurs, climatisation, batterie…</span></div></div>';
    h += '</div>';
    var box = CK.on ? $('#ck-body') : $('#maintview');
    box.innerHTML = h;
    $$('.mrow', box).forEach(function (r) { tappable(r, function () { var k = r.getAttribute('data-m'); if (k === '+') maintSheet(null); else maintSheet(k); }); });
    tappable($('#m-pdf', box), maintPdf);
    tappable($('#m-engine', box), function () {
      openSheet('<div class="t-headline">Motorisation</div><div class="t-sub">Adapte la liste : filtre à gasoil pour un diesel, bougies pour un essence.</div>' +
        '<div class="sheet-actions"><button class="sbtn press" data-e="diesel">Diesel</button><button class="sbtn press" data-e="essence">Essence</button></div>', function (sh) {
        $$('[data-e]', sh).forEach(function (b) { if (b.getAttribute('data-e') === engine()) b.style.background = 'rgba(255,255,255,.22)'; b.onclick = function () { store.set('engine', b.getAttribute('data-e')); closeSheet(); renderMaint(); renderVehicleSummary(); }; });
      });
    });
  }
  /* fiche d'une opération (id = null : nouvel entretien libre) */
  function maintSheet(id) {
    var m = maintData(), isNew = id == null, it = isNew ? { km: 0, months: 12, on: true, last: null, hist: [], custom: true } : m[id];
    var def = isNew ? [null, '', 0, 12, 'wrench', ''] : maintDefs(m).filter(function (d) { return d[0] === id; })[0] || [id, it.name || id];
    var custom = !!it.custom, odo = curKm(), last = it.last || {};
    var hist = (it.hist || []).slice(-4).reverse().map(function (e) { return '<div class="mh">' + frDate(e.date) + (e.km != null ? ' · ' + fmtN(e.km) + ' km' : '') + '</div>'; }).join('');
    var h = '<div class="t-headline">' + (isNew ? 'Nouvel entretien' : esc(def[1])) + '</div>' +
      '<div class="t-sub">' + (custom ? 'Laisse un intervalle à 0 s\'il ne s\'applique pas.' : 'Au premier des deux termes atteint. Valeurs à vérifier dans ton carnet d\'entretien.') + '</div>' +
      (custom ? '<div class="mform"><label style="grid-column:1 / span 2">Nom<input type="text" id="m-name" maxlength="40" placeholder="Ex. : pneus avant, amortisseurs, recharge clim" value="' + esc(it.name || '') + '"></label></div>' : '') +
      (odo != null ? '<button class="sbtn wide press" id="m-today" style="margin-top:12px;width:100%;background:var(--green);color:#fff">Fait aujourd\'hui à ' + fmtN(odo) + ' km</button>' : '') +
      '<div class="mform">' +
      '<label>Fait le<input type="date" id="m-date" value="' + (last.date || todayIso()) + '"></label>' +
      '<label>À (km)<input type="number" inputmode="numeric" id="m-km" value="' + (last.km != null ? last.km : odo != null ? odo : '') + '"></label>' +
      '<label>Tous les (km, 0 = aucun)<input type="number" inputmode="numeric" id="m-ikm" value="' + it.km + '"></label>' +
      '<label>Ou tous les (mois)<input type="number" inputmode="numeric" id="m-imo" value="' + it.months + '"></label>' +
      (custom ? '<label style="grid-column:1 / span 2">Note (pièce, garage, prix…)<input type="text" id="m-note" maxlength="80" value="' + esc(it.note || '') + '"></label>' : '') +
      '</div>' + (hist ? '<div class="hdr" style="padding-left:0">Historique</div>' + hist : '') +
      '<div class="sheet-actions"><button class="sbtn press" data-act="save" style="background:rgba(255,255,255,.18)">Enregistrer</button>' +
      (isNew ? '<button class="sbtn press" data-act="cancel">Annuler</button>' : custom ? '<button class="sbtn danger press" data-act="del">Supprimer</button>' : '<button class="sbtn press" data-act="toggle">' + (it.on ? 'Désactiver' : 'Activer') + '</button>') + '</div>';
    openSheet(h, function (sh) {
      // le clavier de l'autoradio couvre le bas de l'écran : on remonte la feuille pendant la saisie
      $$('input', sh).forEach(function (el) {
        el.addEventListener('focus', function () { sh.style.top = (sh.offsetHeight / 2 + 8) + 'px'; });
        el.addEventListener('blur', function () { setTimeout(function () { if (!sh.contains(document.activeElement)) sh.style.top = ''; }, 50); });
      });
      function record(date, km, done) {
        var mm = maintData(), key = id, x;
        if (custom) {
          var nm = ($('#m-name', sh).value || '').trim();
          if (!nm) { toast('Donne un nom à cet entretien'); $('#m-name', sh).focus(); return; }
          if (isNew) { key = 'x' + Date.now(); x = { custom: true, created: Date.now(), on: true, last: null, hist: [] }; } else x = mm[key];
          x.name = nm; x.note = ($('#m-note', sh).value || '').trim();
        } else x = mm[key];
        if (done) {
          x.last = { date: date, km: km };
          x.hist = (x.hist || []).filter(function (e) { return !(e.date === date && e.km === km); }); x.hist.push({ date: date, km: km });
        }
        x.km = Math.max(0, parseInt($('#m-ikm', sh).value, 10) || 0); x.months = Math.max(0, parseInt($('#m-imo', sh).value, 10) || 0);
        x.on = true; mm[key] = x; maintSave(mm); closeSheet(); toast((x.name || def[1]) + ' enregistré');
      }
      var t = $('#m-today', sh); if (t) t.onclick = function () { record(todayIso(), odo, true); };
      $('[data-act="save"]', sh).onclick = function () {
        var date = $('#m-date', sh).value || todayIso(), km = parseInt($('#m-km', sh).value, 10);
        record(date, isNaN(km) ? null : km, true);
      };
      var tg = $('[data-act="toggle"]', sh); if (tg) tg.onclick = function () { var mm = maintData(); mm[id].on = !mm[id].on; maintSave(mm); closeSheet(); };
      var cc = $('[data-act="cancel"]', sh); if (cc) cc.onclick = closeSheet;
      var dl = $('[data-act="del"]', sh); if (dl) dl.onclick = function () {
        if (dl.getAttribute('data-sure')) { var mm = maintData(); var nm = mm[id].name; delete mm[id]; maintSave(mm); closeSheet(); toast(nm + ' supprimé'); }
        else { dl.setAttribute('data-sure', '1'); dl.textContent = 'Confirmer la suppression'; }
      };
    });
  }
  /* export PDF du carnet : le lanceur prépare les données, Android dessine le PDF (A4) et l'enregistre dans Téléchargements/Ibiza */
  function maintPdf() {
    var m = maintData(), odo = curKm(), items = [];
    maintDefs(m).forEach(function (d) {
      var it = m[d[0]], st = maintState(it), next = [];
      if (it.on && it.last) {
        if (it.km > 0 && it.last.km != null) next.push(fmtN(it.last.km + it.km) + ' km');
        if (it.months > 0 && it.last.date) next.push(frDate(addMonths(it.last.date, it.months)));
      }
      items.push({ name: d[1], on: !!it.on, custom: !!it.custom, note: it.note || '',
        last: it.last ? frDate(it.last.date) + (it.last.km != null ? ' · ' + fmtN(it.last.km) + ' km' : '') : '',
        interval: ivText(it), next: next.join(' ou '), left: st ? leftText(st) : '', level: st ? st.level : -1,
        hist: (it.hist || []).slice().reverse().map(function (e) { return frDate(e.date) + (e.km != null ? ' · ' + fmtN(e.km) + ' km' : ''); }) });
    });
    var rep = { title: 'Carnet d\'entretien', car: CAR.model, engine: engine() === 'diesel' ? 'Diesel' : 'Essence', date: frDate(new Date()),
      km: odo != null ? fmtN(odo) + ' km' : '', items: items, file: 'Entretien-' + String(CAR.name).replace(/[^A-Za-z0-9-]+/g, '-') + '-' + todayIso() + '.pdf' };
    if (!Bridge.exportPdf(JSON.stringify(rep))) toast('Export PDF indisponible');
  }
  Bridge.on('pdf', function (r) {
    if (!r || !r.ok) { toast('PDF : échec de l\'enregistrement' + (r && r.err ? ' (' + r.err + ')' : '')); return; }
    openSheet('<div class="t-headline">PDF enregistré</div><div class="t-sub">' + esc(r.where || '') + '</div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-a="open" style="background:rgba(255,255,255,.18)">Ouvrir</button><button class="sbtn press" data-a="share">Partager</button></div>', function (sh) {
      $('[data-a="open"]', sh).onclick = function () { closeSheet(); Bridge.openPdf(); };
      $('[data-a="share"]', sh).onclick = function () { closeSheet(); Bridge.sharePdf(); };
    });
  });

  /* carte horloge : les 4 premières données disponibles (selon ce que le boîtier CAN fournit) */
  var CD = [
    ['outside', 'Ext.', function (v) { return fmtN(v, 1) + '°'; }, function (v) { return v <= 3; }],
    ['coolant', 'Eau', function (v) { return fmtN(v) + '°'; }, function (v) { return v > 110; }],
    ['oil', 'Huile', function (v) { return fmtN(v) + '°'; }, function (v) { return v > 130; }],
    ['battery', 'Batt.', function (v) { return fmtN(v, 1) + ' V'; }, function (v) { return v < 11.8; }],
    ['fuel', 'Carbu.', function (v) { return fmtN(v) + ' L'; }, function (v) { return v < 7; }],
    ['range', 'Auton.', function (v) { return fmtN(v) + ' km'; }, function (v) { return v < 60; }],
    ['rpm', 'Régime', function (v) { return fmtN(v); }, null],
    ['odo', 'Km', function (v) { return fmtN(v); }, null]
  ];
  if (NOCAN) CD = [
    ['trip', 'Trajet', function (v) { return fmtN(v, 1) + ' km'; }, null],
    ['avg', 'Moy.', function (v) { return fmtN(v) + ' km/h'; }, null],
    ['drive', 'Durée', hhmm, null],
    ['odo', 'Km', function (v) { return fmtN(v); }, null]
  ];
  function renderCd() {
    if (NOCAN) { CD.forEach(function (c, i) { var l = $('#cdl-' + i), b = $('#cd-' + i); if (!l || !b) return; l.textContent = c[1]; b.textContent = car[c[0]] == null ? '—' : c[2](car[c[0]]); b.classList.remove('warn'); }); return; }
    var have = CD.filter(function (c) { return car[c[0]] != null; });
    var rest = CD.filter(function (c) { return car[c[0]] == null && ['outside', 'battery', 'fuel', 'rpm'].indexOf(c[0]) >= 0; });
    var pick = have.concat(rest).slice(0, 4);
    pick.forEach(function (c, i) {
      var l = $('#cdl-' + i), b = $('#cd-' + i); if (!l || !b) return;
      l.textContent = c[1];
      var v = car[c[0]];
      b.textContent = v == null ? '—' : c[2](v);
      b.classList.toggle('warn', v != null && !!(c[3] && c[3](v)));
    });
  }
  Bridge.on('car', function (d) {
    if (NOCAN) return;          // pas de boîtier : les valeurs viennent du GPS (plus bas)
    d = d || {};
    Object.keys(d).forEach(function (k) { car[k] = d[k]; }); if (!car.at) car.at = Date.now();
    store.set('car', car);
    d = car;
    renderVehicleSummary(); if (current === 'vehicle' && vehView === 'trip') renderTrip(); if (CK.on || (current === 'vehicle' && vehView === 'maint')) renderMaint();
    renderCd();
  });
  var stopT;
  Bridge.on('speed', function (d) {
    var k = d && d.kmh ? d.kmh : 0, card = $('#card-clock');
    $('#c-kmh').textContent = k;
    if (k >= 4) { clearTimeout(stopT); card.classList.add('moving'); }
    else { clearTimeout(stopT); stopT = setTimeout(function () { card.classList.remove('moving'); }, 4000); }
  });

  /* ======================= sans boîtier CAN : ordinateur de bord calculé par le GPS =======================
     distance = vitesse GPS intégrée dans le temps ; kilométrage = valeur saisie une fois + km parcourus depuis. */
  var GT = store.get('gpsTrip', null) || { km: 0, sec: 0, vmax: 0, odoBase: null, odoAdd: 0, day: todayIso() };
  var gLast = 0, gSaved = 0;
  function gpsApply() {
    car.trip = GT.km; car.drive = GT.sec; car.vmax = GT.vmax || null;
    car.avg = GT.sec > 60 ? GT.km / (GT.sec / 3600) : null;
    car.odo = GT.odoBase != null ? Math.round(GT.odoBase + GT.odoAdd) : null;
  }
  function gpsSave() { gSaved = Date.now(); store.set('gpsTrip', GT); }
  function gpsRefresh() {
    renderCd(); renderVehicleSummary(); renderNocarHome();
    if (dashOn()) { $('#dash-speed').textContent = Math.round(car.speed || 0); $('#dash-trip').textContent = (fmtN(car.trip || 0, 1) || '0') + ' km'; }
    if (current === 'vehicle' && vehView === 'trip') renderTrip();
    if (current === 'vehicle') renderVehicle();
  }
  /* v2.1 : sur l'autoradio, le service Android compte le trajet même quand Waze est devant (événement « trip ») ;
     ici on ne fait que reprendre ses totaux. Le calcul local ci-dessous ne sert qu'en secours (navigateur, ancienne version). */
  var javaTrip = false, javaTotal = null;
  function fromService(d) {
    if (!d || d.km == null) return;
    javaTrip = true;
    GT.km = +d.km || 0; GT.sec = +d.sec || 0; GT.vmax = +d.vmax || 0; GT.day = d.day || todayIso();
    javaTotal = +d.total || 0;
    if (GT.odoBase != null) {
      if (GT.odoTotal0 == null) GT.odoTotal0 = javaTotal - (GT.odoAdd || 0);   // reprise d'un kilométrage saisi avant la v2.1
      GT.odoAdd = Math.max(0, javaTotal - GT.odoTotal0);
    }
    if (d.kmh != null && d.running) car.speed = +d.kmh || 0;
  }
  function gpsReset() {
    GT.km = 0; GT.sec = 0; GT.vmax = 0; GT.day = todayIso();
    Bridge.tripReset();
    gpsSave(); gpsApply(); gpsRefresh();
  }
  function odoSheet() {
    openSheet('<div class="t-headline">Kilométrage du compteur</div><div class="t-sub">Recopie le kilométrage affiché au tableau de bord. Les kilomètres parcourus s\'ajoutent ensuite tout seuls.</div>' +
      '<input id="odo-in" class="field" type="number" inputmode="numeric" placeholder="ex. 142 350" value="' + (car.odo != null ? car.odo : '') + '">' +
      '<div class="sheet-actions"><button class="sbtn press" data-a="cancel">Annuler</button><button class="sbtn primary press" data-a="ok">Enregistrer</button></div>', function (s) {
      $('[data-a="cancel"]', s).onclick = closeSheet;
      $('[data-a="ok"]', s).onclick = function () {
        var v = parseInt(String($('#odo-in', s).value).replace(/\s/g, ''), 10);
        if (isNaN(v) || v < 0) { toast('Kilométrage invalide'); return; }
        GT.odoBase = v; GT.odoAdd = 0; GT.odoTotal0 = javaTotal;   // les km comptés par le service s'ajoutent à partir d'ici
        gpsSave(); gpsApply(); closeSheet(); gpsRefresh(); toast('Kilométrage enregistré');
      };
    });
  }
  /* carte d'accueil sans pack 3D : vitesse quand on roule, sinon le résumé du jour */
  function renderNocarHome() {
    var el = $('#nocar-home'); if (!el) return;
    var moving = (car.speed || 0) >= 4;
    el.classList.toggle('moving', moving);
    el.innerHTML =
      '<div class="nh-speed"><b>' + Math.round(car.speed || 0) + '</b><span>km/h</span></div>' +
      '<div class="nh-day"><span>Trajet du jour</span><b>' + (fmtN(car.trip || 0, 1) || '0') + '<small> km</small></b>' +
      '<div class="nh-sub">' + (car.drive ? hhmm(car.drive) + ' de conduite' : 'Pas encore roulé aujourd\'hui') + '</div></div>' +
      '<div class="nh-odo">' + (car.odo != null ? '<span>Compteur</span><b>' + fmtN(car.odo) + ' km</b>' : '<button class="chip-btn press" id="nh-odo">' + svg('gauge') + 'Saisir le kilométrage</button>') + '</div>';
    var b = $('#nh-odo'); if (b) tappable(b, odoSheet);
  }
  if (NOCAN) {
    if (GT.day !== todayIso()) { GT.km = 0; GT.sec = 0; GT.vmax = 0; GT.day = todayIso(); }
    fromService(Bridge.tripState());
    gpsApply();
    Bridge.on('trip', function (d) {
      fromService(d);
      if (Date.now() - gSaved > 30000) gpsSave();
      gpsApply(); gpsRefresh();
    });
    Bridge.on('speed', function (d) {
      var now = Date.now(), k = d && d.kmh ? d.kmh : 0;
      if (javaTrip) { car.speed = k; renderNocarHome(); return; }   // le service compte déjà : on affiche juste la vitesse
      if (GT.day !== todayIso()) { GT.km = 0; GT.sec = 0; GT.vmax = 0; GT.day = todayIso(); }
      if (gLast && k >= 3) {
        var dt = Math.min(5, (now - gLast) / 1000), dk = k * dt / 3600;
        GT.km += dk; GT.odoAdd += dk; GT.sec += dt; if (k > GT.vmax) GT.vmax = k;
      }
      gLast = now; car.speed = k;
      if (now - gSaved > 30000) gpsSave();
      gpsApply(); gpsRefresh();
    });
    Bridge.on('pause', gpsSave);
  }
  /* lecture automatique de CarCan : ~8 s après le démarrage, puis toutes les 15 min si on est sur l'accueil
     (jamais par-dessus CarPlay ou une autre appli). v1.10 : CarCan s'ouvre sous un rideau (image figée du lanceur), on ne le voit plus. */
  var visible = true, lastAutoRead = 0, bootRead = false;
  Bridge.on('pause', function () { visible = false; });
  Bridge.on('resume', function () { visible = true; applyBarMode(); });
  function autoRead(force) {
    var mode = store.get('autoread', 'boot');
    if (NOCAN || mode === 'off' || Bridge.mock || !Bridge.canRefreshPossible()) return;
    if (!visible || current !== 'home' || $('#sheet-scrim').classList.contains('on')) return;
    if (!force && (mode !== '15' || Date.now() - lastAutoRead < 15 * 60000)) return;
    lastAutoRead = Date.now(); Bridge.canRefresh();
  }
  setTimeout(function () { if (bootRead) autoRead(true); }, 8000);   // sinon c'est l'écran « Prêt à partir » qui lance la lecture
  setInterval(function () { autoRead(false); }, 60000);
  Bridge.on('turn', function (t) { var el = $('#turn'); el.classList.toggle('l', !!(t && t.left)); el.classList.toggle('r', !!(t && t.right)); });
  Bridge.on('apps', function () { loadApps(); renderDock(); renderQuick(); renderApps(); alignGlass(); });
  Bridge.on('status', function (s) { $('#st-bt').classList.toggle('on', !!s.bt); $('#st-wifi').classList.toggle('on', !!s.wifi); });
  Bridge.on('resume', function (d) {
    if (d && d.silent) return;
    if (stayPage) { stayPage = false; return; }          // v3.0 : le lanceur repasse devant pour afficher une autre page
    if (current !== 'home') showPage('home');
    introSpin();
    renderDash(); setTimeout(function () { tileDash(); }, 700);   // v3.0 : les fenêtres reprennent leur place
  });   // retour de la lecture CarCan masquée : on reste où l'on était
  Bridge.on('home', function () { if (current !== 'home') showPage('home'); else introSpin(); });
  window.addEventListener('resize', function () { fit(); renderApps(); alignGlass(); });
  if (Bridge.mock) window.showPageForTest = function (n) { showPage(n); };   // aperçu dans un navigateur uniquement
  boot();
})();
