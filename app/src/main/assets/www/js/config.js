/* Réglages du véhicule (voir ADAPTER.md).
   can: false   -> pas de boîtier CAN : portes, radars et ordinateur de bord CAN sont masqués,
                   l'ordinateur de bord est calculé avec le GPS de la tablette (trajet, vitesse moyenne, kilométrage).
   doors: 3     -> pas de portes arrière.
   carPack: false -> pas encore de pack 3D : l'accueil affiche la carte « Trajet » à la place de la voiture. */
window.IBZ_CONFIG = {
  "car": { "name": "Aygo", "model": "Toyota Aygo 1.0 VVT-i", "fuel": "essence" },
  "version": "3.2",
  "updateRepo": "yasslamenace132/car_launcher",
  "can": false,
  "doors": 3,
  "carPack": false,
  "topView": false,
  "cockpit": false,
  "hiView": false,
  "comboAnim": false,
  "frames": 48,
  "homeView": 8,
  "doorSteps": 6,
  "doorViews": { "fl": 34, "fr": 22, "hood": 0, "trunk": 24 },
  "topBox": { "x0": 219, "x1": 460, "y0": 49, "y1": 632, "w": 680, "h": 750 }
};
