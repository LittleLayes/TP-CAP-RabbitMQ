import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.file.*;
import java.sql.Connection;
import java.util.concurrent.TimeoutException;

public class Replica {
    // Doit correspondre EXACTEMENT à l'échange déclaré dans ClientWriter
    private static final String EXCHANGE_NAME = "replication_fanout";

    public static void main(String[] args) throws IOException, TimeoutException {
        // 1. Validation de l'argument
        if (args.length < 1 || !args[0].matches("\\d+")) {
            System.err.println("❌ Usage: java Replica <id> (ex: java Replica 1)");
            System.exit(1);
        }

        String replicaId = args[0];
        String queueName = "replica_" + replicaId + "_queue";
        String dirPath = "replica_" + replicaId;
        String filePath = dirPath + "/data.txt";

        // 2. Création du répertoire local s'il n'existe pas
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            System.out.println("📁 Répertoire créé : " + dirPath);
        }

        // 3. Configuration connexion RabbitMQ
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");

        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        // 4. Déclaration de la queue DURABLE (survit au redémarrage du broker)
        // durable=true, exclusive=false, autoDelete=false
        channel.queueDeclare(queueName, true, false, false, null);

        // 5. Liaison (bind) de la queue à l'échange FANOUT
        // Routing key vide car ignored par le type FANOUT
        channel.queueBind(queueName, EXCHANGE_NAME, "");

        System.out.println("🟢 Replica " + replicaId + " en écoute sur : " + queueName);
        System.out.println("   Fichier cible : " + filePath);

        // 1. Déclarer une file temporaire pour les requêtes de lecture
        String requestQueue = "replica_" + replicaId + "_requests";
        channel.queueDeclare(requestQueue, true, false, false, null);
        // 2. La lier à l'échange Fanout utilisé par ClientReader
        channel.queueBind(requestQueue, REQUEST_EXCHANGE, "");

        // 3. Callback pour traiter "Read Last"
        DeliverCallback requestCallback = (consumerTag, delivery) -> {
            String command = new String(delivery.getBody(), "UTF-8").trim();
            if (command.equals("Read Last")) {
                try {
                    Path file = Paths.get(filePath);
                    if (Files.exists(file)) {
                        // Lire la dernière ligne du fichier
                        String lastLine = Files.lines(file).reduce((first, second) -> second).orElse("Fichier vide");
                        // Publier la réponse dans la file commune du ClientReader
                        channel.basicPublish("", RESPONSE_QUEUE, null, lastLine.getBytes("UTF-8"));
                        System.out.println("📤 Replica " + replicaId + " a répondu : " + lastLine);
                    }
                } catch (IOException e) {
                    System.err.println("❌ Erreur lecture fichier Replica " + replicaId);
                }
            }
        };
        // Lancer le consommateur de requêtes
        channel.basicConsume(requestQueue, true, requestCallback, consumerTag -> {});
// -------------------------------
        // 6. Callback de traitement des messages
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();

            try {
                Path file = Paths.get(filePath);
                // Format TP : "1 Texte 1..." → on ajoute \n si absent
                String line = message.endsWith("\n") ? message : message + "\n";
                
                // Écriture atomique en mode APPEND
                Files.writeString(file, line, 
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.APPEND);

                // ACK explicite : le message est traité et persisté sur disque
                channel.basicAck(deliveryTag, false);
                System.out.println("[✓] Répliqué ligne vers Replica " + replicaId + " : " + message.trim());
                
            } catch (IOException e) {
                System.err.println("❌ Erreur d'écriture fichier Replica " + replicaId + " : " + e.getMessage());
                // NACK avec requeue=true pour que RabbitMQ redistribue le message plus tard
                channel.basicNack(deliveryTag, false, true);
            }
        };

        CancelCallback cancelCallback = consumerTag -> {
            System.out.println("[⚠️] Consommation interrompue pour " + queueName);
        };

        // 7. Lancement du consommateur (autoAck = false INDISPENSABLE pour la tolérance aux pannes)
        channel.basicConsume(queueName, false, deliverCallback, cancelCallback);

        // Garder le processus actif jusqu'à interruption manuelle
        System.out.println("⏳ Appuyez sur Entrée pour arrêter proprement le Replica...");
        System.in.read();

        // Nettoyage des ressources
        channel.close();
        connection.close();
        System.out.println("[🛑] Replica " + replicaId + " arrêté.");
    }
}