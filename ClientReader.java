import com.rabbitmq.client.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ClientReader {
    // Échange utilisé pour broadcast la demande de lecture à TOUS les replicas
    private static final String REQUEST_EXCHANGE = "read_request_fanout";
    // File commune où les replicas publieront leurs réponses
    private static final String RESPONSE_QUEUE = "client_reader_responses";
    // Commande attendue par les replicas
    private static final String READ_COMMAND = "Read Last";
    // Timeout pour éviter un blocage infini si aucun replica ne répond
    private static final int TIMEOUT_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // 1. Déclarer l'échange de requêtes (Fanout → diffusion à tous les replicas)
            channel.exchangeDeclare(REQUEST_EXCHANGE, BuiltinExchangeType.FANOUT, true);

            // 2. Déclarer la file de réponse (durable pour fiabilité)
            channel.queueDeclare(RESPONSE_QUEUE, true, false, false, null);

            System.out.println("📤 Envoi de la requête : '" + READ_COMMAND + "'");
            // Publication de la commande vers l'échange
            channel.basicPublish(REQUEST_EXCHANGE, "", null, READ_COMMAND.getBytes("UTF-8"));

            // 3. Mécanisme pour capturer UNIQUEMENT la première réponse (Question 5)
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> firstResponse = new AtomicReference<>();

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String response = new String(delivery.getBody(), "UTF-8").trim();
                // On ne traite que la toute première réponse qui arrive
                if (latch.getCount() > 0) {
                    firstResponse.set(response);
                    latch.countDown(); // Débloque le thread principal
                    System.out.println("📩 Réponse reçue depuis un Replica : " + response);
                }
            };

            // Consommation de la file de réponse (autoAck=true car lecture simple)
            channel.basicConsume(RESPONSE_QUEUE, true, deliverCallback, consumerTag -> {});

            // 4. Attente bloquante avec timeout
            System.out.println("⏳ En attente de la réponse (max " + TIMEOUT_SECONDS + "s)...");
            boolean received = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (received) {
                System.out.println("✅ Dernière ligne affichée : " + firstResponse.get());
            } else {
                System.out.println("❌ Timeout : Aucun replica actif n'a répondu.");
                System.out.println("💡 Vérifiez que les processus Replica sont lancés.");
            }
        }
    }
}