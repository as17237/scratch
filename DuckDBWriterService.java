import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.duckdb.DuckDBAppender; // For efficient batch inserts
import org.duckdb.DuckDBConnection;

class DuckDBWriterService implements Runnable {
    private final BlockingQueue<DataToWrite> queue;
    private final String dbPath; // e.g., "my_test_data.duckdb"
    private volatile boolean running = true;

    public DuckDBWriterService(BlockingQueue<DataToWrite> queue, String dbPath) {
        this.queue = queue;
        this.dbPath = dbPath;
    }

    public void stop() {
        running = false;
        // Offer a "poison pill" to unblock the queue if it's waiting
        // This ensures the thread can exit its loop and close resources.
        // Use a special instance or null, and handle it in the loop.
        try {
            // Create a distinct object for the poison pill if null isn't suitable
            // for your DataToWrite class or if null has another meaning.
            queue.offer(new DataToWrite("POISON_PILL_TABLE", List.of()));
        } catch (Exception e) {
            // Log if offering the poison pill fails, though with LinkedBlockingQueue
            // offer without timeout won't block or throw InterruptedException unless
            // the queue implementation has an issue or is a fixed-size queue that's full.
            System.err.println("Error offering poison pill: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:" + dbPath)) {
            System.out.println("DuckDB Writer Service started. Connected to: " + dbPath);

            // Ensure this connection is a DuckDBConnection for Appender
            if (!(conn instanceof DuckDBConnection)) {
                System.err.println("Connection is not a DuckDBConnection, Appender API not available.");
                // Fallback to PreparedStatement if needed, or throw error
                return;
            }
            DuckDBConnection duckDBConn = (DuckDBConnection) conn;

            while (running || !queue.isEmpty()) {
                DataToWrite dataItem = null;
                try {
                    // Wait for data, with a timeout to periodically check `running` flag
                    dataItem = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("DuckDB Writer Service interrupted while waiting for data.");
                    break; // Exit if interrupted
                }

                if (dataItem != null) {
                    // Check for poison pill
                    if ("POISON_PILL_TABLE".equals(dataItem.getTableName())) {
                        System.out.println("Poison pill received. Shutting down writer.");
                        break;
                    }

                    // Ensure table exists (simplified example, add proper schema management)
                    // This is a simplified check; for production, you'd manage schema more robustly.
                    try (Statement stmt = conn.createStatement()) {
                        // Example: CREATE TABLE IF NOT EXISTS target_table_name (column1_name type, ...);
                        // You'll need to know the schema or derive it.
                        // For simplicity, this example assumes you know the schema or it's pre-created.
                        // stmt.execute("CREATE TABLE IF NOT EXISTS " + dataItem.getTableName() + " (id INTEGER, value VARCHAR)");
                        System.out.println("Ensuring table " + dataItem.getTableName() + " exists (logic to be implemented).");
                    } catch (SQLException e) {
                        System.err.println("Error ensuring table " + dataItem.getTableName() + " exists: " + e.getMessage());
                        // Decide how to handle: skip item, retry, stop service?
                        continue;
                    }


                    // Efficiently insert data using DuckDBAppender
                    try (DuckDBAppender appender = duckDBConn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, dataItem.getTableName())) {
                        for (Object[] row : dataItem.getRows()) {
                            for (int i = 0; i < row.length; i++) {
                                appender.append(row[i]);
                            }
                            appender.endRow();
                        }
                        appender.flush(); // Flush remaining data
                        System.out.println("Wrote " + dataItem.getRows().size() + " rows to " + dataItem.getTableName());
                    } catch (SQLException e) {
                        System.err.println("Error writing data to DuckDB table " + dataItem.getTableName() + ": " + e.getMessage());
                        // Handle write error (e.g., log, potentially re-queue with retry logic, or discard)
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("DuckDB Writer Service failed to connect or encountered a major SQL error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("DuckDB Writer Service stopped.");
        }
    }
}
