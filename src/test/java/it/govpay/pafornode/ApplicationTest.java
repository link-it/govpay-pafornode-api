package it.govpay.pafornode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import it.govpay.pafornode.send.SendClient;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class ApplicationTest {

	@Autowired
	private SendClient sendClient;

	@Test
	void contextLoads() {
		assertThat(sendClient).isNotNull();
	}
}
