# Car Launcher — lanceur Android pour autoradio

Lanceur Android (Android Studio, interface HTML/CSS/JS dans une WebView + Java) pensé pour les autoradios Android 2 DIN
chinois. Développé à l'origine sur un **Junsun V1 (MediaTek MT8163, firmware « hcn », écran 1024×600)** avec un boîtier CAN
**SimpleSoft profil VW**, dans une Seat Ibiza 6J 5 portes.

**Version 2.0 : réglée pour une Toyota Aygo 1.0 VVT-i 3 portes essence, sans boîtier CAN** (voir `js/config.js` et le
CHANGELOG) : identité visuelle « Nuit glacier », ordinateur de bord calculé avec le GPS de la tablette.

## Compiler sans rien installer (GitHub)

1. Crée un dépôt sur GitHub et envoie-y ce dossier (bouton *Add file > Upload files*, en gardant le dossier `.github`).
2. Onglet **Actions** : la compilation démarre toute seule (environ 5 min).
3. Ouvre la dernière exécution > **Artifacts** > `car-launcher-apk` : c'est l'APK à copier sur une clé USB.

## Fonctions

- **Accueil** : barre latérale, horloge, musique, apps, thèmes clair/sombre/auto, fond d'écran, effet verre.
- **Sans boîtier CAN** (`can: false`) : trajet du jour, temps de conduite, vitesses moyenne et max, kilométrage estimé
  par le GPS, compté même quand Waze est devant ; la carte d'accueil montre la vitesse en grand quand on roule.
- **Barre sur les applis** (v2.1) : la barre de gauche reste affichée quand Waze ou Spotify est ouvert (heure, dock,
  lecture/pause, suivant, accueil). Réglages > Barre sur les applis > Place des applis : à côté de la barre si la tablette
  gère le multi-fenêtres, bande réservée (commande `adb shell wm overscan`, Android 10 et avant) ou par-dessus.
- **Voiture 3D** sur plateau tournant (48 vues pré-rendues) : chaque ouvrant s'anime à l'ouverture (portes, hayon, capot),
  combinaisons animées, vue de face quand des portes sont ouvertes des deux côtés. *(nécessite un « pack voiture », voir plus bas)*
- **Ouvrants, clignotants, vitesse, radars en direct** (lus sur le CAN via les propriétés système du firmware hcn).
- **Ordinateur de bord** : carburant, batterie, température extérieure, kilométrage, régime, lave-glace.
- **Entretien** : suivi des opérations (km et/ou mois), entretiens libres, diesel/essence, **export PDF** du carnet,
  vue « poste de conduite » animée.
- **Écran « Prêt à partir »** au démarrage : autonomie, température, batterie, alertes (verglas, batterie faible,
  réserve, lave-glace, entretien dû, ouvrant ouvert).
- **Alerte plein écran** si un ouvrant s'ouvre en roulant (> 5 km/h), même par-dessus CarPlay.
- Fenêtre « activité en direct » des ouvrants par-dessus les autres apps.

## Compatibilité

| Élément | État |
|---|---|
| Interface, apps, musique, entretien, PDF | tout autoradio Android 6+ |
| Portes / clignotants / vitesse CAN | firmware Junsun « hcn » (propriétés `persist.vendor.cem.*`) |
| Ordinateur de bord | appli CAN **CarCan** (`com.hcn.autocan`) + boîtier SimpleSoft **profil VW** |
| Voiture 3D | pack d'images propre à chaque voiture (non fourni) |

Autre autoradio, autre boîtier CAN ou autre voiture : **suis `ADAPTER.md`** (prompt prêt à donner à une IA avec ce dossier).

## Installer

1. Ouvre le dossier dans **Android Studio** (SDK 34), laisse Gradle synchroniser, puis *Build > Build APK(s)*.
2. Copie l'APK sur une clé USB et installe-le sur l'autoradio (autoriser les sources inconnues).
3. Touche Accueil > choisis le lanceur > *Toujours*.
4. Dans le lanceur : appui long sur l'horloge > Réglages > Installation : autorise les notifications (musique),
   l'**accessibilité** (lecture CAN/CarCan) et l'affichage par-dessus les apps.

Optionnel — ordinateur de bord **en direct**, sans ouvrir CarCan (nécessite ADB ou root, une seule fois) :
```
adb shell pm grant fr.ibiza.launcher android.permission.READ_LOGS
```

## Sécurité : ce que le lanceur touche (et ne touche pas)

- **Rien n'est modifié dans le système** : pas de root, pas de mise à jour de firmware, aucune appli d'origine modifiée.
  CarPlay / Android Auto, la radio, la caméra de recul et l'appli CAN continuent de fonctionner comme avant.
- Le lanceur **lit** seulement : propriétés système, notifications média, écran de l'appli CAN (accessibilité).
- Par-dessus les autres applis, il n'affiche que la petite fenêtre des ouvrants (désactivable) et l'alerte
  « ouvrant ouvert en roulant » (désactivable). La lecture de l'appli CAN ne se lance **jamais par-dessus CarPlay**.

## Revenir en arrière / désinstaller

1. Réglages du lanceur > Installation > **Revenir au lanceur d'origine / désinstaller** > « 1. Écran d'accueil » :
   choisis l'écran d'accueil d'origine (souvent « Launcher3 »). Ou : Paramètres Android > Applications >
   Applications par défaut > Application d'accueil.
2. Puis « 2. Désinstaller » (le bouton Désinstaller d'Android reste grisé tant que le lanceur est l'écran d'accueil par défaut).

Si le lanceur ne s'affiche plus : ouvre les Paramètres Android depuis le volet des notifications (glisser du haut de l'écran,
icône engrenage) > Applications > Applications par défaut > Application d'accueil, et choisis celui d'origine.

## Pack voiture (images 3D)

Les images de la voiture **ne sont pas incluses** (licence du modèle 3D d'origine). Sans pack, l'appli fonctionne et affiche
un message à la place de la voiture. Pour créer le tien : `ADAPTER.md` + `tools/render/`. Dossiers à remplir :
`img/car` (vues tournantes, ouvrants), `img/top` et `img/toph` (vue du dessus), `img/cockpit` (page Entretien).
Réglages du véhicule dans `app/src/main/assets/www/js/config.js`.

## Documentation

- `ADAPTER.md` — adapter à ton véhicule / autoradio (avec le prompt à copier-coller)
- `docs/CAN.md` — ce qu'on sait du CAN sur Junsun « hcn » + CarCan (propriétés, trames, astuces)
- `tools/render/` — pipeline Blender des rendus 3D
- `CHANGELOG.txt` — historique des versions

## Avertissement

Projet personnel, fourni **sans aucune garantie**. Ne manipule pas l'écran en roulant. L'appli utilise le service
d'accessibilité uniquement pour lire l'écran de l'appli CAN de l'autoradio ; aucune donnée ne quitte l'appareil.
Marques et logos cités appartiennent à leurs propriétaires ; projet non affilié.
