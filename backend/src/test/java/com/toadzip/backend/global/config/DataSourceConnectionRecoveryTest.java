package com.toadzip.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DataSourceConnectionRecoveryTest {

    @Autowired
    private ApplicationContext applicationContext;

    @ParameterizedTest
    @ValueSource(strings = {"dataSource", "sharedDataSource"})
    void 인증_무응답_연결을_종료하고_같은_풀에서_다시_연결한다(String beanName) throws Exception {
        HikariDataSource configured = applicationContext.getBean(beanName, HikariDataSource.class);
        try (TcpProxy proxy = new TcpProxy(configured)) {
            proxy.unresponsive.set(true);
            try (HikariDataSource pool = recoveryPool(configured, proxy)) {
                try {
                    assertThrows(SQLException.class, pool::getConnection);
                    assertThat(proxy.accepted.await(3, TimeUnit.SECONDS)).isTrue();
                    assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
                    // loginTimeout alone returns the caller but can leave its JDBC worker/socket alive.
                    assertThat(proxy.disconnected.await(5, TimeUnit.SECONDS)).isTrue();
                    proxy.unresponsive.set(false);
                    pool.setConnectionTimeout(5_000);
                    assertQuerySucceeds(pool);
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                }
                finally {
                    proxy.disconnectClients();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dataSource", "sharedDataSource"})
    void 사용_중_응답이_끊긴_연결을_폐기하고_새_연결로_복구한다(String beanName) throws Exception {
        HikariDataSource configured = applicationContext.getBean(beanName, HikariDataSource.class);
        try (TcpProxy proxy = new TcpProxy(configured);
                HikariDataSource pool = recoveryPool(configured, proxy)) {
            pool.setConnectionTimeout(5_000);
            assertQuerySucceeds(pool);
            try (Connection connection = pool.getConnection(); var statement = connection.createStatement()) {
                proxy.unresponsive.set(true);
                assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1"));
            }
            proxy.unresponsive.set(false);
            assertQuerySucceeds(pool);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    private HikariDataSource recoveryPool(HikariDataSource configured, TcpProxy proxy) {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + proxy.listener.getLocalPort()
                + proxy.target.getPath() + "?sslmode=disable&gssEncMode=disable");
        configuration.setUsername(configured.getUsername());
        configuration.setPassword(configured.getPassword());
        configuration.setDataSourceProperties(configured.getDataSourceProperties());
        configuration.addDataSourceProperty("connectTimeout", "1");
        configuration.addDataSourceProperty("loginTimeout", "1");
        configuration.addDataSourceProperty("socketTimeout", "2");
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(1);
        configuration.setInitializationFailTimeout(-1);
        configuration.setConnectionTimeout(500);
        configuration.setValidationTimeout(250);
        return new HikariDataSource(configuration);
    }

    private void assertQuerySucceeds(HikariDataSource pool) throws SQLException {
        try (Connection connection = pool.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    private static final class TcpProxy implements AutoCloseable {

        private final URI target;
        private final ServerSocket listener;
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final AtomicBoolean unresponsive = new AtomicBoolean();
        private final CountDownLatch accepted = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);

        private TcpProxy(HikariDataSource dataSource) throws IOException {
            target = URI.create(dataSource.getJdbcUrl().substring("jdbc:".length()));
            if (!InetAddress.getByName(target.getHost()).isLoopbackAddress()) {
                throw new IllegalArgumentException("복구 시험은 로컬 테스트 DB에만 연결합니다.");
            }
            listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            workers.submit(this::acceptConnections);
        }

        private void acceptConnections() {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    sockets.add(client);
                    accepted.countDown();
                    workers.submit(() -> handle(client));
                }
                catch (IOException exception) {
                    if (!listener.isClosed()) {
                        throw new IllegalStateException(exception);
                    }
                }
            }
        }

        private void handle(Socket client) {
            try (client) {
                if (unresponsive.get()) {
                    client.getInputStream().transferTo(OutputStream.nullOutputStream());
                    disconnected.countDown();
                    return;
                }
                try (Socket server = new Socket(target.getHost(), target.getPort())) {
                    sockets.add(server);
                    workers.submit(() -> forward(server, client, true));
                    forward(client, server, false);
                }
            }
            catch (IOException ignored) {
                // Connection timeouts and explicit teardown are expected in this fixture.
            }
        }

        private void forward(Socket source, Socket destination, boolean response) {
            try {
                InputStream input = source.getInputStream();
                OutputStream output = destination.getOutputStream();
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (!response || !unresponsive.get()) {
                        output.write(buffer, 0, count);
                        output.flush();
                    }
                }
            }
            catch (IOException ignored) {
                // A timed-out JDBC connection closes both forwarding directions.
            }
        }

        private void disconnectClients() throws IOException {
            for (Socket socket : sockets) {
                socket.close();
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            disconnectClients();
            workers.shutdownNow();
        }
    }
}
