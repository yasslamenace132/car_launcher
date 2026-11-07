# CAN sur autoradio Junsun « hcn » + appli CarCan — ce qu'on a appris

Relevé sur un Junsun V1 (MT8163, Android 9 affiché « 15 », firmware hcn) avec CarCan `com.hcn.autocan` v43 et un boîtier
SimpleSoft profil VW. D'autres firmwares fonctionnent autrement : utilise la méthode en fin de page.

## 1. Lu en direct, sans autorisation : propriétés système `persist.vendor.cem.*`

| Propriété | Valeur |
|---|---|
| `cardoor1..5` | 1 = fermé, 2 = ouvert — ordre : avant gauche, avant droite, arrière gauche, arrière droite, coffre |
| `light0` / `light1` | clignotant gauche / droit (2 = allumé) |
| `speed` | vitesse CAN (km/h) si `persist.sys.cem.speed.from.can` = 1 |

Lecture : `android.os.SystemProperties.get()` par réflexion, toutes les 0,5 s (`CanBridge.java`). Correspondance modifiable
dans `ibiza_map.json` (`"cemDoors": [0,1,2,3,4]`).

## 2. Ordinateur de bord : affiché seulement par CarCan

CarCan lit le boîtier sur la liaison série (`/dev/ttyMT2`) et garde la liaison pour lui (impossible de la partager).
Son service AIDL `ICustomDataServices` n'envoie aucune donnée. Il publie seulement dans `Settings.System` :
`car_speed_value`, `key_can_average_speed`, `key_can_gear`.

**Méthode A (sans autorisation)** : ouvrir CarCan directement sur la page des infos, lire l'écran par l'accessibilité,
revenir au lanceur, le tout sous une image figée du lanceur (fenêtre `TYPE_ACCESSIBILITY_OVERLAY`, `Curtain.java`).
- Ouverture directe : extra `ToFragment` = `com.hcn.autocan.SimpleSoft.VW.VWInfoFragment`
  (+ `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`, sinon une instance déjà lancée ne reçoit pas l'extra).
- Les cases du menu CarCan ne réagissent qu'au **toucher** (`OnTouchListener`) : clic d'accessibilité sans effet,
  il faut `dispatchGesture` (capacité `canPerformGestures`).
- Identifiants des vues : `odbtextName` / `odbtextValue`, `textValue` / `textUnit` / `textName` (`CarText.parseHcn`).

**Méthode B (en direct, permission READ_LOGS par ADB ou root)** : CarCan écrit chaque morceau reçu du boîtier dans le
journal : `I CanService: rev: 2E41…` → décodé par `LogCan.java`.

## 3. Trames du boîtier SimpleSoft, profil VW (d'après `VWDataDecorder` de CarCan)

Format : `2E | cmd | len | données (len octets) | chk`, chk = (cmd + len + données) XOR 0xFF.

| cmd | Contenu |
|---|---|
| `0x14` | éclairage tableau de bord |
| `0x20` | boutons au volant |
| `0x21` | climatisation |
| `0x22` / `0x23` | radars arrière / avant |
| `0x24` | portes |
| `0x25` | coupure son radars |
| `0x26` | angle du volant |
| `0x27` | ampli / égaliseur |
| `0x30` | version du boîtier |
| `0x41` | infos véhicule (ci-dessous) |

`0x41` (index dans le tableau [cmd, len, d…] reçu par le décodeur) :
- sous-type 1 (`[2]` = 1) : `[3]` bit 6 lave-glace bas, bit 7 ceinture, bits 0–3 portes
- sous-type 2 : régime `[3..4]`, vitesse `[5..6]` × 0,01 km/h, batterie `[7..8]` × 0,01 V,
  extérieur (signé) `[9..10]` × 0,1 °C, kilométrage `[11..13]`, carburant `[14]` L
- sous-type 3 : `[3]` bit 7 alerte carburant, bit 6 alerte batterie

Attention : le dépôt « simplesoft-canbus-box-reverse-engineer » décrit le boîtier **Toyota** (RP5-TY-101) : numéros et
formats différents du profil VW.

## 4. Méthode pour un autre autoradio / une autre appli CAN

1. Lanceur installé : Réglages > Diagnostic CAN > *Exporter le journal* (ouvre/ferme des portes) et *Copier les APK CAN*.
2. Le journal liste les propriétés système, fenêtres et textes vus : chercher les clés qui changent avec les portes.
3. Décompiler l'APK CAN (androguard / jadx) : chercher le port série, le format des trames (classe `PacketManager` ou
   équivalent), le décodeur du profil véhicule, les `Log.*` qui écrivent les trames, les extras d'ouverture d'activité.
