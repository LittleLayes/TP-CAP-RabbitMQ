# 📦 Projet RabbitMQ — Réplication & Tolérance aux Pannes

> Interface accessible : http://localhost:15672 (login: `guest` / mdp: `guest`)

---

## 📥 Installation des dépendances

Téléchargez les JARs suivants et placez-les dans le dossier `libs/` :

- [RabbitMQ Java Client](https://www.rabbitmq.com/java-client.html)
- [SLF4J API](https://www.slf4j.org/download.html)

---

## 🛠️ Compilation

```bash
javac -cp "libs/*" -d out src/*.java
```

---

## 🚀 Exécution (dans l'ordre)

### 1. Lancer les 3 réplicas (3 terminaux séparés)

```bash
java -cp "libs/*:out" Replica 1
java -cp "libs/*:out" Replica 2
java -cp "libs/*:out" Replica 3
```

### 2. Envoyer des données (ClientWriter)

```bash
java -cp "libs/*:out" ClientWriter
```

### 3. Lire la dernière ligne (ClientReader — Q4/5)

```bash
java -cp "libs/*:out" ClientReader
```

### 4. Lecture avec vote majoritaire (ClientReaderV2 — Q7)

```bash
java -cp "libs/*:out" ClientReaderV2
```

---

## 🧪 Simulation de panne (Question 6)

1. Écrivez 2 lignes avec **ClientWriter**.
2. Arrêtez **Replica 2** (`Ctrl+C`).
3. Écrivez 2 nouvelles lignes.
4. Relancez **Replica 2** → les fichiers se resynchronisent automatiquement grâce à `autoAck=false`.
### 🧪 Simulation automatisée (Question 6)
Un script Bash est fourni pour reproduire automatiquement le scénario de panne et vérifier la résilience :
```bash
chmod +x simulation_q6.sh
./simulation_q6.sh
---

## 📐 Théorème CAP illustré

| Version          | Stratégie                    | Propriété priorisée                        |
|------------------|------------------------------|--------------------------------------------|
| `ClientReader`   | Première réponse disponible  | **AP** (Availability + Partition Tolerance) |
| `ClientReaderV2` | Vote majoritaire (≥ 2/3)    | **CP** (Consistency + Partition Tolerance)  |
