package it.govpay.pafornode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import it.govpay.pafornode.send.SendClient;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class ApplicationTest {

	@Autowired
	private SendClient sendClient;

	@Test
	void contextLoads() {
		assertThat(sendClient).isNotNull();
	}

    @Autowired
    private DataSource dataSource;

    /**
     * #29: il pool usa READ_COMMITTED come il core GovPay, non il default del database.
     */
    @Test
    void isolamentoReadCommitted() throws SQLException {
        assertThat(dataSource.unwrap(HikariDataSource.class).getTransactionIsolation()).isEqualTo("TRANSACTION_READ_COMMITTED");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
    }
}
