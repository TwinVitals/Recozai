package sn.recozai.platform;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.recozai.shared.Transactions;
import java.util.function.Supplier;

@Component
public class SpringTransactions implements Transactions {
    private final TransactionTemplate template;
    public SpringTransactions(PlatformTransactionManager manager) { template = new TransactionTemplate(manager); }
    @Override public <T> T execute(Supplier<T> operation) { return template.execute(status -> operation.get()); }
}
