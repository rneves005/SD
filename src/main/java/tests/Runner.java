package tests;

import client.Client;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import model.Event;
import server.ClientHandler;
import server.ServerState;


public class Runner {

    private static final int PORT = 13337;
    private static final String HOST = "localhost";
    private static final Path TEST_DATA_DIR = Paths.get("test_data");

    private static final int MAX_DAYS = 10;
    private static final int MAX_IN_MEMORY = 2;

    private static ServerSocket serverSocket;

    public static void main(String[] args) {
        System.out.println("=== INICIANDO TESTES COM LOCKS/CONDITIONS ===\n");

        try {
            // 1. Limpar dados e arrancar servidor
            cleanDataDir();
            startTestServer();
            waitForServerReady();

            // 2. Executar Cenários
            test01_BasicFlow();
            test02_AggregationAndNextDay();
            test03_NotificationBlocking();
            test04_PersistenceAndLRU();
            test05_ConcurrencyStress();
            test06_WrongAuthentication();
            test07_ConsecutiveSales();
            test08_NonExistentData();
            test09_SimultaneousTimeout();
            test10_ConsecutiveTimeout();
            test11_CachePerformance();
            test12_MultipleClients();
            test13_EventFiltering();

            System.out.println("\n" + "=".repeat(60));
            System.out.println("✅ TODOS OS TESTES PASSARAM COM SUCESSO! (13/13)");
            System.out.println("=".repeat(60));

        } catch (AssertionError e) {
            System.err.println("\n❌ FALHA NO TESTE: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("\n❌ ERRO INESPERADO: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            shutdownServer();
        }

        System.exit(0);
    }

    // --- CENÁRIOS DE TESTE ---

    private static void test01_BasicFlow() throws Exception {
        System.out.println("[T01] Testando Fluxo Básico (register, auth, addEvent)...");
        try (Client c = new Client(HOST, PORT)) {
            c.register("user1", "pass1");
            c.authenticate("user1", "pass1");
            c.addEvent("prodA", 10, 5.0);
            System.out.println("   ✅ Registo, Autenticação e AddEvent OK.\n");
        }
    }

    private static void test02_AggregationAndNextDay() throws Exception {
        System.out.println("[T02] Testando Agregação e NextDay...");
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");
            c.addEvent("apple", 10, 2.0);
            c.addEvent("apple", 5, 2.0);
            c.nextDay();

            long qtd = c.getQuantity("apple", 1);
            double vol = c.getVolume("apple", 1);
            double avg = c.getAveragePrice("apple", 1);
            double max = c.getMaxPrice("apple", 1);

            assertCondition(qtd == 15, "Quantidade deve ser 15, obtido: " + qtd);
            assertCondition(Math.abs(vol - 30.0) < 0.001, "Volume deve ser 30.0, obtido: " + vol);
            assertCondition(Math.abs(avg - 2.0) < 0.001, "Preço médio deve ser 2.0, obtido: " + avg);
            assertCondition(Math.abs(max - 2.0) < 0.001, "Preço máximo deve ser 2.0, obtido: " + max);

            System.out.println("   ✅ Agregações (qty, vol, avg, max) OK.\n");
        }
    }

    // T03: Notificação quando dois produtos são vendidos
    private static void test03_NotificationBlocking() throws Exception {
        System.out.println("[T03] Testando Notificação Simultânea (sucesso)...");

        final ReentrantLock lock = new ReentrantLock();
        final Condition cond = lock.newCondition();
        final boolean[] testFinished = {false};
        final boolean[] success = {false};

        Thread waiter = new Thread(() -> {
            try (Client c = new Client(HOST, PORT)) {
                c.authenticate("user1", "pass1");
                // Bloqueia até X e Y serem vendidos
                boolean result = c.waitForSimultaneous("X", "Y");

                lock.lock();
                try {
                    success[0] = result;
                    testFinished[0] = true;
                    cond.signal();
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        waiter.start();
        waitWithTimeout(500); // Espera thread bloquear

        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");
            c.addEvent("X", 1, 10.0);
            System.out.println("   → Vendeu X (waiter ainda bloqueado)");
            waitWithTimeout(200);
            c.addEvent("Y", 1, 10.0);
            System.out.println("   → Vendeu Y (waiter deve desbloquear)");
        }

        // Espera resultado com timeout
        lock.lock();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (!testFinished[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("Timeout: waiter não desbloqueou!");
                }
                cond.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }

        assertCondition(success[0], "Cliente não recebeu notificação de vendas simultâneas.");
        System.out.println("   ✅ Notificação simultânea OK.\n");
    }

    private static void test04_PersistenceAndLRU() throws Exception {
        System.out.println("[T04] Testando Persistência e Evicção LRU...");
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");

            // Adicionar eventos em 5 dias (MAX_IN_MEMORY=2)
            for (int i = 0; i < 5; i++) {
                c.addEvent("diskItem", 100, 1.0);
                c.nextDay();
            }

            // Consultar últimos 5 dias (força leitura de disco)
            long qtd = c.getQuantity("diskItem", 5);
            assertCondition(qtd == 500, "Deve ler 500 do disco, obtido: " + qtd);

            // Verificar ficheiros CSV criados
            for (int i = 0; i < 5; i++) {
                Path csvFile = TEST_DATA_DIR.resolve("day_" + i + ".csv");
                assertCondition(Files.exists(csvFile), "Ficheiro day_" + i + ".csv deve existir");
            }

            System.out.println("   ✅ Persistência em CSV e LRU OK.\n");
        }
    }

    private static void test05_ConcurrencyStress() throws Exception {
        System.out.println("[T05] Testando Concorrência (10 threads × 50 eventos)...");
        int numThreads = 10;
        Thread[] threads = new Thread[numThreads];

        for (int i = 0; i < numThreads; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                try (Client c = new Client(HOST, PORT)) {
                    String u = "stressUser" + id;
                    c.register(u, "pass");
                    c.authenticate(u, "pass");
                    for (int j = 0; j < 50; j++) {
                        c.addEvent("stressItem", 1, 1.0);
                    }
                } catch (Exception e) {
                    System.err.println("Erro thread " + id + ": " + e.getMessage());
                }
            });
            threads[i].start();
        }

        for (Thread t : threads) t.join();

        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");
            c.nextDay();
            long total = c.getQuantity("stressItem", 1);
            assertCondition(total == 500, "Race condition! Esperado 500, obtido " + total);
            System.out.println("   ✅ Sem race conditions (500/500 eventos preservados).\n");
        }
    }

    private static void test06_WrongAuthentication() throws Exception {
        System.out.println("[T06] Testando Casos de Erro (auth, registo, sem login)...");
        try (Client c = new Client(HOST, PORT)) {
            c.register("user2", "pass2");

            // 1. Autenticação com password errada
            boolean authFailed = false;
            try {
                c.authenticate("user2", "wrongpass");
            } catch (IOException e) {
                authFailed = true;
                assertCondition(e.getMessage().contains("auth failed"),
                    "Mensagem deve conter 'auth failed'");
            }
            assertCondition(authFailed, "Autenticação com password errada deve falhar");

            // 2. Registo duplicado
            boolean registerFailed = false;
            try {
                c.register("user2", "pass2");
            } catch (IOException e) {
                registerFailed = true;
                assertCondition(e.getMessage().contains("user exists"),
                    "Mensagem deve conter 'user exists'");
            }
            assertCondition(registerFailed, "Registo duplicado deve falhar");

            // 3. Operação sem autenticação
            boolean notAuthFailed = false;
            try {
                c.addEvent("item", 1, 1.0);
            } catch (IOException e) {
                notAuthFailed = true;
                assertCondition(e.getMessage().contains("not authenticated"),
                    "Mensagem deve conter 'not authenticated'");
            }
            assertCondition(notAuthFailed, "Operação sem autenticação deve falhar");

            System.out.println("   ✅ Validações de erro OK.\n");
        }
    }

    private static void test07_ConsecutiveSales() throws Exception {
        System.out.println("[T07] Testando Vendas Consecutivas (n=3)...");
        Lock lock = new ReentrantLock();
        Condition cond = lock.newCondition();
        final String[] result = {null};
        final boolean[] finished = {false};

        Thread waiter = new Thread(() -> {
            try (Client c = new Client(HOST, PORT)) {
                c.authenticate("user1", "pass1");
                String product = c.waitForConsecutive(3);
                lock.lock();
                try {
                    result[0] = product;
                    finished[0] = true;
                    cond.signalAll();
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        waiter.start();
        waitWithTimeout(500);

        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");

            // Sequência quebrada
            c.addEvent("SuccessProd", 1, 1.0); // 1
            c.addEvent("SuccessProd", 1, 1.0); // 2
            c.addEvent("OtherProd", 1, 1.0);   // Reset!
            System.out.println("   → Sequência quebrada enviada (reset contador)");
            waitWithTimeout(100);

            // Sequência válida
            c.addEvent("SuccessProd", 1, 1.0); // 1
            c.addEvent("SuccessProd", 1, 1.0); // 2
            c.addEvent("SuccessProd", 1, 1.0); // 3 ✅
            System.out.println("   → Sequência válida enviada (3 consecutivas)");
        }

        lock.lock();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (!finished[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("Timeout esperando notificação consecutiva");
                }
                cond.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }

        assertCondition("SuccessProd".equals(result[0]),
            "Produto deve ser 'SuccessProd', obtido: " + result[0]);
        System.out.println("   ✅ Notificação consecutiva OK.\n");
    }

    private static void test08_NonExistentData() throws Exception {
        System.out.println("[T08] Testando Dados Inexistentes...");
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");

            long qtd = c.getQuantity("nonExistentProduct", 1);
            double vol = c.getVolume("nonExistentProduct", 1);
            double avg = c.getAveragePrice("nonExistentProduct", 1);
            double max = c.getMaxPrice("nonExistentProduct", 1);

            assertCondition(qtd == 0, "Quantidade deve ser 0, obtido: " + qtd);
            assertCondition(vol == 0, "Volume deve ser 0.0, obtido: " + vol);
            assertCondition(avg == 0, "Preço médio deve ser 0.0, obtido: " + avg);
            assertCondition(max == 0, "Preço máximo deve ser 0.0, obtido: " + max);

            System.out.println("   ✅ Produto inexistente retorna zeros.\n");
        }
    }

    // T09: Timeout quando dia acaba antes de vendas simultâneas
    private static void test09_SimultaneousTimeout() throws Exception {
        System.out.println("[T09] Testando Timeout Simultâneo (nextDay interrompe)...");

        Lock lock = new ReentrantLock();
        Condition cond = lock.newCondition();
        final boolean[] result = {true}; // Assume sucesso (deve virar false)
        final boolean[] finished = {false};

        Thread waiter = new Thread(() -> {
            try (Client c = new Client(HOST, PORT)) {
                c.authenticate("user1", "pass1");
                boolean happened = c.waitForSimultaneous("TimeoutA", "TimeoutB");
                lock.lock();
                try {
                    result[0] = happened;
                    finished[0] = true;
                    cond.signal();
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        waiter.start();
        waitWithTimeout(500);

        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");
            c.addEvent("TimeoutA", 1, 1.0);  // Só 1 produto vendido
            System.out.println("   → Vendeu apenas TimeoutA");
            waitWithTimeout(200);
            c.nextDay();  // Dia acaba sem TimeoutB!
            System.out.println("   → Dia avançou (sem vender TimeoutB)");
        }

        lock.lock();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (!finished[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("Timeout: waiter não desbloqueou após nextDay");
                }
                cond.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }

        assertCondition(!result[0], "Deve retornar FALSE (timeout por nextDay)");
        System.out.println("   ✅ Timeout detectado corretamente.\n");
    }

    // T10: Timeout de vendas consecutivas
    private static void test10_ConsecutiveTimeout() throws Exception {
        System.out.println("[T10] Testando Timeout Consecutivo (nextDay interrompe)...");

        Lock lock = new ReentrantLock();
        Condition cond = lock.newCondition();
        final String[] result = {"WRONG"}; // Deve virar null
        final boolean[] finished = {false};

        Thread waiter = new Thread(() -> {
            try (Client c = new Client(HOST, PORT)) {
                c.authenticate("user1", "pass1");
                String product = c.waitForConsecutive(5); // Espera 5 consecutivas
                lock.lock();
                try {
                    result[0] = product;
                    finished[0] = true;
                    cond.signal();
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        waiter.start();
        waitWithTimeout(500);

        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");

            // Apenas 3 consecutivas (não chega a 5)
            c.addEvent("TimeoutProd", 1, 1.0); // 1
            c.addEvent("TimeoutProd", 1, 1.0); // 2
            c.addEvent("TimeoutProd", 1, 1.0); // 3
            System.out.println("   → Vendeu 3 consecutivas (faltam 2)");
            waitWithTimeout(200);

            c.nextDay();  // Dia acaba!
            System.out.println("   → Dia avançou (sem chegar a 5)");
        }

        lock.lock();
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (!finished[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("Timeout: waiter não desbloqueou");
                }
                cond.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }

        assertCondition(result[0] == null, "Deve retornar NULL (não atingiu n=5)");
        System.out.println("   ✅ Timeout consecutivo OK.\n");
    }

    // T11: Performance do cache
    private static void test11_CachePerformance() throws Exception {
        System.out.println("[T11] Testando Correção do Cache de Agregações...");
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");
    
            // Adicionar eventos
            for (int i = 0; i < 100; i++) {
                c.addEvent("cacheTest", 10, 1.5);
            }
            c.nextDay();
    
            // Primeira consulta
            long qty1 = c.getQuantity("cacheTest", 1);
            double vol1 = c.getVolume("cacheTest", 1);
            
            // Segunda consulta (deve usar cache)
            long qty2 = c.getQuantity("cacheTest", 1);
            double vol2 = c.getVolume("cacheTest", 1);
            
            // Terceira consulta
            long qty3 = c.getQuantity("cacheTest", 1);
            double vol3 = c.getVolume("cacheTest", 1);
    
            // Verifica consistência (cache retorna valores corretos)
            assertCondition(qty1 == qty2 && qty2 == qty3, 
                "Cache deve retornar valores consistentes");
            assertCondition(qty1 == 1000, "Quantidade deve ser 1000, obtido: " + qty1);
            assertCondition(Math.abs(vol1 - 1500.0) < 0.001, "Volume deve ser 1500.0, obtido: " + vol1);
            assertCondition(Math.abs(vol1 - vol2) < 0.001 && Math.abs(vol2 - vol3) < 0.001,
                "Volume deve ser consistente");
    
            System.out.println("   → Cache retornou valores consistentes em 3 queries");
            System.out.println("   ✅ Cache funcionando corretamente.\n");
        }
    }

    // T12: Múltiplos clientes em paralelo
    private static void test12_MultipleClients() throws Exception {
        System.out.println("[T12] Testando Múltiplos Clientes Simultâneos...");

        // Cliente 1 adiciona dados
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");
            for (int i = 0; i < 50; i++) {
                c.addEvent("multiTest", 2, 3.0);
            }
            c.nextDay();
        }

        // 5 clientes consultam em paralelo
        Thread[] clients = new Thread[5];
        final boolean[] results = new boolean[5];

        for (int i = 0; i < 5; i++) {
            final int id = i;
            clients[i] = new Thread(() -> {
                try (Client c = new Client(HOST, PORT)) {
                    c.authenticate("user1", "pass1");
                    long qty = c.getQuantity("multiTest", 1);
                    double vol = c.getVolume("multiTest", 1);
                    results[id] = (qty == 100 && Math.abs(vol - 300.0) < 0.001);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            clients[i].start();
        }

        for (Thread t : clients) t.join();

        for (int i = 0; i < 5; i++) {
            assertCondition(results[i], "Cliente " + i + " obteve dados incorretos");
        }

        System.out.println("   ✅ Múltiplos clientes obtiveram dados corretos.\n");
    }

    // T13: Filtragem de eventos com string table
    private static void test13_EventFiltering() throws Exception {
        System.out.println("[T13] Testando Filtragem de Eventos (serialização eficiente)...");
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("user1", "pass1");

            // Adicionar eventos variados
            c.addEvent("apple", 10, 2.0);
            c.addEvent("banana", 5, 1.5);
            c.addEvent("apple", 3, 2.1);
            c.addEvent("orange", 7, 3.0);
            c.addEvent("banana", 2, 1.6);
            c.addEvent("grape", 15, 4.0);
            c.addEvent("apple", 5, 2.2);

            c.nextDay();

            // Filtrar apenas apple e banana
            Set<String> filter = new HashSet<>();
            filter.add("apple");
            filter.add("banana");

            List<Event> events = c.getEventsForProducts(filter, 1);

            // Verificar que apenas apple e banana aparecem
            int appleCount = 0;
            int bananaCount = 0;
            int totalQty = 0;

            for (Event e : events) {
                String prod = e.getProduct();
                assertCondition(filter.contains(prod),
                    "Produto filtrado incorretamente: " + prod);

                if (prod.equals("apple")) appleCount++;
                if (prod.equals("banana")) bananaCount++;
                totalQty += e.getQuantity();
            }

            assertCondition(appleCount == 3, "Deve ter 3 eventos de apple, obtido: " + appleCount);
            assertCondition(bananaCount == 2, "Deve ter 2 eventos de banana, obtido: " + bananaCount);
            assertCondition(totalQty == 25, "Quantidade total deve ser 25, obtido: " + totalQty);

            System.out.println("   → Filtrados " + events.size() + " eventos de " + filter.size() + " produtos");
            System.out.println("   ✅ Filtragem e serialização eficiente OK.\n");
        }
    }

    // --- UTILITÁRIOS ---

    private static void assertCondition(boolean condition, String failMessage) {
        if (!condition) throw new AssertionError(failMessage);
    }

    private static void cleanDataDir() throws IOException {
        if (Files.exists(TEST_DATA_DIR)) {
            Files.walk(TEST_DATA_DIR)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
        Files.createDirectories(TEST_DATA_DIR);
        System.out.println("→ Diretório de testes limpo: " + TEST_DATA_DIR + "\n");
    }

    private static void startTestServer() throws IOException {
        ServerState state = new ServerState(MAX_DAYS, MAX_IN_MEMORY, TEST_DATA_DIR);
        serverSocket = new ServerSocket(PORT);

        Thread serverThread = new Thread(() -> {
            try {
                while (!serverSocket.isClosed()) {
                    Socket cs = serverSocket.accept();
                    new Thread(new ClientHandler(state, cs)).start();
                }
            } catch (IOException e) {
                // Socket fechado (shutdown normal)
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        System.out.println("→ Servidor de testes iniciado na porta " + PORT + "\n");
    }

    private static void waitForServerReady() throws InterruptedException {
        // Polling até servidor aceitar conexões
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket test = new Socket(HOST, PORT)) {
                System.out.println("→ Servidor pronto para testes!\n");
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        throw new RuntimeException("Servidor não arrancou a tempo!");
    }

    private static void shutdownServer() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                System.out.println("\n→ Servidor encerrado.");
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    private static void waitWithTimeout(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
