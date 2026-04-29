import com.rabbitmq.client.*;
import java.util.*;
import java.util.concurrent.*;

public class ClientReaderV2 {
    private static final String READ_EXCHANGE  = "read_all_fanout";
    private static final String RESPONSE_QUEUE = "reader_v2_responses";
    private static final int REPLICA_COUNT = 3;
    private static final int TIMEOUT_SEC   = 8;

    public static void main(String[] args) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection conn = factory.newConnection();
             Channel ch = conn.createChannel()) {

            ch.exchangeDeclare(READ_EXCHANGE, BuiltinExchangeType.FANOUT, true);
            ch.queueDeclare(RESPONSE_QUEUE, true, false, false, null);

            // Structure thread-safe pour compter les occurrences de chaque ligne
            ConcurrentHashMap<String, Integer> votes = new ConcurrentHashMap<>();
            CountDownLatch latch = new CountDownLatch(REPLICA_COUNT);

            // Callback de consommation des réponses
            DeliverCallback deliverCallback = (ct, delivery) -> {
                String msg = new String(delivery.getBody(), "UTF-8").trim();
                if (msg.startsWith("__EOF__|")) {
                    latch.countDown(); // Un réplica a fini d'envoyer
                } else if (!msg.isEmpty()) {
                    votes.merge(msg, 1, Integer::sum); // Vote +1 pour cette ligne
                }
            };

            String consumerTag = ch.basicConsume(RESPONSE_QUEUE, true, deliverCallback, ct -> {});

            // 1. Diffusion de la requête
            System.out.println("📤 Envoi de la requête 'Read All'...");
            ch.basicPublish(READ_EXCHANGE, "", null, "Read All".getBytes("UTF-8"));

            // 2. Attente des 3 réplicas (ou timeout)
            System.out.println("⏳ Collecte des votes (max " + TIMEOUT_SEC + "s)...");
            boolean allResponded = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS);

            if (!allResponded) {
                System.out.println("⚠️ Timeout : Certains réplicas ne répondent pas (partition réseau).");
            }

            // 3. Filtrage par majorité (>= 2/3)
            List<String> consensusLines = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : votes.entrySet()) {
                if (entry.getValue() >= 2) {
                    consensusLines.add(entry.getKey());
                }
            }

            // 4. Tri par numéro de ligne (format: "1 Texte...")
            consensusLines.sort((a, b) -> {
                try {
                    int numA = Integer.parseInt(a.split(" ")[0]);
                    int numB = Integer.parseInt(b.split(" ")[0]);
                    return Integer.compare(numA, numB);
                } catch (Exception e) {
                    return 0; // Fallback
                }
            });

            // 5. Affichage final
            System.out.println("\n=== ✅ Consensus (Majorité ≥ 2/3) ===");
            if (consensusLines.isEmpty()) {
                System.out.println("(Aucune ligne n'a atteint la majorité)");
            } else {
                consensusLines.forEach(System.out::println);
            }

            // Nettoyage
            ch.basicCancel(consumerTag);
            System.out.println("✅ ClientReaderV2 terminé.");
        }
    }
}