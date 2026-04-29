import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.TimeoutException;

public class ClientWriter {
    // Nom de l'échange qui va diffuser le message à TOUS les réplicas
    private static final String EXCHANGE_NAME = "replication_fanout";

    public static void main(String[] args) throws IOException, TimeoutException {
        // 1. Configuration de la connexion au broker
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");

        // try-with-resources garantit la fermeture propre des ressources
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // 2. Déclaration de l'échange FANOUT (broadcast)
            // type=FANOUT => chaque message envoyé à cet échange est copié vers TOUTES les queues liées
            channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.FANOUT, true);

            Scanner scanner = new Scanner(System.in);
            int lineNumber = 1;

            System.out.println("🖊️  ClientWriter prêt. Tapez une ligne par message (tapez 'exit' pour quitter) :");
            
            while (scanner.hasNextLine()) {
                String userInput = scanner.nextLine().trim();
                if (userInput.equalsIgnoreCase("exit")) break;
                if (userInput.isEmpty()) continue;

                // Format exigé par le TP : "<numéro> <texte>"
                String message = lineNumber + " " + userInput;
                byte[] body = message.getBytes("UTF-8");

                // 3. Publication du message
                // - Exchange : notre fanout
                // - RoutingKey : "" (ignoré pour FANOUT)
                // - Properties : PERSISTENT => survit au redémarrage de RabbitMQ (essentiel pour simuler les pannes du TP)
                channel.basicPublish(EXCHANGE_NAME, "", MessageProperties.PERSISTENT_TEXT_PLAIN, body);

                System.out.println(" [x] Publié ligne " + lineNumber + " : " + userInput);
                lineNumber++;
            }
        } catch (Exception e) {
            System.err.println("❌ Erreur de communication avec RabbitMQ : " + e.getMessage());
        }
    }
}