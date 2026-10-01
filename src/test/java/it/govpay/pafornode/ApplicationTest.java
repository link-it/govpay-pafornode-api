package it.govpay.pafornode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class ApplicationTest {

	@Test
	void contextLoads() {
		// verifica che il contesto Spring si avvii
	}
}
