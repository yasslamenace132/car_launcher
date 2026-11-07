/* Pont vers l'app Android (window.Native). Hors Android : données de démo. */
(function () {
  var N = window.Native || null;
  var listeners = {};
  function on(type, fn) { (listeners[type] = listeners[type] || []).push(fn); }
  function emit(type, data) { (listeners[type] || []).forEach(function (f) { try { f(data); } catch (e) { console.error(e); } }); }
  /* appelé par Android : window.onNative('media', '{...}') */
  window.onNative = function (type, json) {
    var d = json; try { if (typeof json === 'string') d = JSON.parse(json); } catch (e) {}
    emit(type, d);
  };
  function parse(s, dflt) { try { return JSON.parse(s); } catch (e) { return dflt; } }

  var mock = !N;
  var demoApps = [
    ['com.ex.navi', 'Navigation'], ['com.ex.music', 'Musique'], ['com.ex.bt', 'Bluetooth'], ['com.ex.zlink', 'CarPlay'],
    ['com.ex.radio', 'Radio'], ['com.ex.video', 'Vidéo'], ['com.android.settings', 'Paramètres'], ['com.ex.eq', 'Égaliseur'],
    ['com.ex.canbus', 'Voiture'], ['com.ex.files', 'Fichiers'], ['com.android.chrome', 'Chrome'], ['com.waze', 'Waze'],
    ['com.spotify.music', 'Spotify'], ['com.ex.aux', 'AUX'], ['com.ex.usb', 'USB'], ['com.ex.gallery', 'Galerie'],
    ['com.ex.calc', 'Calculatrice'], ['com.ex.clock', 'Horloge'], ['com.ex.weather', 'Météo'], ['com.ex.store', 'Play Store']
  ].map(function (a) { return { pkg: a[0], cls: a[0] + '.Main', label: a[1], icon: '' }; });

  var demo = {
    media: { playing: true, title: 'Titre en cours', artist: 'Artiste', source: 'Bluetooth', pos: 72, dur: 243 },
    vehicle: { doors: { fl: false, fr: false, rl: false, rr: false, trunk: false, hood: false }, radar: null, reverse: false },
    perms: { notif: false, access: false, overlay: false, home: false }
  };

  var demoCar = { oil: 92, coolant: 89, outside: 11, battery: 14.2, insp_km: 12400, insp_days: 210, oil_km: 8300, oil_days: 140, range: 520, cons: 5.8, avg: 47, odo: 84213, src: 'démo' };
  var API = {
    mock: mock,
    on: on,
    apps: function () {
      if (mock) return demoApps;
      var list = parse(N.getApps(), []);
      var v = Date.now();
      list.forEach(function (a) { a.icon = 'https://appassets.androidplatform.net/icons/' + encodeURIComponent(a.pkg) + '/' + encodeURIComponent(a.cls) + '.png?v=' + v; });
      return list;
    },
    launch: function (a) { if (mock) { API.toast('Ouverture : ' + a.label); return; } N.launch(a.pkg, a.cls); },
    state: function () { return mock ? demo : parse(N.getState(), demo); },
    media: function (cmd) {
      if (mock) {
        if (cmd === 'toggle') demo.media.playing = !demo.media.playing;
        if (cmd === 'next' || cmd === 'prev') demo.media.pos = 0;
        emit('media', demo.media); return;
      }
      N.media(cmd);
    },
    openSystem: function (which) { if (mock) { API.toast('Réglage système : ' + which); return; } N.openSystem(which); },
    setPref: function (k, v) { if (!mock && N.setPref) N.setPref(k, String(v)); },
    simulate: function (vehicle) { if (mock) { emit('vehicle', vehicle); return; } N.simulate(JSON.stringify(vehicle)); },
    diag: function () { return mock ? 'Mode démo : pas de journal.' : N.getDiag(); },
    diagExport: function () { return mock ? '' : N.exportDiag(); },
    diagClear: function () { if (!mock) N.clearDiag(); },
    toast: function (t) { emit('toast', t); },
    iconDir: function () { return mock ? '/sdcard/Android/data/fr.ibiza.launcher/files/icons' : N.iconDir(); },
    reloadIcons: function () { if (mock) emit('apps', {}); else N.reloadIcons(); },
    launchPkg: function (pkg) { if (mock) { API.toast('Ouverture : ' + pkg); return; } if (N.launchPkg) N.launchPkg(pkg); },
    canScan: function () { return mock ? 'Mode démo : pas d\'analyse.' : (N.canScan ? N.canScan() : ''); },
    canExport: function () { return mock ? '' : (N.canExport ? N.canExport() : ''); },
    canLogs: function () { return mock ? false : (N.canLogs ? N.canLogs() : false); },
    canLive: function () { return mock ? false : (N.canLive ? N.canLive() : false); },
    tryRoot: function () { if (mock) { API.toast('Essai root (démo)'); return; } if (N.tryRoot) N.tryRoot(); },
    canRefresh: function () { if (mock) { API.toast('Lecture CarCan (démo)'); return; } if (N.canRefresh) N.canRefresh(); },
    canRefreshPossible: function () { return mock ? true : (N.canRefreshPossible ? N.canRefreshPossible() : false); },
    car: function () { return mock ? demoCar : parse(N.getCar ? N.getCar() : '{}', {}); },
    /* v1.11 : carnet d'entretien en PDF (dessiné par Android, résultat renvoyé par l'événement « pdf ») */
    exportPdf: function (json) {
      if (mock) { window.__lastPdf = json; setTimeout(function () { emit('pdf', { ok: true, where: 'Téléchargements › Ibiza › ' + JSON.parse(json).file }); }, 300); return true; }
      if (!N.exportPdf) return false; N.exportPdf(json); return true;
    },
    testMoveAlert: function () { if (mock) { API.toast('Alerte en roulant (démo) : visible sur l\'autoradio'); return; } if (N.testMoveAlert) N.testMoveAlert(); },
    openPdf: function () { if (mock) { API.toast('Ouverture du PDF (démo)'); return; } if (N.openPdf) N.openPdf(); },
    /* v2.1 : barre latérale par-dessus les applis + compteur de trajet en arrière-plan */
    setBar: function (list) { if (!mock && N.setBar) N.setBar(JSON.stringify(list || [])); },
    barInfo: function () {
      if (mock) return { api: 0, release: 'navigateur', freeform: false, barPx: 88, screenW: 1024, screenH: 600, mode: 'auto', effective: 'over', overlay: false, enabled: true, demo: true };
      return parse(N.barInfo ? N.barInfo() : '{}', {});
    },
    hasTripService: function () { return !mock && !!N.tripState; },
    tripState: function () { return mock || !N.tripState ? null : parse(N.tripState(), null); },
    tripReset: function () { if (!mock && N.tripReset) N.tripReset(); },
    /* v2.2 : mise à jour depuis GitHub */
    appVersion: function () { return mock || !N.appVersion ? 0 : N.appVersion(); },
    checkUpdate: function (repo) {
      if (mock) { setTimeout(function () { emit('update', { state: 'available', local: 24, remote: 25, name: '2.3 (démo)', progress: 0 }); }, 400); return; }
      if (N.checkUpdate) N.checkUpdate(repo || '');
    },
    installUpdate: function (repo) {
      if (mock) { API.toast('Installation de la mise à jour (démo)'); return; }
      if (N.installUpdate) N.installUpdate(repo || '');
    },
    sharePdf: function () { if (mock) { API.toast('Partage du PDF (démo)'); return; } if (N.sharePdf) N.sharePdf(); }
  };
  if (mock) {
    setTimeout(function () { emit('car', demoCar); }, 300);
    setInterval(function () {
      if (demo.media.playing) { demo.media.pos = (demo.media.pos + 1) % demo.media.dur; emit('media', demo.media); }
    }, 1000);
  }
  window.Bridge = API;
})();
