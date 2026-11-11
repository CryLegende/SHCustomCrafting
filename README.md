<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHCustomCrafting

*Recettes SkyHand et crafts vanilla désactivés (remplace CustomCrafting).*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Recettes SkyHand et crafts vanilla désactivés (remplace CustomCrafting).

Utilisé sur : **Opale**.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shcustomcrafting reload` | `/shcc reload` | Relit `config.yml` et `messages.yml` puis réapplique les recettes ; les recettes vanilla sorties de `recettes-desactivees` sont rétablies. Un fichier illisible ne change rien. | `shcustomcrafting.admin.reload` |
| `/shcustomcrafting list` | `/shcc list` | Liste les recettes personnalisées, les crafts bloqués et les recettes vanilla retirées. | `shcustomcrafting.admin.list` |

Sans argument, ou avec une sous-commande inconnue, une ligne d'usage ne liste que les sous-commandes permises à l'expéditeur. Les deux sous-commandes marchent depuis la console.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shcustomcrafting.admin` | Toute l'administration (parent des deux nœuds suivants). | `op` |
| `shcustomcrafting.admin.reload` | Recharger les recettes. | `op` |
| `shcustomcrafting.admin.list` | Lister les recettes. | `op` |

## Messages

Les textes sont dans `messages.yml`. Au premier démarrage avec cette version, il est créé à partir de `prefix` et de la section `messages` du `config.yml` existant, qui ne sont plus lus ensuite.

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHCustomCrafting/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
