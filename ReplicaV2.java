import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class ReplicaV2 {
    private static final String WRITE_EXCHANGE = "replication_fanout";
    private static final String READ_EXCHANGE  = "read_all_fanout";
    private static final String RESPONSE_QUEUE = "reader_v2_responses";

    public static void main(String[] args) throws IOException, TimeoutException {
        if (args.length < 1 || !args[0].matches("\\d+")) {
            System.err.println("❌ Usage: java ReplicaV2 <id>");
            System.exit(1);
        }

        String id = args[0];
        String writeQueue = "replica_" + id + "_queue";
        String readQueue  = "replica_" + id + "_read_req";
        String dirPath    = "replica_" + id;
        String filePath   = dirPath + "/data.txt";

        // Création du répertoire
        if (!Files.exists(Paths.get(dirPath))) Files.createDirectories(Paths.get(dirPath));

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try (Connection conn = factory.newConnection();
             Channel ch = conn.createChannel()) {

            // 1. Déclarations RabbitMQ (idempotentes)
            ch.exchangeDeclare(WRITE_EXCHANGE, BuiltinExchangeType.FANOUT, true);
            ch.exchangeDeclare(READ_EXCHANGE, BuiltinExchangeType.FANOUT, true);
            ch.queueDeclare(RESPONSE_QUEUE, true, false, false, null);

            ch.queueDeclare(writeQueue, true, false, false, null);
            ch.queueBind(writeQueue, WRITE_EXCHANGE, "");

            ch.queueDeclare(readQueue, true, false, false, null);
            ch.queueBind(readQueue, READ_EXCHANGE, "");

            System.out.println("🟢 ReplicaV2 " + id + " en écoute...");

            // 2. Callback Écriture (inchangé)
            DeliverCallback writeCallback = (ct, delivery) -> {
                String line = new String(delivery.getBody(), "UTF-8") + "\n";
                Files.writeString(Paths.get(filePath), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            };
            ch.basicConsume(writeQueue, false, writeCallback, ct -> {});

            // 3. Callback Lecture 'Read All' (Nouveau)
            DeliverCallback readCallback = (ct, delivery) -> {
                String cmd = new String(delivery.getBody(), "UTF-8").trim();
                if (cmd.equals("Read All")) {
                    try {
                        Path file = Paths.get(filePath);
                        if (Files.exists(file)) {
                            List<String> lines = Files.readAllLines(file);
                            for (String line : lines) {
                                if (line.trim().isEmpty()) continue;
                                // Envoi ligne par ligne
                                ch.basicPublish("", RESPONSE_QUEUE, null, line.getBytes("UTF-8"));
                            }
                        }
                        // Marqueur de fin pour signaler au client que ce réplica a terminé
                        String eofMsg = "__EOF__|" + id;
                        ch.basicPublish("", RESPONSE_QUEUE, null, eofMsg.getBytes("UTF-8"));
                    } catch (IOException e) {
                        System.err.println("❌ Erreur lecture fichier Replica " + id);
                    }
                }
            };
            ch.basicConsume(readQueue, true, readCallback, ct -> {});

            // Maintien du processus actif
            System.out.println("⏳ Appuyez sur Entrée pour arrêter...");
            System.in.read();
        }
    }
}