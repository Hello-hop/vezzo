# Vezzo

Application Android de collecte des disponibilités par SMS, puis génération
d'un export prêt à être analysé par une IA.

## Ce que fait l'application

L'écran d'accueil propose quatre boutons.

**SMS groupé** — gère la liste des contacts fréquents (ajout depuis le carnet
d'adresses, suppression), permet de modifier le texte du SMS, et l'envoie
réellement à toute la liste en un appui. Le message est personnalisé grâce aux
variables `{PRENOM}` et `{MOIS}`. La date de l'envoi est mémorisée : elle sert
de point de départ pour repérer les réponses.

**Relance** — lit les SMS reçus depuis le dernier envoi groupé, affiche
« À ce jour : X réponses sur N contacts », liste les personnes qui n'ont pas
encore répondu, et permet de leur envoyer un texte de relance modifiable.

**Export pour l'IA** — rassemble toutes les réponses du mois, les regroupe par
personne, et y ajoute la consigne complète : le planning jour par jour avec les
colonnes MATIN / APRÈS-MIDI / JOUR COMPLET, la phrase de synthèse indiquant si
le mois est couvert, et la version HTML autonome. Deux sorties possibles :
copie dans le presse-papier ou partage vers l'application de ton choix.

**Envoyer cette synthèse à l'administrateur** — en un appui, prépare le même
export et ouvre l'application mail avec le destinataire déjà renseigné, le
contenu dans le corps du message et en pièce jointe.

Une option « prénoms seulement » masque les noms de famille dans l'export.

## Compiler l'APK

Le fichier `.github/workflows/build.yml` compile automatiquement l'application
à chaque envoi sur la branche `main`.

1. Crée un dépôt sur GitHub et envoie ces fichiers dessus.
2. Ouvre l'onglet **Actions** du dépôt. La compilation démarre toute seule
   (compte environ trois minutes la première fois).
3. Une fois terminée, ouvre l'exécution et télécharge l'artefact `vezzo-apk`.
4. Décompresse-le : tu obtiens `app-debug.apk`.

Tu peux aussi relancer une compilation manuellement via **Actions →
Build APK → Run workflow**.

## Installer sur le téléphone

1. Copie `app-debug.apk` sur ton téléphone Android.
2. Ouvre-le depuis le gestionnaire de fichiers.
3. Android demandera l'autorisation d'installer depuis une source inconnue :
   accepte pour l'application concernée.
4. Au premier lancement, accorde les autorisations SMS demandées. Sans elles,
   l'envoi et la lecture des réponses ne fonctionneront pas.

L'application n'est pas destinée au Play Store : Google refuse presque
systématiquement les permissions de lecture de SMS. L'installation directe
contourne cette limite sans aucun coût.

## Points à connaître

- L'envoi groupé utilise le forfait SMS de la carte SIM. Une pause de 0,4
  seconde sépare chaque message pour éviter un blocage opérateur.
- Les réponses sont associées aux contacts en comparant les neuf derniers
  chiffres du numéro, ce qui gère indifféremment `06…` et `+336…`.
- Toutes les données restent sur le téléphone. Rien n'est envoyé nulle part
  tant que tu ne déclenches pas un export ou un envoi à l'administrateur.
- Le mois planifié est automatiquement le mois suivant le mois en cours.
