#!/bin/bash
set -e

echo "🚀 Démarrage de la simulation CAP - Question 7 (Vote Majoritaire / Cohérence)"

# ⚙️ Configuration du classpath
CLASSPATH=".:libs/amqp-client-5.18.0.jar:libs/slf4j-api-2.0.9.jar"

# 🧹 Nettoyage des anciennes exécutions
rm -rf replica_1 replica_2 replica_3
mkdir -p replica_1 replica_2 replica_3
echo "🧹 Répertoires nettoyés."

# 🔨 Compilation
echo "🔨 Compilation des classes Java V2..."
javac -cp "$CLASSPATH" ClientWriter.java ReplicaV2.java ClientReaderV2.java
echo "✅ Compilation terminée."

# 🟢 Lancement des 3 replicas V2 en arrière-plan
echo "🟢 Lancement des ReplicaV2 1, 2 et 3..."
java -cp "$CLASSPATH" ReplicaV2 1 & PID_R1=$!
java -cp "$CLASSPATH" ReplicaV2 2 & PID_R2=$!
java -cp "$CLASSPATH" ReplicaV2 3 & PID_R3=$!

# ⏳ Attente initialisation RabbitMQ
sleep 3

# 🖊️ ÉTAPE 1 : Écriture initiale (tous les réplicas sont synchrones)
echo "📝 ClientWriter envoie 3 lignes initiales..."
printf "Ligne 1\nLigne 2\nLigne 3\n" | java -cp "$CLASSPATH" ClientWriter
sleep 2

# 💥 ÉTAPE 2 : Simulation de panne de Replica 2
echo "💥 Arrêt brutal de ReplicaV2 2..."
kill $PID_R2 2>/dev/null || true
wait $PID_R2 2>/dev/null || true
sleep 1

# 🖊️ ÉTAPE 3 : Écriture pendant la partition
echo "📝 ClientWriter envoie 2 lignes pendant la panne (Replica 2 DOWN)..."
printf "Ligne 4\nLigne 5\n" | java -cp "$CLASSPATH" ClientWriter
sleep 2

# 🔄 ÉTAPE 4 : Relance de Replica 2 (il va rattraper les messages en attente)
echo "🔄 Relance de ReplicaV2 2..."
java -cp "$CLASSPATH" ReplicaV2 2 & PID_R2_NEW=$!
sleep 3

# 📄 Vérification visuelle des fichiers (divergence temporaire)
echo "📄 Contenu des fichiers avant lecture consensus :"
echo "R1: $(cat replica_1/data.txt 2>/dev/null || echo '(vide)')"
echo "R2: $(cat replica_2/data.txt 2>/dev/null || echo '(vide)')"
echo "R3: $(cat replica_3/data.txt 2>/dev/null || echo '(vide)')"
echo "--------------------------------------------------"

# 📖 ÉTAPE 5 : Exécution du ClientReaderV2 (Broadcast 'Read All' + Vote majoritaire)
echo "📖 Lancement de ClientReaderV2 (requête 'Read All' + filtre ≥2/3)..."
java -cp "$CLASSPATH" ClientReaderV2
echo "--------------------------------------------------"

# 🛑 Nettoyage propre
echo "🛑 Arrêt des processus restants..."
kill $PID_R1 $PID_R2_NEW $PID_R3 2>/dev/null || true
wait 2>/dev/null || true
echo "✅ Simulation Q7 terminée."