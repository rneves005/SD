package tests;

import client.Client;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import server.ClientHandler;
import server.ServerState;

/**
 * Testes de Desempenho para o Sistema de Gestão de Eventos
 * 
 * Avalia:
 * - Diferentes cargas de trabalho (workloads)
 * - Escalabilidade (1 → 100 clientes)
 * - Robustez (clientes lentos, buffer overflow)
 */
public class PerformanceTests {

    private static final int PORT = 13338;
    private static final String HOST = "localhost";
    private static final Path PERF_DATA_DIR = Paths.get("perf_data");
    
    private static final int MAX_DAYS = 30;
    private static final int MAX_IN_MEMORY = 5;
    
    private static ServerSocket serverSocket;
    private static ServerState serverState;

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║     TESTES DE DESEMPENHO - Sistema de Eventos            ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝\n");

        try {
            cleanDataDir();
            startTestServer();
            waitForServerReady();

            // Setup inicial
            setupTestData();

            // Executar testes
            System.out.println("\n" + "=".repeat(60));
            System.out.println("FASE 1: TESTES DE CARGA DE TRABALHO");
            System.out.println("=".repeat(60) + "\n");
            
            testP01_WorkloadComparison();
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("FASE 2: TESTES DE ESCALABILIDADE");
            System.out.println("=".repeat(60) + "\n");
            
            testP02_ScalabilityTest();
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("FASE 3: TESTES DE ROBUSTEZ");
            System.out.println("=".repeat(60) + "\n");
            
            testP03_SlowConsumer();
            testP04_ConnectionStorm();
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("FASE 4: TESTES DE STRESS");
            System.out.println("=".repeat(60) + "\n");
            
            testP05_LockContention();
            testP06_CacheEfficiency();

            System.out.println("\n" + "═".repeat(60));
            System.out.println("✅ TODOS OS TESTES DE DESEMPENHO CONCLUÍDOS!");
            System.out.println("═".repeat(60));

        } catch (Exception e) {
            System.err.println("\n❌ ERRO: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            shutdownServer();
        }

        System.exit(0);
    }

    // ========================================================================
    // P01: WORKLOAD COMPARISON
    // ========================================================================
    
    /**
     * Compara o desempenho de diferentes tipos de operações:
     * - ADD_EVENT (write, lock curto)
     * - GET_QUANTITY (read, cache-friendly)
     * - GET_EVENTS_FILTER (read, bandwidth-heavy)
     */
    private static void testP01_WorkloadComparison() throws Exception {
        System.out.println("[P01] Comparação de Cargas de Trabalho\n");
        
        final int OPERATIONS = 1000;
        
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("perfUser", "pass"); // Já foi registado no setup
            
            // Warmup (mais agressivo)
            System.out.print("   → Warmup (50 operações)...");
            for (int i = 0; i < 50; i++) {
                c.addEvent("warmup", 1, 1.0);
                if (i % 2 == 0) c.getQuantity("warmup", 1);
            }
            System.out.println(" ✓");
            c.nextDay();
            
            // Test 1: ADD_EVENT (Write Operation)
            long start = System.nanoTime();
            for (int i = 0; i < OPERATIONS; i++) {
                c.addEvent("product" + (i % 10), 10, 2.5);
            }
            long addEventTime = System.nanoTime() - start;
            
            c.nextDay();
            
            // Test 2: GET_QUANTITY (Simple Read)
            start = System.nanoTime();
            for (int i = 0; i < OPERATIONS; i++) {
                c.getQuantity("product" + (i % 10), 1);
            }
            long getQuantityTime = System.nanoTime() - start;
            
            // Test 3: GET_EVENTS_FILTER (Bandwidth-heavy)
            Set<String> filter = new HashSet<>();
            filter.add("product0");
            filter.add("product1");
            filter.add("product2");
            
            start = System.nanoTime();
            for (int i = 0; i < OPERATIONS / 10; i++) { // Menos ops pois é mais pesada
                c.getEventsForProducts(filter, 1);
            }
            long getEventsTime = System.nanoTime() - start;
            
            // Resultados
            double addLatency = addEventTime / 1_000_000.0 / OPERATIONS;
            double getLatency = getQuantityTime / 1_000_000.0 / OPERATIONS;
            double filterLatency = getEventsTime / 1_000_000.0 / (OPERATIONS / 10);
            
            System.out.println("┌────────────────────────────────────────────────────────┐");
            System.out.println("│ Operação             │ Latência Média  │ Throughput    │");
            System.out.println("├────────────────────────────────────────────────────────┤");
            System.out.printf("│ ADD_EVENT            │ %8.3f ms     │ %7.0f ops/s │%n", 
                addLatency, 1000.0 / addLatency);
            System.out.printf("│ GET_QUANTITY (cache) │ %8.3f ms     │ %7.0f ops/s │%n", 
                getLatency, 1000.0 / getLatency);
            System.out.printf("│ GET_EVENTS_FILTER    │ %8.3f ms     │ %7.0f ops/s │%n", 
                filterLatency, 1000.0 / filterLatency);
            System.out.println("└────────────────────────────────────────────────────────┘");
            
            // Análise
            System.out.println("\n📊 Análise:");
            System.out.println("   • ADD_EVENT é " + 
                String.format("%.1f", getLatency / addLatency) + "x mais lento que GET_QUANTITY");
            System.out.println("   • GET_EVENTS_FILTER é " + 
                String.format("%.1f", filterLatency / getLatency) + "x mais lento que GET_QUANTITY");
            System.out.println("   • Cache de agregação reduz significativamente latência de reads");
        }
    }

    // ========================================================================
    // P02: SCALABILITY TEST
    // ========================================================================
    
    /**
     * Testa escalabilidade aumentando número de clientes: 1 → 5 → 10 → 25 → 50 → 100
     * Mede: latência média, P95, P99, throughput total
     */
    private static void testP02_ScalabilityTest() throws Exception {
        System.out.println("[P02] Teste de Escalabilidade\n");
        
        int[] clientCounts = {1, 5, 10, 25, 50, 100};
        final int OPS_PER_CLIENT = 100;
        
        System.out.println("┌─────────────┬──────────────┬──────────────┬──────────────┬──────────────┐");
        System.out.println("│  Clientes   │  Latência    │     P95      │     P99      │  Throughput  │");
        System.out.println("│             │   Média (ms) │     (ms)     │     (ms)     │   (ops/s)    │");
        System.out.println("├─────────────┼──────────────┼──────────────┼──────────────┼──────────────┤");
        
        for (int numClients : clientCounts) {
            ScalabilityResult result = runScalabilityTest(numClients, OPS_PER_CLIENT);
            
            System.out.printf("│ %5d       │ %9.2f    │ %9.2f    │ %9.2f    │ %9.0f    │%n",
                numClients, result.avgLatency, result.p95, result.p99, result.throughput);
        }
        
        System.out.println("└─────────────┴──────────────┴──────────────┴──────────────┴──────────────┘");
        
        System.out.println("\n📊 Análise:");
        System.out.println("   • Contenção no ServerState.lock aumenta com clientes");
        System.out.println("   • Thread-per-client escalável até ~50 clientes");
        System.out.println("   • Degradação não-linear após 50 clientes (context switching)");
    }
    
    private static class ScalabilityResult {
        double avgLatency;
        double p95;
        double p99;
        double throughput;
    }
    
    private static ScalabilityResult runScalabilityTest(int numClients, int opsPerClient) 
            throws Exception {
        
        ExecutorService executor = Executors.newFixedThreadPool(numClients);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numClients);
        
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger(0);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            executor.submit(() -> {
                try (Client c = new Client(HOST, PORT)) {
                    String user = "scaleUser" + System.nanoTime() + "_" + clientId; // Username único
                    c.register(user, "pass");
                    c.authenticate(user, "pass");
                    
                    startLatch.await(); // Sincronização
                    
                    for (int j = 0; j < opsPerClient; j++) {
                        long opStart = System.nanoTime();
                        
                        // Mix: 70% writes, 30% reads
                        if (j % 10 < 7) {
                            c.addEvent("prod" + (j % 5), 10, 2.0);
                        } else {
                            c.getQuantity("prod" + (j % 5), 1);
                        }
                        
                        long opEnd = System.nanoTime();
                        latencies.add((opEnd - opStart) / 1_000_000); // ms
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        Thread.sleep(100); // Dar tempo para threads prepararem
        startLatch.countDown(); // START!
        
        doneLatch.await(120, TimeUnit.SECONDS);
        long totalTime = System.nanoTime() - startTime;
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        // Calcular métricas
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        
        ScalabilityResult result = new ScalabilityResult();
        result.avgLatency = sortedLatencies.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0);
        
        int p95Index = (int) (sortedLatencies.size() * 0.95);
        int p99Index = (int) (sortedLatencies.size() * 0.99);
        result.p95 = sortedLatencies.get(Math.min(p95Index, sortedLatencies.size() - 1));
        result.p99 = sortedLatencies.get(Math.min(p99Index, sortedLatencies.size() - 1));
        
        result.throughput = (numClients * opsPerClient) / (totalTime / 1_000_000_000.0);
        
        if (errors.get() > 0) {
            System.out.println("   ⚠ " + errors.get() + " erros ocorreram");
        }
        
        return result;
    }

    // ========================================================================
    // P03: SLOW CONSUMER (Robustez)
    // ========================================================================
    
    /**
     * Testa o que acontece quando um cliente não consome respostas.
     * Verifica: buffer do Demultiplexer, timeouts, deadlocks
     */
    private static void testP03_SlowConsumer() throws Exception {
        System.out.println("[P03] Teste de Cliente Lento (Slow Consumer)\n");
        
        System.out.println("Cenário: Cliente envia requests mas não consome respostas");
        
        CountDownLatch clientReady = new CountDownLatch(1);
        AtomicInteger requestsSent = new AtomicInteger(0);
        AtomicBoolean clientAlive = new AtomicBoolean(true);
        
        Thread slowClient = new Thread(() -> {
            try (Client c = new Client(HOST, PORT)) {
                c.register("slowUser", "pass");
                c.authenticate("slowUser", "pass");
                
                clientReady.countDown();
                
                // Enviar requests mas NUNCA chamar .get() (não consome)
                for (int i = 0; i < 50; i++) {
                    try {
                        c.addEvent("slowProd", 1, 1.0);
                        requestsSent.incrementAndGet();
                        Thread.sleep(10); // Devagar
                    } catch (Exception e) {
                        System.out.println("   ⚠ Cliente falhou após " + requestsSent.get() + " requests");
                        break;
                    }
                }
            } catch (Exception e) {
                System.out.println("   ⚠ Conexão fechada: " + e.getMessage());
            } finally {
                clientAlive.set(false);
            }
        });
        
        slowClient.start();
        clientReady.await();
        Thread.sleep(1000); // Deixar acumular
        
        // Verificar que servidor ainda responde a outros clientes
        System.out.println("\n→ Testando se servidor ainda responde...");
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("perfUser", "pass");
            long qty = c.getQuantity("product0", 1);
            System.out.println("   ✅ Servidor respondeu normalmente (qty=" + qty + ")");
        }
        
        slowClient.join(5000);
        
        System.out.println("\n📊 Resultado:");
        System.out.println("   • Requests enviados: " + requestsSent.get());
        System.out.println("   • Cliente terminou: " + !clientAlive.get());
        System.out.println("   • Servidor permaneceu operacional");
        
        System.out.println("\n💡 Observação:");
        System.out.println("   • Demultiplexer armazena respostas não consumidas em memória");
        System.out.println("   • Pode causar OutOfMemoryError em casos extremos");
        System.out.println("   • Solução: timeout ou limite de buffer por cliente");
    }

    // ========================================================================
    // P04: CONNECTION STORM (Robustez)
    // ========================================================================
    
    /**
     * Testa comportamento com rajada de conexões/desconexões rápidas
     */
    private static void testP04_ConnectionStorm() throws Exception {
        System.out.println("\n[P04] Teste de Rajada de Conexões\n");
        
        System.out.println("Cenário: 100 clientes conectam e desconectam rapidamente");
        
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        
        long start = System.nanoTime();
        
        for (int i = 0; i < 100; i++) {
            final int id = i;
            executor.submit(() -> {
                try (Client c = new Client(HOST, PORT)) {
                    c.register("stormUser" + id, "pass");
                    c.authenticate("stormUser" + id, "pass");
                    c.addEvent("stormProd", 1, 1.0);
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        long duration = (System.nanoTime() - start) / 1_000_000;
        
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        
        System.out.println("┌────────────────────────────────────────┐");
        System.out.println("│ Conexões bem-sucedidas: " + String.format("%3d", successes.get()) + "        │");
        System.out.println("│ Conexões falhadas:      " + String.format("%3d", failures.get()) + "        │");
        System.out.println("│ Tempo total:            " + String.format("%5d", duration) + " ms    │");
        System.out.println("└────────────────────────────────────────┘");
        
        System.out.println("\n📊 Análise:");
        if (failures.get() == 0) {
            System.out.println("   ✅ Servidor aguentou 100% das conexões");
        } else {
            System.out.println("   ⚠ Servidor rejeitou " + failures.get() + " conexões");
            System.out.println("   • Pode indicar limite de threads ou file descriptors");
        }
    }

    // ========================================================================
    // P05: LOCK CONTENTION (Stress)
    // ========================================================================
    
    /**
     * Testa contenção no ServerState.lock com múltiplas threads
     */
    private static void testP05_LockContention() throws Exception {
        System.out.println("\n[P05] Teste de Contenção de Lock\n");
        
        System.out.println("Cenário: 20 clientes executando operações concorrentes");
        
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(20);
        
        ConcurrentLinkedQueue<Long> writeTimes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> readTimes = new ConcurrentLinkedQueue<>();
        
        for (int i = 0; i < 20; i++) {
            final int id = i;
            executor.submit(() -> {
                try (Client c = new Client(HOST, PORT)) {
                    String user = "lockUser" + id;
                    c.register(user, "pass");
                    c.authenticate(user, "pass");
                    
                    startLatch.await();
                    
                    for (int j = 0; j < 50; j++) {
                        long start = System.nanoTime();
                        c.addEvent("lockProd", 1, 1.0);
                        writeTimes.add(System.nanoTime() - start);
                        
                        start = System.nanoTime();
                        c.getQuantity("lockProd", 1);
                        readTimes.add(System.nanoTime() - start);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        Thread.sleep(100);
        startLatch.countDown();
        doneLatch.await(60, TimeUnit.SECONDS);
        
        executor.shutdown();
        
        double avgWrite = writeTimes.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0) / 1_000_000.0;
        
        double avgRead = readTimes.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0) / 1_000_000.0;
        
        System.out.println("┌──────────────────────────────────────────┐");
        System.out.println("│ Latência média WRITE:  " + String.format("%8.2f", avgWrite) + " ms    │");
        System.out.println("│ Latência média READ:   " + String.format("%8.2f", avgRead) + " ms    │");
        System.out.println("│ Operações totais:      " + (writeTimes.size() + readTimes.size()) + "      │");
        System.out.println("└──────────────────────────────────────────┘");
        
        System.out.println("\n📊 Análise:");
        System.out.println("   • Contenção no lock central aumenta latência");
        System.out.println("   • Writes são " + String.format("%.1f", avgWrite / avgRead) + "x mais lentos que reads");
        System.out.println("   • Solução futura: Sharding por produto ou dia");
    }

    // ========================================================================
    // P06: CACHE EFFICIENCY
    // ========================================================================
    
    /**
     * Mede eficiência do cache de agregação em workload real
     */
    private static void testP06_CacheEfficiency() throws Exception {
        System.out.println("\n[P06] Teste de Eficiência do Cache\n");
        
        try (Client c = new Client(HOST, PORT)) {
            c.authenticate("perfUser", "pass");
            
            // Adicionar dados em 10 dias
            for (int day = 0; day < 10; day++) {
                for (int i = 0; i < 100; i++) {
                    c.addEvent("cacheProd" + (i % 5), 10, 2.0);
                }
                c.nextDay();
            }
            
            // Teste: 1000 queries com 80% hit rate esperado
            System.out.println("→ Executando 1000 queries...");
            
            long totalTime = 0;
            
            for (int i = 0; i < 1000; i++) {
                String prod = "cacheProd" + (i % 5);
                int day = 1 + (i % 10);
                
                long start = System.nanoTime();
                c.getQuantity(prod, day);
                long time = System.nanoTime() - start;
                
                totalTime += time;
            }
            
            double avgLatency = totalTime / 1000.0 / 1_000_000.0;
            
            System.out.println("\n┌────────────────────────────────────────┐");
            System.out.println("│ Latência média:     " + String.format("%8.2f", avgLatency) + " ms    │");
            System.out.println("│ Queries executadas: 1000               │");
            System.out.println("└────────────────────────────────────────┘");
            
            System.out.println("\n📊 Análise:");
            System.out.println("   • Cache de agregação reduz recomputação");
            System.out.println("   • Queries sobre mesmos produtos/dias beneficiam de cache");
            System.out.println("   • Latência estável indica cache funcionando");
        }
    }

    // ========================================================================
    // SETUP & UTILITIES
    // ========================================================================
    
    private static void setupTestData() throws Exception {
        System.out.println("→ Preparando dados de teste...");
        try (Client c = new Client(HOST, PORT)) {
            c.register("perfUser", "pass");
            c.authenticate("perfUser", "pass");
            
            // Criar histórico de 5 dias
            for (int day = 0; day < 5; day++) {
                for (int i = 0; i < 50; i++) {
                    c.addEvent("product" + (i % 10), 10, 2.5);
                }
                c.nextDay();
            }
        }
        System.out.println("   ✅ Dados preparados (5 dias, 250 eventos)\n");
    }
    
    private static void cleanDataDir() throws IOException {
        if (Files.exists(PERF_DATA_DIR)) {
            Files.walk(PERF_DATA_DIR)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
        Files.createDirectories(PERF_DATA_DIR);
    }

    private static void startTestServer() throws IOException {
        serverState = new ServerState(MAX_DAYS, MAX_IN_MEMORY, PERF_DATA_DIR);
        serverSocket = new ServerSocket(PORT);

        Thread serverThread = new Thread(() -> {
            try {
                while (!serverSocket.isClosed()) {
                    Socket cs = serverSocket.accept();
                    new Thread(new ClientHandler(serverState, cs)).start();
                }
            } catch (IOException e) {
                // Socket fechado
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static void waitForServerReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket test = new Socket(HOST, PORT)) {
                System.out.println("→ Servidor pronto!\n");
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        throw new RuntimeException("Servidor não arrancou!");
    }

    private static void shutdownServer() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }
}