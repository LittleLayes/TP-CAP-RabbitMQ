#!/bin/bash
set -e

echo "🚀 Démarrage de la simulation CAP - Question 6 (Panne Replica 2)"

# ⚙️ Configuration du classpath (ajustez si vos jars sont ailleurs)
RABBITMQ_JAR="amqp-client-5.18.0.jar"
SLF4J_JAR="slf4j-api-2.0.9.jar"
CLASSPATH=".:${RABBITMQ_JAR}:${SLF4J_JAR}"

# 🧹 Nettoyage des anciennes exécutions
rm -rf replica_1 replica_2 replica_3
mkdir -p replica_1 replica_2 replica_3
echo "🧹 Répertoires nettoyés."

# 🔨 Compilation
echo "🔨 Compilation des classes Java..."
javac -cp "$CLASSPATH" ClientWriter.java Replica.java
echo "✅ Compilation terminée."

# 🟢 Lancement des 3 replicas en arrière-plan
echo "🟢 Lancement des Replica 1, 2 et 3..."
java -cp "$CLASSPATH" Replica 1 &
PID_R1=$!
java -cp "$CLASSPATH" Replica 2 &
PID_R2=$!
java -cp "$CLASSPATH" Replica 3 &
PID_R3=$!

# ⏳ Attente que les connexions RabbitMQ soient établies et les queues déclarées
echo "⏳ Initialisation RabbitMQ (3s)..."
sleep 3

# 🖊️ ÉTAPE 1 : Écriture des 2 premières lignes
echo "📝 ClientWriter envoie : 'Texte message1' et 'Texte message2'"
printf "Texte message1\nTexte message2\n" | java -cp "$CLASSPATH" ClientWriter
sleep 2

echo "📄 Contenu après étape 1 (tous identiques) :"
echo "R1: $(cat replica_1/data.txt 2>/dev/null || echo '(vide)')"
echo "R2: $(cat replica_2/data.txt 2>/dev/null || echo '(vide)')"
echo "R3: $(cat replica_3/data.txt 2>/dev/null || echo '(vide)')"
echo "--------------------------------------------------"

# 💥 ÉTAPE 2 : Simulation de panne brutale de Replica 2
echo "💥 Arrêt brutal de Replica 2 (kill -SIGTERM)..."
kill $PID_R2 2>/dev/null || true
wait $PID_R2 2>/dev/null || true
sleep 1

# 🖊️ ÉTAPE 3 : Écriture pendant la panne
echo "📝 ClientWriter envoie : 'Texte message3' et 'Texte message4' (Replica 2 DOWN)"
printf "Texte message3\nTexte message4\n" | java -cp "$CLASSPATH" ClientWriter
sleep 2

echo "📄 Contenu après étape 3 (Replica 2 incomplet) :"
echo "R1: $(cat replica_1/data.txt)"
echo "R2: $(cat replica_2/data.txt 2>/dev/null || echo '(fichier figé)')"
echo "R3: $(cat replica_3/data.txt)"
echo "⚠️  Notez que Replica 2 ne contient PAS les lignes 3 et 4."
echo "--------------------------------------------------"

# 🔄 ÉTAPE 4 : Relance de Replica 2 (rattrapage des messages non ACK)
echo "🔄 Relance de Replica 2..."
java -cp "$CLASSPATH" Replica 2 &
PID_R2_NEW=$!
sleep 3 # Temps nécessaire pour consommer les messages en attente

echo "📄 Contenu final après rattrapage :"
echo "R1: $(cat replica_1/data.txt)"
echo "R2: $(cat replica_2/data.txt)"
echo "R3: $(cat replica_3/data.txt)"

# 🔍 Vérification de cohérence
echo "--------------------------------------------------"
if diff -q replica_1/data.txt replica_2/data.txt > /dev/null 2>&1 && \
   diff -q replica_2/data.txt replica_3/data.txt > /dev/null 2>&1; then
    echo "✅ COHÉRENCE RESTAURÉE : Les 3 fichiers sont identiques."
else
    echo "❌ DIVERGENCE : Les fichiers ne sont pas synchronisés."
    echo "💡 Vérifiez que channel.basicAck() est bien appelé après Files.writeString()"
fi

# 🛑 Nettoyage propre
echo "🛑 Arrêt des processus restants..."
kill $PID_R1 $PID_R2_NEW $PID_R3 2>/dev/null || true
wait 2>/dev/null || true
echo "✅ Simulation terminée."