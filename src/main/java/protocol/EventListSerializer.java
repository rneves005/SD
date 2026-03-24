package protocol;

import model.Event;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
* Efficient event serialization.
* Uses string table to avoid repeating product names.
* Format: [products table][events with indexes]
*/
public class EventListSerializer {

    public static void serialize(List<Event> events, DataOutputStream out) throws IOException {
        // Builds a table of unique products
        List<String> productTable = new ArrayList<>();
        Map<String, Integer> productIndex = new HashMap<>();

        for (Event e : events) {
            String product = e.getProduct();
            if (!productIndex.containsKey(product)) {
                productIndex.put(product, productTable.size());
                productTable.add(product);
            }
        }

        // Writes product table
        out.writeInt(productTable.size());
        for (String product : productTable) {
            out.writeUTF(product);
        }

        // Writes events (using indices instead of strings)
        out.writeInt(events.size());
        for (Event e : events) {
            int idx = productIndex.get(e.getProduct());
            out.writeInt(idx);
            out.writeInt(e.getQuantity());
            out.writeDouble(e.getPrice());
        }
    }

    // Deserializes event list
    public static List<Event> deserialize(DataInputStream in) throws IOException {
        // Lê tabela de produtos
        int numProducts = in.readInt();
        String[] productTable = new String[numProducts];
        for (int i = 0; i < numProducts; i++) {
            productTable[i] = in.readUTF();
        }

        
        int numEvents = in.readInt();
        List<Event> events = new ArrayList<>(numEvents);
        for (int i = 0; i < numEvents; i++) {
            int productIdx = in.readInt();
            int quantity = in.readInt();
            double price = in.readDouble();

            String product = productTable[productIdx];
            events.add(new Event(product, quantity, price));
        }

        return events;
    }
}
