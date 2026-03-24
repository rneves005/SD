package client;

import java.io.IOException;
import java.util.Scanner;

/**
 * Simple CLI UI that uses the Client facade (not the underlying protocol).
 * This demonstrates correct separation: UI -> Client -> ClientLibrary -> Demultiplexer -> Server
 */
public class ClientUI {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 12345;

        try (Client client = new Client(host, port); Scanner sc = new Scanner(System.in)) {
            client.setUnsolicitedListener(s -> System.out.println("[UNSOLICITED] " + s));

            while (true) {
                printMenu();
                String cmd = sc.nextLine().trim();
                if (cmd.isEmpty()) continue;
                if (cmd.equalsIgnoreCase("q") || cmd.equalsIgnoreCase("quit")) break;
                
                try {
                    switch (cmd) {
                        case "1" -> { // register
                            System.out.print("user: ");
                            String u = sc.nextLine().trim();
                            System.out.print("pass: ");
                            String p = sc.nextLine().trim();
                            client.register(u, p);
                            System.out.println("registered");
                        }
                        case "2" -> { // auth
                            System.out.print("user: ");
                            String u2 = sc.nextLine().trim();
                            System.out.print("pass: ");
                            String p2 = sc.nextLine().trim();
                            client.authenticate(u2, p2);
                            System.out.println("authenticated");
                        }
                        case "3" -> { // add event
                            System.out.print("product: ");
                            String prod = sc.nextLine().trim();
                            System.out.print("quantity: ");
                            int qn = Integer.parseInt(sc.nextLine().trim());
                            System.out.print("price: ");
                            double pr = Double.parseDouble(sc.nextLine().trim());
                            client.addEvent(prod, qn, pr);
                            System.out.println("event added");
                        }
                        case "4" -> { // next day
                            client.nextDay();
                            System.out.println("next day");
                        }
                        case "5" -> { // get quantity
                            System.out.print("product: ");
                            String gp = sc.nextLine().trim();
                            System.out.print("d: ");
                            int gd = Integer.parseInt(sc.nextLine().trim());
                            System.out.println("quantity=" + client.getQuantity(gp, gd));
                        }
                        case "6" -> { // get volume
                            System.out.print("product: ");
                            String vp = sc.nextLine().trim();
                            System.out.print("d: ");
                            int vd = Integer.parseInt(sc.nextLine().trim());
                            System.out.println("volume=" + client.getVolume(vp, vd));
                        }
                        case "7" -> { // get average
                            System.out.print("product: ");
                            String ap = sc.nextLine().trim();
                            System.out.print("d: ");
                            int ad = Integer.parseInt(sc.nextLine().trim());
                            System.out.println("avg=" + client.getAveragePrice(ap, ad));
                        }
                        case "8" -> { // get max price
                            System.out.print("product: ");
                            String mp = sc.nextLine().trim();
                            System.out.print("d: ");
                            int md = Integer.parseInt(sc.nextLine().trim());
                            System.out.println("max=" + client.getMaxPrice(mp, md));
                        }
                        case "9" -> { // wait simultaneous
                            System.out.print("p1: ");
                            String p1s = sc.nextLine().trim();
                            System.out.print("p2: ");
                            String p2s = sc.nextLine().trim();
                            System.out.println("happened=" + client.waitForSimultaneous(p1s, p2s));
                        }
                        case "10" -> { // wait consecutive
                            System.out.print("n: ");
                            int n = Integer.parseInt(sc.nextLine().trim());
                            String res = client.waitForConsecutive(n);
                            System.out.println("product=" + (res == null ? "(none)" : res));
                        }
                        default -> System.out.println("unknown command");
                    }
                } catch (IOException e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to start client: " + e.getMessage());
        }
    }

    private static void printMenu() {
        System.out.println("--- Menu ---");
        System.out.println("1 - register");
        System.out.println("2 - authenticate");
        System.out.println("3 - add event");
        System.out.println("4 - next day");
        System.out.println("5 - get quantity");
        System.out.println("6 - get volume");
        System.out.println("7 - get average price");
        System.out.println("8 - get max price");
        System.out.println("9 - wait simultaneous");
        System.out.println("10 - wait consecutive");
        System.out.println("q - quit");
        System.out.print("choice> ");
    }
}