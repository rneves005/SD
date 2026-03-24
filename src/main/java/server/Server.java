package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;

public class Server {

    public static void main(String[] args) {
        int port = 12345;
        int D = 30;              // max days of data per série
        int S = 5;               // max number of series in memory
        Path storageDir = Path.of("data");

        try {
            ServerState serverState = new ServerState(D, S, storageDir);

            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("Server listening on port " + port);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    ClientHandler handler =
                            new ClientHandler(serverState, clientSocket);
                    new Thread(handler).start();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();  
        }
    }
}